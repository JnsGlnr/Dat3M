package com.dat3m.dartagnan.solver.caat4wmm.coreReasoning;

import com.dat3m.dartagnan.encoding.EncodingContext;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.wmm.Constraint;
import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.definition.*;
import com.dat3m.dartagnan.wmm.utils.graph.EventGraph;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;

import java.util.*;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

public record TrivialImplications(Map<Relation, Map<Relation, Map<RelLiteral, Set<RelLiteral>>>> trivialImplications) {

    public BooleanFormula encode(EncodingContext context) {
        final BooleanFormulaManager bmgr = context.getBooleanFormulaManager();
        final List<BooleanFormula> enc = new ArrayList<>();
        for (Map.Entry<Relation, Map<Relation, Map<RelLiteral, Set<RelLiteral>>>> implicationsForConstraint : trivialImplications.entrySet()) {
            final Relation eazyRel = implicationsForConstraint.getKey();
            for (Map.Entry<Relation, Map<RelLiteral, Set<RelLiteral>>> implicationsForConstraintAndRel : implicationsForConstraint.getValue().entrySet()) {
                final Relation rel = implicationsForConstraintAndRel.getKey();
                if (rel != eazyRel) {
                    for (Map.Entry<RelLiteral, Set<RelLiteral>> reasonsForEdge : implicationsForConstraintAndRel.getValue().entrySet()) {
                        final RelLiteral first = reasonsForEdge.getKey();
                        for (RelLiteral second : reasonsForEdge.getValue()) {
                            enc.add(bmgr.implication(context.edge(rel, first.getSource(), first.getTarget()), context.edge(eazyRel, second.getSource(), second.getTarget())));
                        }
                    }
                }
            }
        }
        return bmgr.and(enc);
    }

    public boolean isTrivial(Relation reasonRel, Relation impliedRel, Event event) {
        return isTrivial(reasonRel, impliedRel, event, event);
    }

    public boolean isTrivial(Relation reasonRel, Relation impliedRel, Event first, Event second) {
        if (reasonRel == impliedRel) {
            return true;
        }
        final Map<RelLiteral, Set<RelLiteral>> trivialImplicationsForDefAndRel =
                getTrivialImplicationForDefAndRel(impliedRel, reasonRel);
        if (trivialImplicationsForDefAndRel == null) {
            return false;
        }
        final Set<RelLiteral> trivialImplicationsWithEvent = trivialImplicationsForDefAndRel.get(new RelLiteral(reasonRel, first, second, true));
        if (trivialImplicationsWithEvent == null) {
            return false;
        }
        return trivialImplicationsWithEvent.contains(new RelLiteral(impliedRel, first, second, true));
    }

    private Map<RelLiteral, Set<RelLiteral>> getTrivialImplicationForDefAndRel(Relation constraint, Relation rel) {
        final Map<Relation, Map<RelLiteral, Set<RelLiteral>>> trivialImplicationsForDef = trivialImplications.get(constraint);
        if (trivialImplicationsForDef == null) {
            return null;
        }
        return trivialImplicationsForDef.get(rel);
    }

    public static Map<Relation, Map<RelLiteral, Set<RelLiteral>>> getTrivialImplications(
            final EncodingContext context, final Relation eazyRel, final EventGraph encodeSet
    ) {
        final TrivialImplicationsVisitor visitor = new TrivialImplicationsVisitor(context, eazyRel, encodeSet);
        return eazyRel.getDefinition().accept(visitor);
    }

    private static class TrivialImplicationsVisitor implements Constraint.Visitor<Map<Relation, Map<RelLiteral, Set<RelLiteral>>>> {

        private final EncodingContext context;
        private final RelationAnalysis ra;
        private final Relation eazyRel;
        private final Set<Definition> visited;

        private Map<RelLiteral, Set<RelLiteral>> implications;

        private TrivialImplicationsVisitor(final EncodingContext context, final Relation eazyRel, final EventGraph encodeSet) {
            this.context = context;
            this.ra = context.getAnalysisContext().get(RelationAnalysis.class);
            this.eazyRel = eazyRel;
            this.visited = new HashSet<>();
            this.implications = new LinkedHashMap<>();
            final EventGraph mustSet = ra.getKnowledge(eazyRel).getMustSet();
            encodeSet.apply((e1, e2) -> {
                if (!mustSet.contains(e1, e2)) {
                    final RelLiteral literal = new RelLiteral(eazyRel, e1, e2, true);
                    implications.computeIfAbsent(literal, k -> new LinkedHashSet<>()).add(literal);
                }
            });

            visited.add(eazyRel.getDefinition());
        }

        private Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visit(final Definition definition, final Map<RelLiteral, Set<RelLiteral>> implications) {
            final Relation rel = definition.getDefinedRelation();
            if (rel != eazyRel && context.isEncoded(definition)) {
                return singletonMap(rel, implications);
            }
            if (visited.add(definition)) {
                final Map<RelLiteral, Set<RelLiteral>> oldImplications = this.implications;
                this.implications = implications;
                final Map<Relation, Map<RelLiteral, Set<RelLiteral>>> result = definition.accept(this);
                this.implications = oldImplications;
                return result;
            }
            return emptyMap();
        }

        @Override
        public Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visitDefinition(final Definition definition) {
            return emptyMap();
        }

        @Override
        public Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visitUnion(final Union union) {
            return visitSimple(union);
        }

        @Override
        public Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visitIntersection(final Intersection intersection) {
            final Map<Relation, Map<RelLiteral, Set<RelLiteral>>> trivialImplications = new LinkedHashMap<>();
            final Collection<Relation> operands = intersection.getOperands();
            for (final Relation relation : operands) {
                final Map<RelLiteral, Set<RelLiteral>> curImplications = new LinkedHashMap<>();
                final EventGraph maySet = ra.getKnowledge(relation).getMaySet();
                for (final Map.Entry<RelLiteral, Set<RelLiteral>> implicationsForReason : implications.entrySet()) {
                    final RelLiteral reason = implicationsForReason.getKey();
                    final Event first = reason.getSource();
                    final Event second = reason.getTarget();
                    if (maySet.contains(first, second)) {
                        boolean otherConditionsHold = true;
                        for (final Relation otherRelation : operands) {
                            if (relation != otherRelation && !ra.getKnowledge(otherRelation).getMustSet().contains(first, second)) {
                                otherConditionsHold = false;
                                break;
                            }
                        }
                        if (otherConditionsHold) {
                            final RelLiteral newReason = new RelLiteral(relation, first, second, true);
                            curImplications.put(newReason, implicationsForReason.getValue());
                        }
                    }
                }
                if (!curImplications.isEmpty()) {
                    merge(trivialImplications, visit(relation.getDefinition(), curImplications));
                }
            }
            return trivialImplications;
        }

        @Override
        public Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visitInverse(final Inverse inverse) {
            final Map<Relation, Map<RelLiteral, Set<RelLiteral>>> trivialImplications = new LinkedHashMap<>();
            final Relation relation = inverse.getOperand();
            final Map<RelLiteral, Set<RelLiteral>> curImplications = new LinkedHashMap<>();
            for (final Map.Entry<RelLiteral, Set<RelLiteral>> implicationsForReason : implications.entrySet()) {
                final RelLiteral reason = implicationsForReason.getKey();
                final Event first = reason.getSource();
                final Event second = reason.getTarget();
                final RelLiteral newReason = new RelLiteral(relation, second, first, true);
                curImplications.put(newReason, implicationsForReason.getValue());
            }
            if (!curImplications.isEmpty()) {
                merge(trivialImplications, visit(relation.getDefinition(), curImplications));
            }
            return trivialImplications;
        }

        @Override
        public Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visitComposition(final Composition composition) {
            final Map<Relation, Map<RelLiteral, Set<RelLiteral>>> trivialImplications = new LinkedHashMap<>();
            final Relation left = composition.getLeftOperand();
            final Relation right = composition.getRightOperand();
            for (final Relation relation : new Relation[] {left, right}) {
                final boolean isLeft = relation == left;
                final EventGraph otherMustOperands = ra.getKnowledge(isLeft ? right : left).getMustSet();
                final Map<RelLiteral, Set<RelLiteral>> curImplications = new LinkedHashMap<>();
                final EventGraph maySet = ra.getKnowledge(relation).getMaySet();
                for (final Map.Entry<RelLiteral, Set<RelLiteral>> implicationsForReason : implications.entrySet()) {
                    final RelLiteral reason = implicationsForReason.getKey();
                    final Event first = reason.getSource();
                    final Event second = reason.getTarget();
                    if (maySet.contains(first, second)) {
                        final Event commonEvent = isLeft ? second : first;
                        if (otherMustOperands.contains(commonEvent, commonEvent)) {
                            final RelLiteral newReason = new RelLiteral(relation, first, second, true);
                            curImplications.put(newReason, implicationsForReason.getValue());
                        }
                    }
                }
                if (!curImplications.isEmpty()) {
                    merge(trivialImplications, visit(relation.getDefinition(), curImplications));
                }
            }
            return trivialImplications;
        }

        @Override
        public Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visitSetIdentity(final SetIdentity setIdentity) {
            return visitSimple(setIdentity);
        }

        @Override
        public Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visitTransitiveClosure(final TransitiveClosure transitive) {
            return visitSimple(transitive);
        }

        private Map<Relation, Map<RelLiteral, Set<RelLiteral>>> visitSimple(final Definition simpleDefinition) {
            final Map<Relation, Map<RelLiteral, Set<RelLiteral>>> trivialImplications = new LinkedHashMap<>();
            for (final Constraint dep : Wmm.computeConstraintDependencies(simpleDefinition)) {
                final Definition definition = (Definition) dep;
                final Relation relation = definition.getDefinedRelation();
                final Map<RelLiteral, Set<RelLiteral>> curImplications = new LinkedHashMap<>();
                final EventGraph maySet = ra.getKnowledge(relation).getMaySet();
                for (final Map.Entry<RelLiteral, Set<RelLiteral>> implicationsForReason : implications.entrySet()) {
                    final RelLiteral reason = implicationsForReason.getKey();
                    final Event first = reason.getSource();
                    final Event second = reason.getTarget();
                    if (maySet.contains(first, second)) {
                        final RelLiteral newReason = new RelLiteral(relation, first, second, true);
                        curImplications.put(newReason, implicationsForReason.getValue());
                    }
                }
                if (!curImplications.isEmpty()) {
                    merge(trivialImplications, visit(definition, curImplications));
                }
            }
            return trivialImplications;
        }

        private static void merge(final Map<Relation, Map<RelLiteral, Set<RelLiteral>>> curImplications,
                                  final Map<Relation, Map<RelLiteral, Set<RelLiteral>>> newImplications) {
            for (final Map.Entry<Relation, Map<RelLiteral, Set<RelLiteral>>> newImplicationsForRel : newImplications.entrySet()) {
                final Map<RelLiteral, Set<RelLiteral>> curRelImplications =
                        curImplications.computeIfAbsent(newImplicationsForRel.getKey(), k -> new LinkedHashMap<>());
                final Map<RelLiteral, Set<RelLiteral>> newRelImplications = newImplicationsForRel.getValue();
                for (final Map.Entry<RelLiteral, Set<RelLiteral>> newRelImplicationsForEvent : newRelImplications.entrySet()) {
                    curRelImplications.computeIfAbsent(newRelImplicationsForEvent.getKey(), k -> new LinkedHashSet<>())
                            .addAll(newRelImplicationsForEvent.getValue());
                }
            }
        }
    }
}

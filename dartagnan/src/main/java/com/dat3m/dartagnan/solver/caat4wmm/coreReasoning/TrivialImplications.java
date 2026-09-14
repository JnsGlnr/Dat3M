package com.dat3m.dartagnan.solver.caat4wmm.coreReasoning;

import com.dat3m.dartagnan.encoding.EncodingContext;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.wmm.Relation;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;

import java.util.*;

public record TrivialImplications(Map<Relation, Map<Relation, Map<RelLiteral, List<RelLiteral>>>> trivialImplications) {

    public BooleanFormula encode(EncodingContext context) {
        final BooleanFormulaManager bmgr = context.getBooleanFormulaManager();
        final List<BooleanFormula> enc = new ArrayList<>();
        for (Map.Entry<Relation, Map<Relation, Map<RelLiteral, List<RelLiteral>>>> implicationsForConstraint : trivialImplications.entrySet()) {
            final Relation eazyRel = implicationsForConstraint.getKey();
            for (Map.Entry<Relation, Map<RelLiteral, List<RelLiteral>>> implicationsForConstraintAndRel : implicationsForConstraint.getValue().entrySet()) {
                final Relation rel = implicationsForConstraintAndRel.getKey();
                if (rel != eazyRel) {
                    for (Map.Entry<RelLiteral, List<RelLiteral>> reasonsForEdge : implicationsForConstraintAndRel.getValue().entrySet()) {
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
        final Map<RelLiteral, List<RelLiteral>> trivialImplicationsForDefAndRel =
                getTrivialImplicationForDefAndRel(impliedRel, reasonRel);
        if (trivialImplicationsForDefAndRel == null) {
            return false;
        }
        final List<RelLiteral> trivialImplicationsWithEvent = trivialImplicationsForDefAndRel.get(new RelLiteral(reasonRel, first, second, true));
        if (trivialImplicationsWithEvent == null) {
            return false;
        }
        return trivialImplicationsWithEvent.contains(new RelLiteral(impliedRel, first, second, true));
    }

    private Map<RelLiteral, List<RelLiteral>> getTrivialImplicationForDefAndRel(Relation constraint, Relation rel) {
        final Map<Relation, Map<RelLiteral, List<RelLiteral>>> trivialImplicationsForDef = trivialImplications.get(constraint);
        if (trivialImplicationsForDef == null) {
            return null;
        }
        return trivialImplicationsForDef.get(rel);
    }

    public static Map<RelLiteral, List<RelLiteral>> simpleImplications(final Relation reasonRel, final Relation impliedRel, final Map<Event, List<Event>> events) {
        final Map<RelLiteral, List<RelLiteral>> simpleImplications = new LinkedHashMap<>();
        for (final Map.Entry<Event, List<Event>> edge : events.entrySet()) {
            final Event first = edge.getKey();
            for (final Event second : edge.getValue()) {
                final RelLiteral reason = new RelLiteral(reasonRel, first, second, true);
                final RelLiteral implied = new RelLiteral(impliedRel, first, second, true);
                simpleImplications.computeIfAbsent(reason, k -> new ArrayList<>()).add(implied);
            }
        }
        return simpleImplications;
    }
}

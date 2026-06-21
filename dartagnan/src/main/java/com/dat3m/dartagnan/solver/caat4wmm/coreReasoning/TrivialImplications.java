package com.dat3m.dartagnan.solver.caat4wmm.coreReasoning;

import com.dat3m.dartagnan.encoding.EncodingContext;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.wmm.Constraint;
import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.axiom.Axiom;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record TrivialImplications(Map<Relation, Map<Relation, Map<Event, List<Event>>>> trivialImplications) {

    public BooleanFormula encode(EncodingContext context) {
        final BooleanFormulaManager bmgr = context.getBooleanFormulaManager();
        final List<BooleanFormula> enc = new ArrayList<>();
        for (Map.Entry<Relation, Map<Relation, Map<Event, List<Event>>>> implicationsForConstraint : trivialImplications.entrySet()) {
            final Relation eazyRel = implicationsForConstraint.getKey();
            for (Map.Entry<Relation, Map<Event, List<Event>>> implicationsForConstraintAndRel : implicationsForConstraint.getValue().entrySet()) {
                final Relation rel = implicationsForConstraintAndRel.getKey();
                for (Map.Entry<Event, List<Event>> reasonsForEvent : implicationsForConstraintAndRel.getValue().entrySet()) {
                    final Event first = reasonsForEvent.getKey();
                    for (Event second : reasonsForEvent.getValue()) {
                        enc.add(bmgr.implication(context.edge(rel, first, second), context.edge(eazyRel, first, second)));
                    }
                }
            }
        }
        return bmgr.and(enc);
    }

    public boolean isEazy(Constraint constraint) {
        if (constraint instanceof Axiom axiom) {
            return trivialImplications.containsKey(axiom.getRelation());
        } else if (constraint instanceof Definition def) {
            return trivialImplications.containsKey(def.getDefinedRelation());
        } else {
            throw new UnsupportedOperationException("Unsupported constraint type: " + constraint.getClass().getSimpleName());
        }
    }

    public boolean isTrivial(Relation reasonRel, Relation impliedRel, Event event) {
        final Map<Event, List<Event>> trivialImplicationsForDefAndRel =
                getTrivialImplicationForDefAndRel(impliedRel, reasonRel);
        if (trivialImplicationsForDefAndRel == null) {
            return false;
        }
        return trivialImplicationsForDefAndRel.containsKey(event);
    }

    public boolean isTrivial(Relation reasonRel, Relation impliedRel, Event first, Event second) {
        final Map<Event, List<Event>> trivialImplicationsForDefAndRel =
                getTrivialImplicationForDefAndRel(impliedRel, reasonRel);
        if (trivialImplicationsForDefAndRel == null) {
            return false;
        }
        final List<Event> trivialImplicationsWithEvent = trivialImplicationsForDefAndRel.get(first);
        if (trivialImplicationsWithEvent == null) {
            return false;
        }
        return trivialImplicationsWithEvent.contains(second);
    }

    private Map<Event, List<Event>> getTrivialImplicationForDefAndRel(Relation constraint, Relation rel) {
        final Map<Relation, Map<Event, List<Event>>> trivialImplicationsForDef = trivialImplications.get(constraint);
        if (trivialImplicationsForDef == null) {
            return null;
        }
        return trivialImplicationsForDef.get(rel);
    }
}

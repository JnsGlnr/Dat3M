package com.dat3m.dartagnan.wmm.processing;

import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.axiom.Axiom;
import com.dat3m.dartagnan.wmm.axiom.Emptiness;
import com.dat3m.dartagnan.wmm.axiom.Irreflexivity;
import com.dat3m.dartagnan.wmm.definition.Composition;
import com.dat3m.dartagnan.wmm.definition.Intersection;
import com.dat3m.dartagnan.wmm.definition.Inverse;
import com.dat3m.dartagnan.wmm.definition.Union;

import java.util.*;

import static java.util.Collections.singleton;

public class EmptinessToIrreflexivity implements WmmProcessor {

    private EmptinessToIrreflexivity() {
    }

    public static EmptinessToIrreflexivity newInstance() {
        return new EmptinessToIrreflexivity();
    }

    @Override
    public void run(final Wmm wmm) {
        for (final Axiom axiom : wmm.getAxioms()) {
            if (axiom instanceof final Emptiness emptiness) {
                final Collection<Axiom> irreflexivities = emptinessToIrreflexivities(wmm, emptiness);
                if (irreflexivities != null) {
                    wmm.removeConstraint(emptiness);
                    irreflexivities.forEach(wmm::addConstraint);
                }
            }
        }
    }

    private Collection<Axiom> emptinessToIrreflexivities(final Wmm wmm, final Emptiness emptiness) {
        final Definition definition = emptiness.getRelation().getDefinition();
        if (definition instanceof final Union union) {
            final Collection<Relation> operands = union.getOperands();
            final Collection<Axiom> replacement = operands.stream()
                    .map(op -> emptinessToIrreflexivity(wmm, op))
                    .toList();
            final Relation[] unchanged = replacement.stream()
                    .filter(Emptiness.class::isInstance)
                    .map(Axiom::getRelation)
                    .toArray(Relation[]::new);
            if (unchanged.length == operands.size()) {
                return null;
            }
            final Union simplifiedEmptiness = new Union(wmm.newRelation(), unchanged);
            wmm.addDefinition(simplifiedEmptiness);
            final Collection<Axiom> simplifiedReplacement = new ArrayList<>();
            simplifiedReplacement.add(new Emptiness(simplifiedEmptiness.getDefinedRelation()));
            simplifiedReplacement.addAll(replacement.stream()
                    .filter(Irreflexivity.class::isInstance)
                    .toList());
            return simplifiedReplacement;
        }
        final Axiom irreflexivity = emptinessToIrreflexivity(wmm, definition.getDefinedRelation());
        if (irreflexivity instanceof Irreflexivity) {
            return singleton(irreflexivity);
        }
        return null;
    }

    private Axiom emptinessToIrreflexivity(final Wmm wmm, final Relation emptiness) {
        if (emptiness.getDefinition() instanceof final Intersection intersection && intersection.getDefinedRelation().getArity() == Relation.Arity.BINARY) {
            final List<Relation> operands = intersection.getOperands();
            if (operands.size() == 2) {
                final Relation first = operands.get(0);
                final Relation second = operands.get(1);
                final Definition inverseFirst = new Inverse(wmm.newRelation(), first);
                wmm.addDefinition(inverseFirst);
                final Definition irreflexivity = new Composition(wmm.newRelation(), inverseFirst.getDefinedRelation(), second);
                wmm.addDefinition(irreflexivity);
                return new Irreflexivity(irreflexivity.getDefinedRelation());
            }
        }
        return new Emptiness(emptiness);
    }
}

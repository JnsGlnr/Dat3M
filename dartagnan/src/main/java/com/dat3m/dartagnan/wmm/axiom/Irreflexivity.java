package com.dat3m.dartagnan.wmm.axiom;

import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.definition.Composition;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Irreflexivity extends Axiom {

    public Irreflexivity(Relation rel, boolean negated, boolean flag) {
        super(Relation.checkIsRelation(rel), negated, flag);
    }

    public Irreflexivity(Relation rel) {
        this(rel, false, false);
    }

    @Override
    protected String getAxiomName() { return "irreflexive"; }

    @Override
    public <T> T accept(Visitor<? extends T> visitor) {
        return visitor.visitIrreflexivity(this);
    }

    public List<Definition> getComponents() {
        final Definition definition = rel.getDefinition();
        if (definition instanceof final Composition composition) {
            return composition.getComponents();
        }
        return Collections.singletonList(definition);
    }

    public List<Composition> collectCompositions() {
        final Definition definition = rel.getDefinition();
        if (definition instanceof final Composition composition) {
            return composition.collectCompositions();
        }
        return Collections.emptyList();
    }

    @Override
    public int hashCode() {
        return Objects.hash(rel, negated, flag);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof final Irreflexivity otherIrreflexivity)) return false;
        return rel.equals(otherIrreflexivity.rel) && negated == otherIrreflexivity.negated && flag == otherIrreflexivity.flag;
    }
}
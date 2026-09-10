package com.dat3m.dartagnan.wmm.definition;

import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;

import java.util.*;

public class Composition extends Definition {

    private final Relation left;
    private final Relation right;

    public Composition(Relation r0, Relation r1, Relation r2) {
        super(Relation.checkIsRelation(r0), "%s ; %s");
        left = Relation.checkIsRelation(r1);
        right = Relation.checkIsRelation(r2);
    }

    public Relation getLeftOperand() { return left; }
    public Relation getRightOperand() { return right; }

    @Override
    public List<Relation> getConstrainedRelations() {
        return List.of(definedRelation, left, right);
    }

    public List<Definition> getComponents() {
        final List<Definition> components = new ArrayList<>();
        final Deque<Definition> workList = new ArrayDeque<>();
        workList.push(this);
        while (!workList.isEmpty()) {
            final Definition definition = workList.pop();
            if (!(definition instanceof final Composition composition)) {
                components.add(definition);
            } else {
                workList.push(composition.getRightOperand().getDefinition());
                workList.push(composition.getLeftOperand().getDefinition());
            }
        }
        return components;
    }

    public List<Composition> collectCompositions() {
        final List<Composition> compositions = new ArrayList<>();
        final Deque<Composition> workList = new ArrayDeque<>();
        workList.push(this);
        while (!workList.isEmpty()) {
            final Composition composition = workList.pop();
            compositions.add(composition);
            final Definition left = composition.getLeftOperand().getDefinition();
            if (left instanceof final Composition leftComposition) {
                workList.push(leftComposition);
            }
            final Definition right = composition.getRightOperand().getDefinition();
            if (right instanceof final Composition rightComposition) {
                workList.push(rightComposition);
            }
        }
        return compositions;
    }

    @Override
    public <T> T accept(Visitor<? extends T> v) {
        return v.visitComposition(this);
    }
}
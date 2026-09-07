package com.dat3m.dartagnan.wmm.definition;

import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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

    @Override
    public <T> T accept(Visitor<? extends T> v) {
        return v.visitComposition(this);
    }
}
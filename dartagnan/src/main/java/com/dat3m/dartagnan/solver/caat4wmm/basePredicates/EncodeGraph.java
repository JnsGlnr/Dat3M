package com.dat3m.dartagnan.solver.caat4wmm.basePredicates;

import com.dat3m.dartagnan.encoding.ActiveSetAnalysis;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.solver.caat.predicates.misc.PredicateVisitor;
import com.dat3m.dartagnan.solver.caat.predicates.relationGraphs.Edge;
import com.dat3m.dartagnan.wmm.Constraint;
import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.axiom.Axiom;
import com.dat3m.dartagnan.wmm.utils.graph.EventGraph;

import java.util.Map;
import java.util.Set;

public class EncodeGraph extends MaterializedWMMGraph {

    private final Constraint constraint;

    public EncodeGraph(Constraint constraint) {
        this.constraint = constraint;
        setName("encoded");
    }

    @Override
    public void repopulate() {
        final ActiveSetAnalysis asa = model.getContext().getAnalysisContext().requires(ActiveSetAnalysis.class);
        final EventGraph encodeSet;
        if (constraint instanceof Axiom axiom) {
            encodeSet = asa.getRelevantSet(axiom);
        } else if (constraint instanceof Definition definition) {
            encodeSet = asa.getActiveSet(definition);
        } else {
            throw new UnsupportedOperationException("Constraint of type " + constraint.getClass().getSimpleName() + " is not supported in EncodeGraph");
        }
        for (Map.Entry<Event, Set<Event>> outSet : encodeSet.getOutMap().entrySet()) {
            model.getData(outSet.getKey()).ifPresent(e1 -> {
                final int id1 = e1.getId();
                for (Event event : outSet.getValue()) {
                    model.getData(event).ifPresent(e2 -> simpleGraph.add(new Edge(id1, e2.getId())));
                }
            });
        }
    }

    @Override
    public <TRet, TData, TContext> TRet accept(PredicateVisitor<TRet, TData, TContext> visitor, TData tData, TContext context) {
        return visitor.visitTrivialGraph(this, tData, context);
    }
}

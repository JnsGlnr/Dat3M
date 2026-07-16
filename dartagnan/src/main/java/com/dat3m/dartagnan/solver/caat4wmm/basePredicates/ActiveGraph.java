package com.dat3m.dartagnan.solver.caat4wmm.basePredicates;

import com.dat3m.dartagnan.encoding.ActiveSetAnalysis;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.solver.caat.predicates.misc.PredicateVisitor;
import com.dat3m.dartagnan.solver.caat.predicates.relationGraphs.Edge;
import com.dat3m.dartagnan.wmm.axiom.Axiom;

import java.util.Map;
import java.util.Set;

public class ActiveGraph extends MaterializedWMMGraph {

    private final Axiom axiom;

    public ActiveGraph(Axiom axiom) {
        this.axiom = axiom;
        setName("active");
    }

    @Override
    public void repopulate() {
        final ActiveSetAnalysis asa = model.getContext().getAnalysisContext().requires(ActiveSetAnalysis.class);
        for (Map.Entry<Event, Set<Event>> outSet : asa.getRelevantSet(axiom).getOutMap().entrySet()) {
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

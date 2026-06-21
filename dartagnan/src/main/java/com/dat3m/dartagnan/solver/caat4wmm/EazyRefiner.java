package com.dat3m.dartagnan.solver.caat4wmm;

import com.dat3m.dartagnan.encoding.EncodingContext;
import com.dat3m.dartagnan.solver.caat4wmm.coreReasoning.*;
import com.dat3m.dartagnan.utils.logic.Conjunction;
import com.dat3m.dartagnan.utils.logic.DNF;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;

import java.util.ArrayList;
import java.util.List;

/*
    This class handles the computation of refinement clauses from violations found by the Eazy-WMM-solver procedure.
 */
public class EazyRefiner {

    private final Refiner refiner = Refiner.newInstance();

    private EazyRefiner() {}

    public static EazyRefiner newInstance() {
        return new EazyRefiner();
    }

    public BooleanFormula refine(DNF<CoreLiteral> coreReasons, Conjunction<CoreImplication> coreImplications, EncodingContext context) {
        final BooleanFormulaManager bmgr = context.getBooleanFormulaManager();
        List<BooleanFormula> refinement = new ArrayList<>();
        BooleanFormula clause = bmgr.makeTrue();
        for (CoreImplication lit : coreImplications.getLiterals()) {
            final BooleanFormula litFormula = encode(lit, context);
            if (bmgr.isFalse(litFormula)) {
                clause = bmgr.makeTrue();
                break;
            } else {
                clause = bmgr.and(clause, litFormula);
            }
        }
        if (!bmgr.isTrue(clause)) {
            refinement.add(clause);
        }
        BooleanFormula coreImplicationFormula = bmgr.and(refinement);
        return bmgr.and(refiner.refine(coreReasons, context), coreImplicationFormula);
    }

    private BooleanFormula encode(CoreImplication literal, EncodingContext encoder) {
        final BooleanFormulaManager bmgr = encoder.getBooleanFormulaManager();
        final BooleanFormula reasonEnc = bmgr.and(literal.getReason().getLiterals().stream()
                .map(lit -> refiner.encode(lit, encoder))
                .toList());
        final CoreLiteral impliedLiteral = literal.getImpliedLiteral();
        final BooleanFormula encodedLiteral = refiner.encode(impliedLiteral, encoder);
        final BooleanFormula enc = bmgr.implication(reasonEnc, impliedLiteral.isNegative() ? bmgr.not(encodedLiteral) : encodedLiteral);

        return literal.isNegative() ? bmgr.not(enc) : enc;
    }

}

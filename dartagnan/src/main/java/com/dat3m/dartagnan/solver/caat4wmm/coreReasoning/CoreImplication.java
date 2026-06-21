package com.dat3m.dartagnan.solver.caat4wmm.coreReasoning;

import com.dat3m.dartagnan.utils.logic.Conjunction;
import com.dat3m.dartagnan.utils.logic.Literal;

import java.util.Objects;

public class CoreImplication implements Literal<CoreImplication> {

    private final Conjunction<CoreLiteral> reason;
    private final CoreLiteral implied;

    public CoreImplication(Conjunction<CoreLiteral> reason, CoreLiteral implied) {
        this.reason = reason;
        this.implied = implied;
    }

    @Override
    public String getName() {
        return "impl";
    }

    @Override
    public boolean isPositive() {
        return implied.isPositive();
    }

    @Override
    public CoreImplication negated() {
        return new CoreImplication(reason, implied.negated());
    }

    public CoreLiteral getImpliedLiteral() {
        return implied;
    }

    public Conjunction<CoreLiteral> getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return (isNegative() ? "!(" : "") + "(" + reason + ") => " + implied + (isNegative() ? ")" : "");
    }

    @Override
    public int hashCode() {
        return Objects.hash(implied, reason);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof CoreImplication other
                && this.implied.equals(other.implied)
                && this.reason.equals(other.reason));
    }
}

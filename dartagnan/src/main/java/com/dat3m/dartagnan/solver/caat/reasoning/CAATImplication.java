package com.dat3m.dartagnan.solver.caat.reasoning;

import com.dat3m.dartagnan.utils.logic.Conjunction;
import com.dat3m.dartagnan.utils.logic.Literal;

import java.util.Objects;

public class CAATImplication implements Literal<CAATImplication> {

    private final Conjunction<CAATLiteral> reason;
    private final CAATLiteral implied;

    public CAATImplication(Conjunction<CAATLiteral> reason, CAATLiteral implied) {
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
    public CAATImplication negated() {
        return new CAATImplication(reason, implied.negated());
    }

    public CAATLiteral getImpliedLiteral() {
        return implied;
    }

    public Conjunction<CAATLiteral> getReason() {
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
        return (obj instanceof CAATImplication other
                && this.implied.equals(other.implied)
                && this.reason.equals(other.reason));
    }
}

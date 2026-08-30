package com.dat3m.dartagnan.configuration;

import java.util.Arrays;

public enum Method implements OptionInterface {
    EAGER, EAZY, LAZY, PROPAGATOR, EAZY_PROPAGATOR;

    // Used for options in the console
    @Override
    public String asStringOption() {
        return switch (this) {
            case PROPAGATOR -> "propagator";
            case EAGER -> "eager";
            case EAZY -> "eazy";
            case EAZY_PROPAGATOR -> "eazy_propagator";
            case LAZY -> "lazy";
        };
    }

    // Used to display in UI
    @Override
    public String toString() {
        return switch (this) {
            case PROPAGATOR -> "Propagator analysis";
            case EAGER -> "Eager analysis";
            case EAZY -> "Hybrid eager-lazy analysis";
            case EAZY_PROPAGATOR -> "Hybrid eager-lazy propagator analysis";
            case LAZY -> "Lazy analysis";
        };
    }

    public static Method getDefault() {
        return EAGER;
    }

    // Used to decide the order shown by the selector in the UI
    public static Method[] orderedValues() {
        Method[] order = { LAZY, EAZY, EAGER, EAZY_PROPAGATOR };
        // Be sure no element is missing
        assert (Arrays.asList(order).containsAll(Arrays.asList(values())));
        return order;
    }
}
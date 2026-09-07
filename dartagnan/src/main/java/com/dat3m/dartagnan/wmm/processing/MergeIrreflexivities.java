package com.dat3m.dartagnan.wmm.processing;

import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.axiom.Irreflexivity;
import com.dat3m.dartagnan.wmm.definition.*;

import java.util.*;

import static java.util.function.UnaryOperator.identity;
import static java.util.stream.Collectors.toMap;

public class MergeIrreflexivities implements WmmProcessor {

    private MergeIrreflexivities() {
    }

    public static MergeIrreflexivities newInstance() {
        return new MergeIrreflexivities();
    }

    private record SimplificationData(int index, int size) {
    }

    @Override
    public void run(final Wmm wmm) {
        final Collection<Irreflexivity> axiomsToRemove = new ArrayList<>();
        do {
            axiomsToRemove.clear();
            final Map<Irreflexivity, List<Definition>> irreflexivities = wmm.getAxioms().stream()
                    .filter(Irreflexivity.class::isInstance)
                    .map(Irreflexivity.class::cast)
                    .collect(toMap(identity(), Irreflexivity::getComponents));
            for (final Map.Entry<Irreflexivity, List<Definition>> axiomWithComponents : irreflexivities.entrySet()) {
                final List<Definition> components = axiomWithComponents.getValue();
                final List<SimplificationData> simplifications = new ArrayList<>();
                Definition compToSimplify = null;
                int simplificationIndex = -1;
                int simplificationSize = 0;
                int i = 0;
                for (final Definition component : components) {
                    if (isTransitive(component) || isSubsetOfIdentity(component)) {
                        if (simplificationIndex == -1) {
                            compToSimplify = component;
                            simplificationIndex = i;
                            simplificationSize = 0;
                        } else if (!(component == compToSimplify || isSubsetOfIdentity(component))) {
                            if (simplificationIndex != i - 1) {
                                simplifications.add(new SimplificationData(simplificationIndex, simplificationSize));
                            }
                            compToSimplify = component;
                            simplificationIndex = i;
                            simplificationSize = 0;
                        }
                        ++simplificationSize;
                    } else if (simplificationIndex != -1) {
                        simplifications.add(new SimplificationData(simplificationIndex, simplificationSize));
                        compToSimplify = null;
                        simplificationIndex = -1;
                        simplificationSize = 0;
                    }
                    ++i;
                }
                if (simplificationIndex != -1) {
                    simplifications.add(new SimplificationData(simplificationIndex, simplificationSize));
                }
                for (final SimplificationData simplification : simplifications) {
                    if (simplification.size != 1) {
                        COMP_LOOP:
                        for (final List<Definition> otherComponents : irreflexivities.values()) {
                            if (components.size() - simplification.size + 1 == otherComponents.size()) {
                                for (i = 0; i < otherComponents.size(); ++i) {
                                    if (otherComponents.get(i) != components.get(i > simplification.index ? i + simplification.size - 1 : i)) {
                                        continue COMP_LOOP;
                                    }
                                }
                                axiomsToRemove.add(axiomWithComponents.getKey());
                                break;
                            }
                        }
                    }
                }
            }
            for (final Irreflexivity axiom : axiomsToRemove) {
                wmm.removeConstraint(axiom);
            }
        } while (!axiomsToRemove.isEmpty());
    }

    private boolean isSubsetOfIdentity(final Definition definition) {
        return definition instanceof Empty || definition instanceof SetIdentity;
    }

    private boolean isTransitive(final Definition definition) {
        return definition instanceof TransitiveClosure || definition instanceof Coherence
                || definition instanceof ProgramOrder || definition instanceof AMOPairs || definition instanceof Empty
                || definition instanceof Internal || definition instanceof LXSXPairs
                || definition instanceof SameInstruction || definition instanceof SameLocation
                || definition instanceof SameScope || definition instanceof SameVirtualLocation
                || definition instanceof ReadFrom;   // If writes and reads are disjoint
    }
}

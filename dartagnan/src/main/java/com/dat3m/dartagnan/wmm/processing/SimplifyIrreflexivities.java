package com.dat3m.dartagnan.wmm.processing;

import com.dat3m.dartagnan.program.event.Tag;
import com.dat3m.dartagnan.verification.solving.RefinementSolver;
import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.axiom.Acyclicity;
import com.dat3m.dartagnan.wmm.axiom.Axiom;
import com.dat3m.dartagnan.wmm.axiom.Irreflexivity;
import com.dat3m.dartagnan.wmm.definition.*;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;

import java.util.*;
import java.util.stream.Collectors;

import static com.dat3m.dartagnan.configuration.OptionNames.SIMPLIFY_ENCODED_IRREFLEXIVITY_RELATIONS;
import static java.util.Collections.*;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toCollection;

@Options
public class SimplifyIrreflexivities implements WmmProcessor {

    @Option(name=SIMPLIFY_ENCODED_IRREFLEXIVITY_RELATIONS,
            description="Simplify encoded irreflexivity relations",
            secure=true,
            toUppercase=true)
    private boolean ignoreEncoding = true;

    private SimplifyIrreflexivities() {
    }

    public static SimplifyIrreflexivities fromConfig(final Configuration config) throws InvalidConfigurationException {
        final SimplifyIrreflexivities simplifier = new SimplifyIrreflexivities();
        config.inject(simplifier);
        return simplifier;
    }

    @Override
    public void run(final Wmm wmm) {
        final Collection<Relation> encodedRelations = ignoreEncoding ? emptySet() : getEncodedRelations(wmm);
        final List<Axiom> newAxioms = new ArrayList<>();
        final Deque<Irreflexivity> workList = wmm.getAxioms().stream()
                .filter(Irreflexivity.class::isInstance)
                .map(Irreflexivity.class::cast)
                .collect(toCollection(ArrayDeque::new));
        while (!workList.isEmpty()) {
            final Irreflexivity irreflexivity = workList.pop();
            final Collection<Axiom> replacement = simplifyIrreflexivity(wmm, irreflexivity, encodedRelations);
            if (!(replacement.size() == 1 && replacement.iterator().next() == irreflexivity)) {
                wmm.removeConstraint(irreflexivity);
                newAxioms.remove(irreflexivity);
                newAxioms.addAll(replacement);
                replacement.stream()
                        .filter(Irreflexivity.class::isInstance)
                        .map(Irreflexivity.class::cast)
                        .forEach(workList::push);
            }
        }
        newAxioms.forEach(wmm::addConstraint);
    }

    private Collection<Axiom> simplifyIrreflexivity(final Wmm wmm, final Irreflexivity irreflexivity, final Collection<Relation> encodedRelations) {
        final Definition definition = irreflexivity.getRelation().getDefinition();
        if (definition instanceof final TransitiveClosure transitive) {
            return singleton(new Acyclicity(transitive.getOperand()));
        }
        if (definition instanceof final Union union) {
            final List<Relation> operands = union.getOperands();
            final Collection<Axiom> replacement = new ArrayList<>(operands.size());
            for (final Relation relation : operands) {
                replacement.add(new Irreflexivity(relation));
            }
            return replacement;
        }
        if (definition instanceof final Composition composition) {
            final Deque<List<Composition>> workList = new ArrayDeque<>();
            workList.push(singletonList(composition));
            while (!workList.isEmpty()) {
                final List<Composition> compTrace = workList.pop();
                final Composition comp = compTrace.get(compTrace.size() - 1);
                final Relation firstRel = comp.getLeftOperand();
                final Relation secondRel = comp.getRightOperand();
                final Definition first = firstRel.getDefinition();
                final Definition second = secondRel.getDefinition();

                if (isIdentity(firstRel)) {
                    final Definition simplified = replaceInComposition(wmm, second, compTrace);
                    return singleton(new Irreflexivity(simplified.getDefinedRelation()));
                }
                if (isIdentity(secondRel)) {
                    final Definition simplified = replaceInComposition(wmm, first, compTrace);
                    return singleton(new Irreflexivity(simplified.getDefinedRelation()));
                }

                if (first instanceof final Intersection intersection && intersection.getDefinedRelation().isRelation()
                        && checkEncoding(first.getDefinedRelation(), encodedRelations)) {
                    final Definition rewritten = rewriteIntersection(wmm, intersection);
                    if (rewritten != null) {
                        final Composition newInnerComp = new Composition(wmm.newRelation(), rewritten.getDefinedRelation(), secondRel);
                        wmm.addDefinition(newInnerComp);
                        final Definition newComp = replaceInComposition(wmm, newInnerComp, compTrace);
                        return singleton(new Irreflexivity(newComp.getDefinedRelation()));
                    }
                }
                if (second instanceof final Intersection intersection && intersection.getDefinedRelation().isRelation()
                        && checkEncoding(second.getDefinedRelation(), encodedRelations)) {
                    final Definition rewritten = rewriteIntersection(wmm, intersection);
                    if (rewritten != null) {
                        final Composition newInnerComp = new Composition(wmm.newRelation(), firstRel, rewritten.getDefinedRelation());
                        wmm.addDefinition(newInnerComp);
                        final Definition newComp = replaceInComposition(wmm, newInnerComp, compTrace);
                        return singleton(new Irreflexivity(newComp.getDefinedRelation()));
                    }
                }

                if (first instanceof final Union union && checkEncoding(firstRel, encodedRelations)) {
                    final List<Relation> operands = union.getOperands();
                    final Collection<Axiom> replacement = new ArrayList<>(operands.size());
                    for (final Relation operand : union.getOperands()) {
                        final Composition newInnerComp = new Composition(wmm.newRelation(), operand, secondRel);
                        wmm.addDefinition(newInnerComp);
                        final Definition newComp = replaceInComposition(wmm, newInnerComp, compTrace);
                        replacement.add(new Irreflexivity(newComp.getDefinedRelation()));
                    }
                    return replacement;
                }
                if (second instanceof final Union union && checkEncoding(secondRel, encodedRelations)) {
                    final List<Relation> operands = union.getOperands();
                    final Collection<Axiom> replacement = new ArrayList<>(operands.size());
                    for (final Relation operand : union.getOperands()) {
                        final Composition newInnerComp = new Composition(wmm.newRelation(), firstRel, operand);
                        wmm.addDefinition(newInnerComp);
                        final Definition newComp = replaceInComposition(wmm, newInnerComp, compTrace);
                        replacement.add(new Irreflexivity(newComp.getDefinedRelation()));
                    }
                    return replacement;
                }
                if (first instanceof final Composition comp1) {
                    final List<Composition> newCompTrace = new ArrayList<>(compTrace);
                    newCompTrace.add(comp1);
                    workList.push(newCompTrace);
                }
                if (second instanceof final Composition comp2) {
                    final List<Composition> newCompTrace = new ArrayList<>(compTrace);
                    newCompTrace.add(comp2);
                    workList.push(newCompTrace);
                }
            }

            final List<Definition> components = composition.getComponents();
            final List<Definition> componentsToShift = new ArrayList<>(components.size());
            Definition setComponent = components.get(0);
            int i = 0;
            while (setComponent instanceof SetIdentity && ++i < components.size()) {
                componentsToShift.add(setComponent);
                setComponent = components.get(i);
            }
            boolean hasChanges = !componentsToShift.isEmpty();
            components.subList(0, componentsToShift.size()).clear();
            components.addAll(componentsToShift);

            final List<Relation> newComponents = new ArrayList<>(components.size());
            final List<SetIdentity> curSets = new ArrayList<>(components.size());
            for (i = 0; i < components.size(); ++i) {
                final Definition component = components.get(i);
                if (component instanceof final SetIdentity setId) {
                    curSets.add(setId);
                } else {
                    switch (curSets.size()) {
                        case 0:
                            break;
                        case 1:
                            final SetIdentity setIdentity = curSets.get(0);
                            final Definition prevRelDef = components.get(i - 2);
                            final Collection<String> simpleOptionalTags = new ArrayList<>();
                            simpleOptionalTags.addAll(getRangeTags(prevRelDef));
                            simpleOptionalTags.addAll(getDomainTags(component));
                            final Relation intersection = intersectSets(wmm, singleton(setIdentity), simpleOptionalTags);
                            if (intersection == null || !(intersection.getDefinition() instanceof final SetIdentity setId
                                    && setId.getDomain().getDefinition() instanceof final TagSet tagSet && tagSet.getTag().equals(Tag.VISIBLE))) {
                                newComponents.add(intersection);
                                if (intersection != null && intersection.getDefinition() instanceof final SetIdentity intersectionDef) {
                                    if (setIdentity.getDomain() != intersectionDef.getDomain()) {
                                        hasChanges = true;
                                    }
                                } else {
                                    hasChanges = true;
                                }
                            } else {
                                hasChanges = true;
                            }
                            break;
                        default:
                            final Definition startDef = components.get(i - curSets.size() - 1);
                            final Collection<String> optionalTags = new ArrayList<>();
                            optionalTags.addAll(getRangeTags(startDef));
                            optionalTags.addAll(getDomainTags(component));
                            newComponents.add(intersectSets(wmm, curSets, optionalTags));
                            hasChanges = true;
                    }
                    newComponents.add(component.getDefinedRelation());
                    curSets.clear();
                }
            }
            if (!curSets.isEmpty()) {
                final Definition startDef = components.get(components.size() - curSets.size() - 1);
                final Collection<String> optionalTags = new ArrayList<>();
                optionalTags.addAll(getRangeTags(startDef));
                optionalTags.addAll(getDomainTags(components.get(0)));
                if (curSets.size() == 1) {
                    final SetIdentity setIdentity = curSets.get(0);
                    final Relation intersection = intersectSets(wmm, singleton(setIdentity), optionalTags);
                    if (intersection == null || !(intersection.getDefinition() instanceof final SetIdentity setId
                            && setId.getDomain().getDefinition() instanceof final TagSet tagSet && tagSet.getTag().equals(Tag.VISIBLE))) {
                        newComponents.add(intersection);
                    }
                    if (intersection != null && intersection.getDefinition() instanceof final SetIdentity intersectionDef) {
                        hasChanges = setIdentity.getDomain() != intersectionDef.getDomain();
                    } else {
                        hasChanges = true;
                    }
                } else {
                    newComponents.add(intersectSets(wmm, curSets, optionalTags));
                    hasChanges = true;
                }
            }
            if (hasChanges) {
                if (newComponents.contains(null)) {
                    return emptySet();
                }
                return singleton(new Irreflexivity(compose(wmm, newComponents)));
            }
        }
        return singleton(irreflexivity);
    }

    private boolean checkEncoding(final Relation relation, final Collection<Relation> encodedRelations) {
        return ignoreEncoding || !encodedRelations.contains(relation);
    }

    private Definition replaceInComposition(final Wmm wmm, Definition replacement, final List<Composition> compTrace) {
        Composition prevComp = compTrace.get(compTrace.size() - 1);
        for (int i = compTrace.size() - 2; i >= 0; --i) {
            final Composition supComp = compTrace.get(i);
            final Definition supFirst = supComp.getLeftOperand().getDefinition();
            final Definition supSecond = supComp.getRightOperand().getDefinition();
            final Composition replacedComp;
            if (supFirst == prevComp) {
                replacedComp = new Composition(wmm.newRelation(), replacement.getDefinedRelation(), supSecond.getDefinedRelation());
            } else {
                replacedComp = new Composition(wmm.newRelation(), supFirst.getDefinedRelation(), replacement.getDefinedRelation());
            }
            wmm.addDefinition(replacedComp);
            replacement = replacedComp;
            prevComp = supComp;
        }
        return replacement;
    }

    private Definition rewriteIntersection(final Wmm wmm, final Intersection intersection) {
        final List<CartesianProduct> cartesianOperands = new ArrayList<>();
        final List<Relation> otherOperands = new ArrayList<>();
        for (final Relation operand : intersection.getOperands()) {
            if (operand.getDefinition() instanceof final CartesianProduct cartesianProduct) {
                cartesianOperands.add(cartesianProduct);
            } else {
                otherOperands.add(operand);
            }
        }
        if (cartesianOperands.isEmpty()) {
            return null;
        }
        final Relation[] leftConditions = cartesianOperands.stream()
                .map(CartesianProduct::getDomain)
                .toArray(Relation[]::new);
        final Relation[] rightConditions = cartesianOperands.stream()
                .map(CartesianProduct::getRange)
                .toArray(Relation[]::new);
        final Relation left;
        final Relation right;
        if (cartesianOperands.size() == 1) {
            left = leftConditions[0];
            right = rightConditions[0];
        } else {
            final Definition leftDef = new Intersection(wmm.newSet(), leftConditions);
            wmm.addDefinition(leftDef);
            final Definition rightDef = new Intersection(wmm.newSet(), rightConditions);
            wmm.addDefinition(rightDef);
            left = leftDef.getDefinedRelation();
            right = rightDef.getDefinedRelation();
        }
        final Definition leftIdDef = new SetIdentity(wmm.newRelation(), left);
        wmm.addDefinition(leftIdDef);
        final Definition rightIdDef = new SetIdentity(wmm.newRelation(), right);
        wmm.addDefinition(rightIdDef);
        final Relation otherRel;
        if (otherOperands.size() == 1) {
            otherRel = otherOperands.get(0);
        } else {
            final Definition otherDef = new Intersection(wmm.newRelation(), otherOperands.toArray(new Relation[0]));
            wmm.addDefinition(otherDef);
            otherRel = otherDef.getDefinedRelation();
        }
        final Definition rewrittenLeft = new Composition(wmm.newRelation(), leftIdDef.getDefinedRelation(), otherRel);
        wmm.addDefinition(rewrittenLeft);
        final Definition rewritten = new Composition(wmm.newRelation(), rewrittenLeft.getDefinedRelation(), rightIdDef.getDefinedRelation());
        wmm.addDefinition(rewritten);
        return rewritten;
    }

    private Collection<String> getDomainTags(final Definition definition) {
        if (definition instanceof final Inverse inverse) {
            return getRangeTags(inverse.getOperand().getDefinition());
        }
        if (definition instanceof final Difference difference) {
            return getDomainTags(difference.getMinuend().getDefinition());
        }
        if (definition instanceof Composition composition) {
            return getDomainTags(composition.getComponents().get(0));
        }
        if (definition instanceof final Intersection intersection) {
            final Collection<String> tags = new ArrayList<>();
            for (final Relation operand : intersection.getOperands()) {
                tags.addAll(getDomainTags(operand.getDefinition()));
            }
            return tags;
        }
        if (definition instanceof final Union union) {
            final List<Relation> operands = union.getOperands();
            final Collection<String> tags = new ArrayList<>(getDomainTags(operands.get(0).getDefinition()));
            for (final Relation operand : operands.subList(1, operands.size())) {
                final Collection<String> opTags = getDomainTags(operand.getDefinition());
                tags.removeIf(not(opTags::contains));
            }
            return tags;
        }
        if (definition instanceof final SetIdentity setIdentity && setIdentity.getDomain().getDefinition() instanceof final TagSet tagSet) {
            return singleton(tagSet.getTag());
        }
        return getDomainTagsOfBase(definition);
    }

    private Collection<String> getRangeTags(final Definition definition) {
        if (definition instanceof final Inverse inverse) {
            return getDomainTags(inverse.getOperand().getDefinition());
        }
        if (definition instanceof final Difference difference) {
            return getRangeTags(difference.getMinuend().getDefinition());
        }
        if (definition instanceof Composition composition) {
            final List<Definition> components = composition.getComponents();
            return getRangeTags(components.get(components.size() - 1));
        }
        if (definition instanceof final Intersection intersection) {
            final Collection<String> tags = new ArrayList<>();
            for (final Relation operand : intersection.getOperands()) {
                tags.addAll(getRangeTags(operand.getDefinition()));
            }
            return tags;
        }
        if (definition instanceof final Union union) {
            final List<Relation> operands = union.getOperands();
            final Collection<String> tags = new ArrayList<>(getRangeTags(operands.get(0).getDefinition()));
            for (final Relation operand : operands.subList(1, operands.size())) {
                final Collection<String> opTags = getRangeTags(operand.getDefinition());
                tags.removeIf(not(opTags::contains));
            }
            return tags;
        }
        if (definition instanceof final SetIdentity setIdentity && setIdentity.getDomain().getDefinition() instanceof final TagSet tagSet) {
            return singleton(tagSet.getTag());
        }
        return getRangeTagsOfBase(definition);
    }

    private Collection<String> getDomainTagsOfBase(final Definition baseRel) {
        if (baseRel instanceof AMOPairs || baseRel instanceof LXSXPairs) {
            return List.of(Tag.READ, Tag.RMW);
        }
        if (baseRel instanceof DirectAddressDependency || baseRel instanceof SameLocation) {
            return List.of(Tag.MEMORY);
        }
        if (baseRel instanceof ReadFrom || baseRel instanceof Coherence) {
            return List.of(Tag.WRITE);
        }
        return emptyList();
    }

    private Collection<String> getRangeTagsOfBase(final Definition baseRel) {
        if (baseRel instanceof AMOPairs || baseRel instanceof LXSXPairs) {
            return List.of(Tag.WRITE, Tag.RMW);
        }
        if (baseRel instanceof DirectAddressDependency || baseRel instanceof SameLocation) {
            return List.of(Tag.MEMORY);
        }
        if (baseRel instanceof ReadFrom) {
            return List.of(Tag.READ);
        }
        if (baseRel instanceof Coherence) {
            return List.of(Tag.WRITE);
        }
        return emptyList();
    }

    private Relation intersectSets(final Wmm wmm, final Collection<SetIdentity> setIds, final Collection<String> optionalTags) {
        final Collection<Definition> sets = new ArrayList<>(setIds.stream()
                .map(SetIdentity::getDomain)
                .map(Relation::getDefinition)
                .toList());
        sets.addAll(optionalTags.stream()
                .filter(Objects::nonNull)
                .map(tag -> new TagSet(wmm.newSet(), tag))
                .map(wmm::addDefinition)
                .map(Relation::getDefinition)
                .toList());
        final Relation[] simplifiedSets = simplifyIntersection(sets, optionalTags);
        if (simplifiedSets == null) {
            return null;
        }
        return switch (simplifiedSets.length) {
            case 0 -> {
                final Definition intersection = new TagSet(wmm.newSet(), Tag.VISIBLE);
                wmm.addDefinition(intersection);
                final Definition newSetIdentity = new SetIdentity(wmm.newRelation(), intersection.getDefinedRelation());
                wmm.addDefinition(newSetIdentity);
                yield newSetIdentity.getDefinedRelation();
            }
            case 1 -> setIds.stream()
                    .filter(setId -> setId.getDomain() == simplifiedSets[0])
                    .findAny()
                    .orElseThrow()
                    .getDefinedRelation();
            default -> {
                final Definition intersection = new Intersection(wmm.newSet(), simplifiedSets);
                wmm.addDefinition(intersection);
                final Definition newSetIdentity = new SetIdentity(wmm.newRelation(), intersection.getDefinedRelation());
                wmm.addDefinition(newSetIdentity);
                yield newSetIdentity.getDefinedRelation();
            }
        };
    }

    private Relation[] simplifyIntersection(final Collection<Definition> sets, final Collection<String> optionalTags) {
        final Set<TagSet> tagSets = new HashSet<>();
        final Collection<Definition> otherSets = new ArrayList<>();
        for (final Definition set : sets) {
            if (set instanceof final TagSet tagSet) {
                tagSets.add(tagSet);
            } else {
                otherSets.add(set);
            }
        }
        if (isContradiction(tagSets)) {
            return null;
        }
        eliminateSupersets(tagSets);
        tagSets.removeIf(tagSet -> optionalTags.contains(tagSet.getTag()));
        otherSets.addAll(tagSets);
        return otherSets.stream().map(Definition::getDefinedRelation).toArray(Relation[]::new);
    }

    private boolean isContradiction(final Collection<TagSet> tagSets) {
        for (final TagSet tagSet : tagSets) {
            for (final TagSet otherTagSet : tagSets) {
                if (isDisjoint(tagSet.getTag(), otherTagSet.getTag())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void eliminateSupersets(final Set<TagSet> tagSets) {
        final Collection<TagSet> removedTagSets = new ArrayList<>();
        for (final TagSet tagSet : tagSets) {
            for (final TagSet otherTagSet : tagSets) {
                if (!removedTagSets.contains(tagSet) && !removedTagSets.contains(otherTagSet)
                        && isSuperset(otherTagSet.getTag(), tagSet.getTag())) {
                    removedTagSets.add(otherTagSet);
                }
            }
        }
        removedTagSets.forEach(tagSets::remove);
    }

    private Relation compose(final Wmm wmm, final List<Relation> components) {
        Relation comp = components.get(0);
        for (final Relation newComponent : components.subList(1, components.size())) {
            final Definition newComp = new Composition(wmm.newRelation(), comp, newComponent);
            wmm.addDefinition(newComp);
            comp = newComp.getDefinedRelation();
        }
        return comp;
    }

    private static Set<Relation> getEncodedRelations(final Wmm wmm) {
        return RefinementSolver.generateCut(wmm).stream()
                .map(c -> c.getConstrainedRelations().get(0))
                .collect(Collectors.toSet());
    }

    private static boolean isIdentity(final Relation rel) {
        return rel.getDefinition() instanceof final SetIdentity setId
                && setId.getDomain().getDefinition() instanceof TagSet tagSet && tagSet.getTag().equals(Tag.VISIBLE);
    }

    private static boolean isDisjoint(final String tag1, final String tag2) {
        final Map<String, Collection<String>> disjointTags = Map.of(
                Tag.READ, List.of(Tag.WRITE, Tag.FENCE, Tag.STRONG, Tag.INIT),
                Tag.WRITE, List.of(Tag.READ, Tag.FENCE),
                Tag.FENCE, List.of(Tag.READ, Tag.WRITE, Tag.INIT, Tag.STRONG, Tag.MEMORY, Tag.RMW),
                Tag.INIT, List.of(Tag.READ, Tag.FENCE, Tag.STRONG, Tag.RMW),
                Tag.MEMORY, List.of(Tag.FENCE),
                Tag.RMW, List.of(Tag.FENCE, Tag.INIT),
                Tag.STRONG, List.of(Tag.FENCE, Tag.INIT, Tag.READ)
        );
        return disjointTags.getOrDefault(tag1, emptyList()).contains(tag2);
    }

    private static boolean isSuperset(final String superTag, final String tag) {
        if (superTag.equals(Tag.VISIBLE)) {
            return true;
        }
        final Map<String, Collection<String>> supersetTags = Map.of(
                Tag.WRITE, List.of(Tag.INIT, Tag.STRONG),
                Tag.MEMORY, List.of(Tag.READ, Tag.WRITE, Tag.INIT, Tag.STRONG, Tag.RMW),
                Tag.RMW, List.of(Tag.STRONG)
        );
        return supersetTags.getOrDefault(superTag, emptyList()).contains(tag);
    }
}

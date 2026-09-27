/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import java.util.List;

/**
 * Chooses how to stage a project's {@code native-image} inputs, by asking each {@link InputPlanStrategy}
 * in turn whether it recognises the build output.
 *
 * <p>A factory rather than a chain of conditionals, because the set of frameworks is open: Quarkus, Spring
 * Boot AOT, Helidon and plain GraalVM are the current cases and none of them is the last. Adding one means
 * contributing a strategy, not editing this class.
 *
 * <p><b>Order is load-bearing.</b> Strategies are consulted most specific first and
 * {@link DerivedClasspathStrategy} is last, because it always applies. Getting the order wrong does not
 * fail loudly — a framework project that falls through to the derived strategy produces a binary that
 * builds successfully and then misbehaves at run time, because the generated reflection and feature
 * configuration is missing. That failure mode is the reason each framework strategy detects a concrete
 * artifact on disk rather than inferring from dependencies.
 *
 * <p>Verification status per strategy is documented on each; only Quarkus and derived have been run end to
 * end.
 */
public final class NativeImageInputPlanner {

    private final List<InputPlanStrategy> strategies;

    public NativeImageInputPlanner() {
        this(List.of(
                // Most specific first. Quarkus writes a directory of its own, so it is unambiguous.
                new QuarkusNativeSourcesStrategy(),
                // Then an explicitly configured argfile directory, which covers Spring Boot AOT, Helidon
                // and anything else built through native-maven-plugin.
                new ArgsFileDirectoryStrategy(),
                // Always applies, so it must be last.
                new DerivedClasspathStrategy()));
    }

    /** For tests, and for callers that want to restrict or extend the set. */
    public NativeImageInputPlanner(List<InputPlanStrategy> strategies) {
        if (strategies.isEmpty()) {
            throw new IllegalArgumentException("At least one strategy is required");
        }
        this.strategies = List.copyOf(strategies);
    }

    /** Plans staging for the given project, choosing the strategy automatically. */
    public NativeImageInputPlan plan(ProjectInputs inputs) throws InputPlanningException {
        for (InputPlanStrategy strategy : strategies) {
            if (strategy.appliesTo(inputs)) {
                return strategy.plan(inputs);
            }
        }
        // Unreachable with the default set, since the derived strategy always applies. Reported rather
        // than returning null so a custom strategy list that omits a fallback fails clearly.
        throw new InputPlanningException(
                "No input strategy recognised this project. Configured strategies: "
                        + strategies.stream().map(InputPlanStrategy::name).toList());
    }

    /** The strategy that would be chosen, for logging what the plugin decided and why. */
    public String selectedStrategyName(ProjectInputs inputs) {
        return strategies.stream()
                .filter(strategy -> strategy.appliesTo(inputs))
                .map(InputPlanStrategy::name)
                .findFirst()
                .orElse("none");
    }
}

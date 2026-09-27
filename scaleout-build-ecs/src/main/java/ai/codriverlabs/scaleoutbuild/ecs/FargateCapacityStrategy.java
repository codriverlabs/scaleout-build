/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import java.util.List;
import java.util.Locale;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;

/**
 * How Fargate capacity is chosen: Spot, on-demand, or a weighted mix.
 *
 * <p>Previously hardcoded in {@link EcsTaskLauncher} as "prefer Spot 4:1, flip to on-demand after repeated
 * interruptions". That is a reasonable default and a poor universal policy — Spot reclaims a task mid-build,
 * and a `native-image` compile is three to five minutes of work to lose. Whether that trade is worth roughly
 * a 70% discount depends on who is running the service, not on who requested the build.
 *
 * <p><b>Deliberately server-side configuration, not a client parameter.</b> Same reasoning that removed
 * {@code launchType} and {@code capacityProviderName} from the plugin during the control-plane migration:
 * capacity is a cost and reliability decision belonging to whoever operates the deployment. A client that
 * could demand on-demand capacity could also raise everyone else's bill. See
 * {@code docs/design/control-plane/migration-from-direct-ecs-access.md}.
 *
 * <p>Presets rather than a free-form weight spec, because a malformed strategy is not a validation error at
 * configuration time — it is an ECS 400 at {@code RunTask}, after the client has already uploaded its
 * inputs. This project has had exactly that failure once already, when the cluster was missing its capacity
 * provider associations and every cell failed to launch. Presets cannot be malformed.
 */
public enum FargateCapacityStrategy {

    /**
     * Spot preferred, on-demand as fallback. The historical behaviour and still the default: a build is
     * retryable, and the discount is large.
     *
     * <p>Both providers are listed, so ECS falls back rather than failing when Spot capacity is
     * unavailable. {@code base(1)} on the preferred provider places the first task there; the weights
     * distribute the remainder.
     */
    SPOT_PREFERRED("spot-preferred"),

    /**
     * On-demand preferred, Spot as fallback. For deployments where an interrupted build costs more than the
     * capacity saved — a release pipeline, or a long PGO cycle whose earlier phase would have to be redone.
     */
    ON_DEMAND_PREFERRED("on-demand-preferred"),

    /**
     * Spot only, no fallback. Cheapest, and a build fails to launch outright when Spot capacity is
     * unavailable rather than quietly costing full price. Choose this to make cost a hard constraint.
     */
    SPOT_ONLY("spot-only"),

    /**
     * On-demand only. No interruptions, no surprises, no discount. The right choice when a build is on a
     * critical path, and the honest choice when someone says "builds must not fail for infrastructure
     * reasons".
     */
    ON_DEMAND_ONLY("on-demand-only");

    private static final String SPOT = "FARGATE_SPOT";
    private static final String ON_DEMAND = "FARGATE";

    /** Ratio between preferred and fallback providers. 4:1 preserves the previous hardcoded behaviour. */
    private static final int PREFERRED_WEIGHT = 4;
    private static final int FALLBACK_WEIGHT = 1;

    private final String configValue;

    FargateCapacityStrategy(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    /**
     * Parses a configuration value, accepting either the hyphenated form or the enum name.
     *
     * @throws IllegalArgumentException naming every accepted value, because this is read at startup and a
     *     typo should fail the deployment with something actionable rather than silently defaulting
     */
    public static FargateCapacityStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            return SPOT_PREFERRED;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (FargateCapacityStrategy candidate : values()) {
            if (candidate.configValue.equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown Fargate capacity strategy '" + value + "'. Accepted: "
                + List.of(values()).stream().map(FargateCapacityStrategy::configValue).toList());
    }

    /**
     * The ECS strategy for this preset.
     *
     * @param forceOnDemand when true, escalate to on-demand regardless of the preset — used by
     *     {@link EcsTaskSupervisor} after repeated Spot interruptions. Has no effect on {@link #SPOT_ONLY},
     *     which is a deliberate cost ceiling rather than a preference, and none on the on-demand presets,
     *     which are already there.
     */
    public List<CapacityProviderStrategyItem> toStrategy(boolean forceOnDemand) {
        if (forceOnDemand && this == SPOT_PREFERRED) {
            return ON_DEMAND_PREFERRED.toStrategy(false);
        }
        return switch (this) {
            case SPOT_PREFERRED -> weighted(SPOT, ON_DEMAND);
            case ON_DEMAND_PREFERRED -> weighted(ON_DEMAND, SPOT);
            case SPOT_ONLY -> single(SPOT);
            case ON_DEMAND_ONLY -> single(ON_DEMAND);
        };
    }

    private static List<CapacityProviderStrategyItem> weighted(String preferred, String fallback) {
        return List.of(
                CapacityProviderStrategyItem.builder()
                        .capacityProvider(preferred).weight(PREFERRED_WEIGHT).base(1).build(),
                CapacityProviderStrategyItem.builder()
                        .capacityProvider(fallback).weight(FALLBACK_WEIGHT).base(0).build());
    }

    private static List<CapacityProviderStrategyItem> single(String provider) {
        return List.of(CapacityProviderStrategyItem.builder()
                .capacityProvider(provider).weight(1).base(1).build());
    }
}

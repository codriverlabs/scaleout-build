/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;

/**
 * Pins the Fargate capacity presets.
 *
 * <p>Worth testing because a malformed strategy is not caught at configuration time — it is an ECS 400 at
 * {@code RunTask}, after the client has uploaded its inputs. This project has had that failure once
 * already, when the cluster lacked capacity provider associations and every cell failed to launch.
 */
class FargateCapacityStrategyTest {

    private static List<String> providers(List<CapacityProviderStrategyItem> strategy) {
        return strategy.stream().map(CapacityProviderStrategyItem::capacityProvider).toList();
    }

    @Test
    void spotPreferredListsBothSoEcsCanFallBack() {
        List<CapacityProviderStrategyItem> strategy =
                FargateCapacityStrategy.SPOT_PREFERRED.toStrategy(false);

        assertThat(providers(strategy)).containsExactly("FARGATE_SPOT", "FARGATE");
        assertThat(strategy.get(0).weight()).isGreaterThan(strategy.get(1).weight());
        assertThat(strategy.get(0).base())
                .as("base(1) places the first task on the preferred provider")
                .isEqualTo(1);
    }

    @Test
    void onDemandPreferredIsTheSameShapeInverted() {
        assertThat(providers(FargateCapacityStrategy.ON_DEMAND_PREFERRED.toStrategy(false)))
                .containsExactly("FARGATE", "FARGATE_SPOT");
    }

    /**
     * A single-provider preset must not list a fallback, or it silently stops being a constraint: the whole
     * point of {@code spot-only} is that a build fails rather than quietly running at full price.
     */
    @Test
    void singleProviderPresetsHaveNoFallback() {
        assertThat(providers(FargateCapacityStrategy.SPOT_ONLY.toStrategy(false)))
                .containsExactly("FARGATE_SPOT");
        assertThat(providers(FargateCapacityStrategy.ON_DEMAND_ONLY.toStrategy(false)))
                .containsExactly("FARGATE");
    }

    /** Escalation after repeated Spot interruptions, which is what the supervisor uses. */
    @Test
    void forceOnDemandFlipsSpotPreferred() {
        assertThat(providers(FargateCapacityStrategy.SPOT_PREFERRED.toStrategy(true)))
                .containsExactly("FARGATE", "FARGATE_SPOT");
    }

    /**
     * {@code spot-only} is a cost ceiling, not a preference, so escalation must not quietly breach it. An
     * operator who chose it wants the build to fail rather than to cost full price.
     */
    @Test
    void forceOnDemandDoesNotOverrideAnExplicitCostCeiling() {
        assertThat(providers(FargateCapacityStrategy.SPOT_ONLY.toStrategy(true)))
                .containsExactly("FARGATE_SPOT");
    }

    @Test
    void parsingAcceptsHyphenatedAndUnderscoredForms() {
        assertThat(FargateCapacityStrategy.parse("on-demand-only"))
                .isEqualTo(FargateCapacityStrategy.ON_DEMAND_ONLY);
        assertThat(FargateCapacityStrategy.parse("ON_DEMAND_ONLY"))
                .isEqualTo(FargateCapacityStrategy.ON_DEMAND_ONLY);
    }

    @Test
    void parsingDefaultsWhenUnset() {
        assertThat(FargateCapacityStrategy.parse(null)).isEqualTo(FargateCapacityStrategy.SPOT_PREFERRED);
        assertThat(FargateCapacityStrategy.parse("  ")).isEqualTo(FargateCapacityStrategy.SPOT_PREFERRED);
    }

    /** A typo must name the accepted values rather than silently defaulting to something cheaper. */
    @Test
    void parsingRejectsUnknownValuesWithActionableMessage() {
        assertThatThrownBy(() -> FargateCapacityStrategy.parse("cheapest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spot-preferred")
                .hasMessageContaining("on-demand-only");
    }

    /** Preserves the behaviour that was hardcoded before this was configurable. */
    @Test
    void theDefaultMatchesThePreviousHardcodedBehaviour() {
        List<CapacityProviderStrategyItem> strategy =
                FargateCapacityStrategy.parse(null).toStrategy(false);

        assertThat(providers(strategy)).containsExactly("FARGATE_SPOT", "FARGATE");
        assertThat(strategy.get(0).weight()).isEqualTo(4);
        assertThat(strategy.get(1).weight()).isEqualTo(1);
    }
}

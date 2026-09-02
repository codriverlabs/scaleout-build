/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DsqlConnectionSettingsTest {

    @Test
    void ofUsesSensibleDefaults() {
        DsqlConnectionSettings settings =
                DsqlConnectionSettings.of("abcd1234.dsql.us-east-1.on.aws", "us-east-1", "admin");

        assertThat(settings.maxPoolSize()).isEqualTo(5);
        assertThat(settings.maxLifetime()).isLessThan(DsqlConnectionSettings.DSQL_MAX_CONNECTION_DURATION);
        assertThat(settings.jdbcUrl())
                .isEqualTo("jdbc:aws-dsql:postgresql://abcd1234.dsql.us-east-1.on.aws/postgres");
    }

    @Test
    void rejectsAClusterEndpointThatLooksLikeAUrl() {
        assertThatThrownBy(
                () -> DsqlConnectionSettings.of("https://abcd1234.dsql.us-east-1.on.aws", "us-east-1",
                        "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bare host");
    }

    @Test
    void rejectsAMaxLifetimeAtOrAboveTheDsqlConnectionCap() {
        assertThatThrownBy(() -> new DsqlConnectionSettings("abcd1234.dsql.us-east-1.on.aws",
                "us-east-1", "admin", 5, Duration.ofMinutes(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PT1H");

        assertThatThrownBy(() -> new DsqlConnectionSettings("abcd1234.dsql.us-east-1.on.aws",
                "us-east-1", "admin", 5, Duration.ofMinutes(90)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAMaxLifetimeJustUnderTheCap() {
        DsqlConnectionSettings settings = new DsqlConnectionSettings("abcd1234.dsql.us-east-1.on.aws",
                "us-east-1", "admin", 5, Duration.ofMinutes(59));

        assertThat(settings.maxLifetime()).isEqualTo(Duration.ofMinutes(59));
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertThatThrownBy(() -> DsqlConnectionSettings.of("", "us-east-1", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clusterEndpoint");
        assertThatThrownBy(
                () -> DsqlConnectionSettings.of("abcd1234.dsql.us-east-1.on.aws", "", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("region");
        assertThatThrownBy(
                () -> DsqlConnectionSettings.of("abcd1234.dsql.us-east-1.on.aws", "us-east-1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("databaseUser");
    }

    @Test
    void rejectsANonPositivePoolSize() {
        assertThatThrownBy(() -> new DsqlConnectionSettings("abcd1234.dsql.us-east-1.on.aws",
                "us-east-1", "admin", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPoolSize");
    }
}

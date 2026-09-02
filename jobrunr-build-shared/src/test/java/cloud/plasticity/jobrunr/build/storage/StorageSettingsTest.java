/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cloud.plasticity.jobrunr.build.Architecture;
import org.junit.jupiter.api.Test;

class StorageSettingsTest {

    @Test
    void dsqlRequiresConnectionSettings() {
        assertThatThrownBy(() -> StorageSettings.of(StorageType.DSQL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StorageSettings.dsql");
    }

    @Test
    void dsqlFactoryWiresTheConnectionSettingsThrough() {
        DsqlConnectionSettings connection =
                DsqlConnectionSettings.of("abcd1234.dsql.us-east-1.on.aws", "us-east-1", "admin");
        StorageSettings settings = StorageSettings.dsql(connection);

        assertThat(settings.type()).isEqualTo(StorageType.DSQL);
        assertThat(settings.dsqlConnectionSettings()).isSameAs(connection);
        assertThat(settings.schemaPrefix()).isEqualTo("jobrunr_");
    }

    @Test
    void tablePrefixIsSchemaQualifiedPerArchitecture() {
        DsqlConnectionSettings connection =
                DsqlConnectionSettings.of("abcd1234.dsql.us-east-1.on.aws", "us-east-1", "admin");
        StorageSettings settings = StorageSettings.dsql(connection, "custom_");

        assertThat(settings.tablePrefix(Architecture.X86_64)).isEqualTo("custom_x86_64.");
        assertThat(settings.tablePrefix(Architecture.ARM64)).isEqualTo("custom_arm64.");
    }

    @Test
    void inMemoryNeedsNoConnectionSettings() {
        StorageSettings settings = StorageSettings.inMemory();

        assertThat(settings.type()).isEqualTo(StorageType.IN_MEMORY);
        assertThat(settings.dsqlConnectionSettings()).isNull();
    }
}

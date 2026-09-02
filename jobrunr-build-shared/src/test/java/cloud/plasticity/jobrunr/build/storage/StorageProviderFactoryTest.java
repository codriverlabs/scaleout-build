/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cloud.plasticity.jobrunr.build.Architecture;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.Test;

class StorageProviderFactoryTest {

    @Test
    void twoArgOverloadCreatesInMemoryStorage() {
        StorageProvider provider =
                StorageProviderFactory.create(StorageSettings.inMemory(), StorageProviderFactory.jsonMapper());

        assertThat(provider).isInstanceOf(InMemoryStorageProvider.class);
    }

    @Test
    void twoArgOverloadRejectsNonInMemorySettings() {
        DsqlConnectionSettings connection =
                DsqlConnectionSettings.of("abcd1234.dsql.us-east-1.on.aws", "us-east-1", "admin");
        StorageSettings dsqlSettings = StorageSettings.dsql(connection);

        assertThatThrownBy(() -> StorageProviderFactory.create(dsqlSettings,
                StorageProviderFactory.jsonMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("architecture");
    }

    @Test
    void threeArgOverloadRequiresArchitectureForDsql() {
        DsqlConnectionSettings connection =
                DsqlConnectionSettings.of("abcd1234.dsql.us-east-1.on.aws", "us-east-1", "admin");
        StorageSettings dsqlSettings = StorageSettings.dsql(connection);

        assertThatThrownBy(() -> StorageProviderFactory.create(dsqlSettings, null,
                StorageProviderFactory.jsonMapper()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void postgresBackingIsNotYetImplemented() {
        assertThatThrownBy(() -> StorageProviderFactory.create(
                StorageSettings.of(StorageType.POSTGRES, null), Architecture.X86_64))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The owner hash is what separates one engineer's content-addressed store from another's, so its
 * properties are pinned rather than assumed.
 */
class StagingServiceOwnerHashTest {

    private static final String ALICE = "arn:aws:iam::864899852480:role/Dev/alice@codriverlabs.ai";
    private static final String BOB = "arn:aws:iam::864899852480:role/Dev/bob@codriverlabs.ai";

    @Test
    void isStableForTheSameOwner() {
        assertThat(StagingService.ownerHash(ALICE)).isEqualTo(StagingService.ownerHash(ALICE));
    }

    @Test
    void separatesDifferentOwners() {
        assertThat(StagingService.ownerHash(ALICE)).isNotEqualTo(StagingService.ownerHash(BOB));
    }

    /**
     * The reason for hashing at all: an ownerKey is an ARN containing {@code /} and {@code @}, and its
     * session-name component is influenced by the caller. Embedded raw, a caller could steer path
     * separators into S3 keys.
     */
    @Test
    void producesKeySafeSegmentsFromArnsContainingSeparators() {
        String hash = StagingService.ownerHash(ALICE);

        assertThat(hash).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(hash).doesNotContain("/").doesNotContain("@").doesNotContain("..");
    }

    @Test
    void aCallerCannotEscapeItsPrefixByChoosingASessionName() {
        // A session name is attacker-influenced in an SSO or assume-role flow. Even a deliberately
        // hostile one must hash to a flat hex segment.
        String hostile = "arn:aws:sts::1:assumed-role/Dev/../../other-owner";

        assertThat(StagingService.ownerHash(hostile)).matches("[0-9a-f]{32}");
    }
}

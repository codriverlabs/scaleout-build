/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * These tests are the specification for "a developer sees only their own builds". The ownership key
 * decides what is isolated from what, so each branch is pinned explicitly rather than left to
 * inference.
 */
class CallerIdentityResolverTest {

    private final CallerIdentityResolver resolver = new CallerIdentityResolver(new ObjectMapper());

    // --- Ownership rules ---------------------------------------------------------------------

    @Test
    void anIamUserOwnsBuildsByItsOwnArn() {
        CallerIdentity id = CallerIdentityResolver.of(
                "arn:aws:iam::123456789012:user/build-user", null, "1.2.3.4");

        assertThat(id.ownerKey()).isEqualTo("arn:aws:iam::123456789012:user/build-user");
        assertThat(id.perHuman()).isTrue();
        assertThat(id.sessionName()).isNull();
        assertThat(id.accountId()).isEqualTo("123456789012");
    }

    @Test
    void ssoUsersSharingOneRoleAreIsolatedFromEachOther() {
        CallerIdentity alice = CallerIdentityResolver.of(
                "arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_Dev_abc/alice@example.com",
                "AROAEXAMPLE:alice@example.com", null);
        CallerIdentity bob = CallerIdentityResolver.of(
                "arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_Dev_abc/bob@example.com",
                "AROAEXAMPLE:bob@example.com", null);

        assertThat(alice.ownerKey()).isNotEqualTo(bob.ownerKey());
        assertThat(alice.perHuman()).isTrue();
        assertThat(bob.perHuman()).isTrue();
        // Both still normalize to the same underlying role, which is what makes the naive
        // role-only key pool them together.
        assertThat(alice.roleArn()).isEqualTo(bob.roleArn());
    }

    @Test
    void theSameSsoUserKeepsOneOwnerKeyAcrossSessions() {
        // Identity Center reuses the session name per user, so a later login must resolve to the
        // same owner -- otherwise a developer loses sight of yesterday's builds.
        CallerIdentity monday = CallerIdentityResolver.of(
                "arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_Dev_abc/alice@example.com",
                null, null);
        CallerIdentity tuesday = CallerIdentityResolver.of(
                "arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_Dev_2xy/alice@example.com",
                null, null);

        assertThat(monday.ownerKey()).endsWith("/alice@example.com");
        assertThat(tuesday.ownerKey()).endsWith("/alice@example.com");
    }

    @Test
    void emailCasingDoesNotSplitOneHumanIntoTwoOwners() {
        assertThat(CallerIdentityResolver.of(
                "arn:aws:sts::1:assumed-role/R/Alice@Corp.COM", null, null).ownerKey())
                .isEqualTo(CallerIdentityResolver.of(
                        "arn:aws:sts::1:assumed-role/R/alice@corp.com", null, null).ownerKey());
    }

    @Test
    void identityCentreUuidSessionsAreTreatedAsHuman() {
        CallerIdentity id = CallerIdentityResolver.of(
                "arn:aws:sts::1:assumed-role/AWSReservedSSO_Dev_abc/"
                        + "9f1cf3a0-1fde-4499-a6b7-0a9805dcf9fb", null, null);

        assertThat(id.perHuman()).isTrue();
        assertThat(id.ownerKey()).endsWith("/9f1cf3a0-1fde-4499-a6b7-0a9805dcf9fb");
    }

    @Test
    void ciWithAThrowawaySessionNameFallsBackToTheRoleSoRepeatedRunsShareAnOwner() {
        // Keying on a random session name would make every CI run a different owner, unable to read
        // back the build it had just created.
        CallerIdentity run1 = CallerIdentityResolver.of(
                "arn:aws:sts::1:assumed-role/GitHubActionsRole/run-1849302", null, null);
        CallerIdentity run2 = CallerIdentityResolver.of(
                "arn:aws:sts::1:assumed-role/GitHubActionsRole/run-1849777", null, null);

        assertThat(run1.ownerKey()).isEqualTo(run2.ownerKey());
        assertThat(run1.ownerKey()).isEqualTo("arn:aws:iam::1:role/GitHubActionsRole");
        assertThat(run1.perHuman())
                .as("the service must not claim per-human isolation it did not achieve")
                .isFalse();
    }

    @Test
    void anEc2InstanceProfileFallsBackToTheRole() {
        CallerIdentity id = CallerIdentityResolver.of(
                "arn:aws:sts::1:assumed-role/InstanceRole/i-0abc123def456", null, null);

        assertThat(id.ownerKey()).isEqualTo("arn:aws:iam::1:role/InstanceRole");
        assertThat(id.perHuman()).isFalse();
    }

    @Test
    void twoDifferentRolesAreNeverTheSameOwner() {
        assertThat(CallerIdentityResolver.of("arn:aws:sts::1:assumed-role/RoleA/s", null, null).ownerKey())
                .isNotEqualTo(CallerIdentityResolver.of(
                        "arn:aws:sts::1:assumed-role/RoleB/s", null, null).ownerKey());
    }

    @Test
    void theSameRoleNameInDifferentAccountsIsNotTheSameOwner() {
        assertThat(CallerIdentityResolver.of("arn:aws:sts::111:assumed-role/R/s", null, null).ownerKey())
                .isNotEqualTo(CallerIdentityResolver.of(
                        "arn:aws:sts::222:assumed-role/R/s", null, null).ownerKey());
    }

    // --- Normalization ------------------------------------------------------------------------

    @Test
    void normalizeCollapsesAssumedRoleArnsAndLeavesOthersAlone() {
        assertThat(CallerIdentityResolver.normalize(
                "arn:aws:sts::123:assumed-role/Developers/alice"))
                .isEqualTo("arn:aws:iam::123:role/Developers");
        assertThat(CallerIdentityResolver.normalize("arn:aws:iam::123:user/alice"))
                .isEqualTo("arn:aws:iam::123:user/alice");
        // Session names may contain slashes; only the first segment is the role name.
        assertThat(CallerIdentityResolver.normalize(
                "arn:aws:sts::123:assumed-role/Developers/alice/extra"))
                .isEqualTo("arn:aws:iam::123:role/Developers");
    }

    @Test
    void sessionNamePrefersTheArnOverPrincipalId() {
        assertThat(CallerIdentityResolver.sessionName(
                "arn:aws:sts::1:assumed-role/R/from-arn", "AROA:from-principal"))
                .isEqualTo("from-arn");
        assertThat(CallerIdentityResolver.sessionName(
                "arn:aws:iam::1:user/alice", "AROA:from-principal"))
                .isEqualTo("from-principal");
        assertThat(CallerIdentityResolver.sessionName("arn:aws:iam::1:user/alice", null)).isNull();
    }

    @Test
    void durableHumanIdentifierRecognisesEmailsAndUuidsOnly() {
        assertThat(CallerIdentityResolver.isDurableHumanIdentifier("alice@corp.com")).isTrue();
        assertThat(CallerIdentityResolver.isDurableHumanIdentifier(
                "9f1cf3a0-1fde-4499-a6b7-0a9805dcf9fb")).isTrue();
        assertThat(CallerIdentityResolver.isDurableHumanIdentifier("i-0abc123")).isFalse();
        assertThat(CallerIdentityResolver.isDurableHumanIdentifier("run-1849302")).isFalse();
        assertThat(CallerIdentityResolver.isDurableHumanIdentifier("botocore-session-1234")).isFalse();
        assertThat(CallerIdentityResolver.isDurableHumanIdentifier("")).isFalse();
        assertThat(CallerIdentityResolver.isDurableHumanIdentifier(null)).isFalse();
    }

    // --- Parsing the LWA header ---------------------------------------------------------------

    @Test
    void parsesTheNestedFunctionUrlRequestContextShape() throws Exception {
        String json = """
                {
                  "accountId": "123456789012",
                  "authorizer": {
                    "iam": {
                      "accessKey": "AKIAEXAMPLE",
                      "accountId": "123456789012",
                      "principalId": "AROAEXAMPLE:alice@example.com",
                      "userArn": "arn:aws:sts::123456789012:assumed-role/Dev/alice@example.com"
                    }
                  },
                  "http": { "method": "POST", "path": "/builds", "sourceIp": "203.0.113.7" }
                }
                """;

        CallerIdentity id = resolver.resolve(json);

        assertThat(id.ownerKey()).isEqualTo("arn:aws:iam::123456789012:role/Dev/alice@example.com");
        assertThat(id.sourceIp()).isEqualTo("203.0.113.7");
        assertThat(id.perHuman()).isTrue();
    }

    @Test
    void acceptsTopLevelFieldsWhenNotNestedUnderAuthorizer() throws Exception {
        CallerIdentity id = resolver.resolve(
                "{\"userArn\":\"arn:aws:iam::1:user/alice\",\"sourceIp\":\"10.0.0.1\"}");

        assertThat(id.ownerKey()).isEqualTo("arn:aws:iam::1:user/alice");
        assertThat(id.sourceIp()).isEqualTo("10.0.0.1");
    }

    /**
     * The whole point of using a JSON parser rather than substring scanning: a value containing an
     * escaped quote, or the literal key name appearing inside another value, must not shift which
     * field is read.
     */
    @Test
    void parsingIsNotFooledByQuotesOrKeyNamesInsideValues() throws Exception {
        String json = """
                {
                  "authorizer": { "iam": {
                    "userAgent": "evil \\"userArn\\": \\"arn:aws:iam::999:user/attacker\\" client",
                    "userArn": "arn:aws:iam::1:user/alice"
                  } }
                }
                """;

        assertThat(resolver.resolve(json).ownerKey()).isEqualTo("arn:aws:iam::1:user/alice");
    }

    // --- Fail closed --------------------------------------------------------------------------

    @Test
    void rejectsAbsentMalformedAndIdentityFreeContexts() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(UnauthenticatedException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(UnauthenticatedException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> resolver.resolve("{not json"))
                .isInstanceOf(UnauthenticatedException.class).hasMessageContaining("unparseable");
        assertThatThrownBy(() -> resolver.resolve("{}"))
                .isInstanceOf(UnauthenticatedException.class).hasMessageContaining("no userArn");
        // An empty-string userArn is not an identity either.
        assertThatThrownBy(() -> resolver.resolve("{\"userArn\":\"\"}"))
                .isInstanceOf(UnauthenticatedException.class).hasMessageContaining("no userArn");
    }
}

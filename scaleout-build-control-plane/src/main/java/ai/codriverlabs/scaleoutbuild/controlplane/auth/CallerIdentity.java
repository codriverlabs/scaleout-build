/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.auth;

import java.util.Objects;

/**
 * The verified identity of one API caller.
 *
 * @param userArn     the raw ARN as AWS reported it — an IAM user ARN, or an STS assumed-role ARN
 *                    including the session name
 * @param roleArn     {@code userArn} normalized to a stable principal: an assumed-role ARN collapsed
 *                    to its underlying {@code iam::<account>:role/<Name>}. Equal to {@code userArn}
 *                    for an IAM user
 * @param ownerKey    the value builds are actually owned by and filtered on. Per-human where a human
 *                    can be identified, per-role otherwise — see {@link CallerIdentityResolver}
 * @param sessionName the STS session name, or {@code null} for an IAM user
 * @param perHuman    whether {@link #ownerKey()} identifies one person rather than everyone sharing
 *                    a role. Recorded so the service can report honestly what isolation a caller
 *                    actually has, instead of implying per-human isolation it cannot provide
 * @param sourceIp    caller IP, for audit logging only — never for authorization
 */
public record CallerIdentity(String userArn, String roleArn, String ownerKey, String sessionName,
                             boolean perHuman, String sourceIp) {

    /** Request property key under which the filter publishes this for downstream resources. */
    public static final String PROPERTY = "scaleout.callerIdentity";

    public CallerIdentity {
        Objects.requireNonNull(userArn, "userArn");
        Objects.requireNonNull(roleArn, "roleArn");
        Objects.requireNonNull(ownerKey, "ownerKey");
    }

    /** @return the account id embedded in the ARN, or {@code null} if it cannot be parsed */
    public String accountId() {
        String[] parts = userArn.split(":");
        return parts.length > 4 && !parts[4].isBlank() ? parts[4] : null;
    }
}

/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the Lambda request context into a {@link CallerIdentity}, deciding what a build is owned by.
 *
 * <p>Pure functions with no JAX-RS or AWS dependencies, so the ownership rules — the security-
 * relevant part — are exhaustively unit-testable without a container.
 *
 * <h2>Ownership: per-human where possible, per-role where not</h2>
 *
 * The requirement is that a developer sees only their own builds. That is harder than it looks,
 * because what AWS reports depends on how the caller authenticated:
 *
 * <ul>
 *   <li><b>IAM user</b> ({@code arn:aws:iam::123:user/alice}) — already per-human. The ARN is the
 *       owner key.</li>
 *   <li><b>SSO / IAM Identity Center</b>
 *       ({@code arn:aws:sts::123:assumed-role/AWSReservedSSO_Dev_abc/alice@corp.com}) — many humans
 *       share the role, so the role ARN alone would pool them. The session name is the human, and
 *       Identity Center reuses it per user across sessions, so it is stable. Owner key is
 *       {@code <roleArn>/<sessionName>}.</li>
 *   <li><b>CI, EC2 instance profile, or any role assumed with a throwaway session name</b>
 *       ({@code .../assumed-role/CiRole/i-0abc123} or a random string) — the session name is *not*
 *       stable, so keying on it would make every CI run a different owner, unable to see the build
 *       it just created. Owner key falls back to the normalized role ARN, which is correct: all runs
 *       of that role are legitimately the same owner.</li>
 * </ul>
 *
 * The distinction rests on whether the session name looks like a durable human identifier — an
 * email address or a UUID, which is what Identity Center emits. This is a heuristic, and
 * {@link CallerIdentity#perHuman()} records which branch was taken so the service never claims
 * isolation it did not actually achieve.
 *
 * <p><b>Tags are deliberately not the mechanism.</b> ECS task tags and S3 object tags are written
 * for cost allocation and for answering "whose task is this?" in the console, but authorization is
 * this owner key compared against the stored value. A tag filter on a describe call is a query
 * convenience, not an authorization boundary: it decides what a listing returns, not what a caller
 * is permitted to touch, and a caller who knows a build id must still be refused.
 */
public final class CallerIdentityResolver {

    /** Identity Center emits a UUID session name when the user has no email attribute. */
    private static final Pattern UUID_SESSION = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Deliberately loose: anything with a local part, an {@code @}, and a dotted domain. Precise
     * RFC 5322 validation is not the point — distinguishing "a human's durable identifier" from
     * "i-0abc123" is.
     */
    private static final Pattern EMAIL_SESSION = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final String ASSUMED_ROLE_MARKER = ":assumed-role/";

    private final ObjectMapper objectMapper;

    public CallerIdentityResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses the JSON that the Lambda Web Adapter forwards in {@code x-amzn-request-context}.
     *
     * <p>Parsed with a real JSON parser rather than substring scanning, which silently returns the
     * wrong value once any field contains an escaped quote or a key name appears inside another
     * string.
     *
     * @throws UnauthenticatedException if the payload is absent, malformed, or carries no verified
     *         caller ARN. Never returns an anonymous identity — see the class javadoc of
     *         {@link CallerIdentityFilter} for why that must fail closed.
     */
    public CallerIdentity resolve(String requestContextJson) throws UnauthenticatedException {
        if (requestContextJson == null || requestContextJson.isBlank()) {
            throw new UnauthenticatedException("missing x-amzn-request-context");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(requestContextJson);
        } catch (Exception e) {
            throw new UnauthenticatedException("unparseable x-amzn-request-context");
        }

        // Function URL request contexts nest IAM details under authorizer.iam; some shapes place
        // them at the top level. Accept either rather than depending on one nesting.
        JsonNode iam = root.path("authorizer").path("iam");
        String userArn = firstNonBlank(text(iam, "userArn"), text(root, "userArn"));
        if (userArn == null) {
            throw new UnauthenticatedException("no userArn in request context");
        }
        String principalId = firstNonBlank(text(iam, "principalId"), text(root, "principalId"));
        String sourceIp = firstNonBlank(text(root.path("http"), "sourceIp"), text(root, "sourceIp"));

        return of(userArn, principalId, sourceIp);
    }

    /** Applies the ownership rules to an already-extracted ARN. Visible for direct unit testing. */
    public static CallerIdentity of(String userArn, String principalId, String sourceIp) {
        String roleArn = normalize(userArn);
        String sessionName = sessionName(userArn, principalId);

        if (!userArn.contains(ASSUMED_ROLE_MARKER)) {
            // An IAM user ARN is already one human.
            return new CallerIdentity(userArn, roleArn, roleArn, null, true, sourceIp);
        }
        if (isDurableHumanIdentifier(sessionName)) {
            return new CallerIdentity(userArn, roleArn, roleArn + "/" + sessionName.toLowerCase(Locale.ROOT),
                    sessionName, true, sourceIp);
        }
        // Throwaway session name: key on the role so repeated runs remain the same owner.
        return new CallerIdentity(userArn, roleArn, roleArn, sessionName, false, sourceIp);
    }

    /**
     * {@code arn:aws:sts::123:assumed-role/RoleName/session} →
     * {@code arn:aws:iam::123:role/RoleName}. Returns the input unchanged for anything else.
     */
    static String normalize(String userArn) {
        if (!userArn.contains(ASSUMED_ROLE_MARKER)) {
            return userArn;
        }
        String afterMarker = userArn.substring(
                userArn.indexOf(ASSUMED_ROLE_MARKER) + ASSUMED_ROLE_MARKER.length());
        int slash = afterMarker.indexOf('/');
        String roleName = slash >= 0 ? afterMarker.substring(0, slash) : afterMarker;
        String[] parts = userArn.split(":");
        String account = parts.length > 4 ? parts[4] : "";
        return "arn:aws:iam::" + account + ":role/" + roleName;
    }

    /**
     * Prefers the session name from the ARN, because that is what the caller actually presented.
     * {@code principalId} ({@code AROAEXAMPLE:session}) is the fallback for shapes that omit it from
     * the ARN.
     */
    static String sessionName(String userArn, String principalId) {
        if (userArn != null && userArn.contains(ASSUMED_ROLE_MARKER)) {
            String afterMarker = userArn.substring(
                    userArn.indexOf(ASSUMED_ROLE_MARKER) + ASSUMED_ROLE_MARKER.length());
            int slash = afterMarker.indexOf('/');
            if (slash >= 0 && slash + 1 < afterMarker.length()) {
                return afterMarker.substring(slash + 1);
            }
        }
        if (principalId != null) {
            int colon = principalId.indexOf(':');
            if (colon >= 0 && colon + 1 < principalId.length()) {
                return principalId.substring(colon + 1);
            }
        }
        return null;
    }

    /** @return whether this session name identifies a person durably enough to own builds by */
    static boolean isDurableHumanIdentifier(String sessionName) {
        if (sessionName == null || sessionName.isBlank()) {
            return false;
        }
        return EMAIL_SESSION.matcher(sessionName).matches()
                || UUID_SESSION.matcher(sessionName).matches();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}

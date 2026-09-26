/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.api.UploadTarget;
import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Staging: content-addressed uploads in, produced artifacts out, all via presigned URLs so callers
 * need no S3 permissions of their own.
 *
 * <p>Reuses the existing {@link StagingLayout} so the keys the agent already reads are unchanged —
 * the control plane is a new front door onto the same layout, not a new layout.
 *
 * <p>Note what the client still owns: it generates {@code native-image.args} itself (via the Maven
 * plugin's planner) and ships it as an ordinary manifest input. The service therefore never has to
 * reimplement native-image argument construction, which would be a second place for that logic to
 * drift.
 */
@ApplicationScoped
public class StagingService {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final ControlPlaneConfig config;

    @Inject
    public StagingService(S3Client s3, S3Presigner presigner, ControlPlaneConfig config) {
        this.s3 = s3;
        this.presigner = presigner;
        this.config = config;
    }

    /**
     * The layout for one owner: {@code workspace/<ownerHash>/{cas,builds}/…}.
     *
     * <p>Per-owner rather than one shared store, for two reasons documented in
     * {@code docs/design/control-plane/storage-layout-and-isolation.md}. A shared store makes
     * {@link #missingDigests} an existence oracle over other owners' uploads; worse, a manifest is a
     * claim rather than a proof, so a caller could name a digest it never possessed and have the
     * service copy that blob into its build. Namespacing removes both structurally instead of by
     * remembering to check.
     *
     * <p>The cost is small: dedup still applies across all of one owner's builds, which is where the
     * benefit actually is — dependency jars are byte-identical build to build, while the project's own
     * jar changes every time because Maven embeds timestamps.
     */
    private StagingLayout layoutFor(String ownerKey) {
        String ownerHash = ownerHash(ownerKey);
        return new StagingLayout("workspace/" + ownerHash + "/builds",
                "workspace/" + ownerHash + "/cas");
    }

    /**
     * Hashed, not embedded: {@code ownerKey} is an ARN that may contain {@code /} and {@code @}
     * ({@code arn:aws:iam::123:role/Dev/alice@corp.com}), and its session-name component is influenced
     * by the caller. Embedding it raw would inject caller-steerable path separators into S3 keys.
     *
     * <p>32 hex characters — 128 bits, so collisions are not a practical concern. Not a secret: each
     * build record stores the {@code ownerKey} so an operator can resolve a prefix to a principal.
     */
    static String ownerHash(String ownerKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ownerKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    /**
     * @return the digests not already present in the content-addressed store, de-duplicated and in
     *         request order. In practice only the project's own jar appears here: dependency jars are
     *         byte-identical across builds and developers, so they are uploaded once ever.
     */
    public List<String> missingDigests(String ownerKey, List<InputDescriptor> inputs) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (InputDescriptor input : inputs) {
            if (!seen.add(input.sha256())) {
                continue;
            }
            if (!casObjectExists(ownerKey, input.sha256())) {
                missing.add(input.sha256());
            }
        }
        return missing;
    }

    public boolean casObjectExists(String ownerKey, String sha256) {
        try {
            s3.headObject(b -> b.bucket(config.stagingBucket()).key(layoutFor(ownerKey).casKey(sha256)));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            // headObject answers 404 for a missing key, which the SDK may surface as a generic
            // S3Exception rather than NoSuchKeyException depending on bucket permissions.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** Presigned {@code PUT} straight into the content-addressed store. */
    public UploadTarget presignUpload(String ownerKey, String sha256) {
        Duration ttl = Duration.ofSeconds(config.limits().presignedUrlTtlSeconds());
        var presigned = presigner.presignPutObject(b -> b
                .signatureDuration(ttl)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(config.stagingBucket())
                        .key(layoutFor(ownerKey).casKey(sha256))
                        .build()));
        return new UploadTarget(sha256, "PUT", presigned.url().toString(),
                Instant.now().plus(ttl));
    }

    /** Presigned {@code GET} for a produced artifact. */
    public UploadTarget presignDownload(String key, String sha256) {
        Duration ttl = Duration.ofSeconds(config.limits().presignedUrlTtlSeconds());
        var presigned = presigner.presignGetObject(b -> b
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(config.stagingBucket())
                        .key(key)
                        .build()));
        return new UploadTarget(sha256, "GET", presigned.url().toString(), Instant.now().plus(ttl));
    }

    /**
     * Copies every manifest input from the content-addressed store into one cell's staging prefix,
     * which is where the agent expects to read them.
     *
     * <p>Server-side copy, so the bytes never transit this function — a 200 MB classpath costs a few
     * S3 copy calls rather than 200 MB of Lambda bandwidth and memory.
     */
    public void materializeCell(String ownerKey, String buildId, BuildKind buildKind,
                                Architecture architecture, List<InputDescriptor> inputs) {
        String stagingPath = layoutFor(ownerKey).stagingPath(buildId, buildKind, architecture);
        for (InputDescriptor input : inputs) {
            String destination = stagingPath.endsWith("/")
                    ? stagingPath + input.path() : stagingPath + "/" + input.path();
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(config.stagingBucket())
                    .sourceKey(layoutFor(ownerKey).casKey(input.sha256()))
                    .destinationBucket(config.stagingBucket())
                    .destinationKey(destination)
                    .build());
        }
    }

    /** Key prefix the agent writes produced artifacts to. */
    /**
     * Object names directly under a cell's output prefix.
     *
     * <p>Needed because the build record cannot always say what was produced. A pass-through argfile names
     * its own output, so the service never learns the artifact name and the agent discovers it by scanning.
     * Listing S3 is then the only way to report artifacts, and it is also more truthful than the record for
     * a derived build: it reports what was uploaded rather than what was expected.
     */
    public List<String> listOutputNames(String ownerKey, String buildId, BuildKind buildKind,
                                        Architecture architecture) {
        String prefix = outputPrefix(ownerKey, buildId, buildKind, architecture);
        String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
        return s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(config.stagingBucket()).prefix(normalized).build())
                .contents().stream()
                .map(object -> object.key().substring(normalized.length()))
                .filter(name -> !name.isBlank() && !name.contains("/"))
                .toList();
    }

    public String outputPrefix(String ownerKey, String buildId, BuildKind buildKind,
                               Architecture architecture) {
        return layoutFor(ownerKey).outputPath(buildId, buildKind, architecture);
    }

    public String stagingPath(String ownerKey, String buildId, BuildKind buildKind,
                              Architecture architecture) {
        return layoutFor(ownerKey).stagingPath(buildId, buildKind, architecture);
    }
}

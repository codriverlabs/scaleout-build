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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import software.amazon.awssdk.services.s3.S3Client;
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
    private final StagingLayout layout = StagingLayout.defaults();

    @Inject
    public StagingService(S3Client s3, S3Presigner presigner, ControlPlaneConfig config) {
        this.s3 = s3;
        this.presigner = presigner;
        this.config = config;
    }

    /**
     * @return the digests not already present in the content-addressed store, de-duplicated and in
     *         request order. In practice only the project's own jar appears here: dependency jars are
     *         byte-identical across builds and developers, so they are uploaded once ever.
     */
    public List<String> missingDigests(List<InputDescriptor> inputs) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (InputDescriptor input : inputs) {
            if (!seen.add(input.sha256())) {
                continue;
            }
            if (!casObjectExists(input.sha256())) {
                missing.add(input.sha256());
            }
        }
        return missing;
    }

    public boolean casObjectExists(String sha256) {
        try {
            s3.headObject(b -> b.bucket(config.stagingBucket()).key(layout.casKey(sha256)));
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
    public UploadTarget presignUpload(String sha256) {
        Duration ttl = Duration.ofSeconds(config.limits().presignedUrlTtlSeconds());
        var presigned = presigner.presignPutObject(b -> b
                .signatureDuration(ttl)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(config.stagingBucket())
                        .key(layout.casKey(sha256))
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
    public void materializeCell(String buildId, BuildKind buildKind, Architecture architecture,
                                List<InputDescriptor> inputs) {
        String stagingPath = layout.stagingPath(buildId, buildKind, architecture);
        for (InputDescriptor input : inputs) {
            String destination = stagingPath.endsWith("/")
                    ? stagingPath + input.path() : stagingPath + "/" + input.path();
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(config.stagingBucket())
                    .sourceKey(layout.casKey(input.sha256()))
                    .destinationBucket(config.stagingBucket())
                    .destinationKey(destination)
                    .build());
        }
    }

    /** Key prefix the agent writes produced artifacts to. */
    public String outputPrefix(String buildId, BuildKind buildKind, Architecture architecture) {
        return layout.outputPath(buildId, buildKind, architecture);
    }

    public String stagingPath(String buildId, BuildKind buildKind, Architecture architecture) {
        return layout.stagingPath(buildId, buildKind, architecture);
    }
}

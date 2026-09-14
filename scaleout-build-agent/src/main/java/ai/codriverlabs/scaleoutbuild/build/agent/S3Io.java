/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build.agent;

import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The agent's direct-S3-calls I/O mode: downloads a build's staged inputs to a local directory
 * before {@code native-image} runs, and uploads the produced artifacts back to S3 afterward.
 *
 * <p>This is an alternative to, not a replacement for, the mount-based modes ({@link
 * ai.codriverlabs.scaleoutbuild.build.BuildEnvironment} resolving {@code stagingRelativePath} against an
 * already-mounted {@code mountRoot} — S3 Files on FARGATE/MANAGED_INSTANCES, Mountpoint-via-user-data
 * on EC2). Both exist because they have genuinely different tradeoffs, not because one is
 * deprecated: the mount modes need no agent-side S3 code at all but depend on infrastructure that
 * varies by launch type (an S3 Files file system + mount targets, or user-data-installed Mountpoint);
 * this mode needs no ECS-side mount infrastructure at all — just IAM permissions on the task role —
 * but makes the agent responsible for its own transfer, and means every byte native-image reads or
 * writes crosses the network twice (once down, once up) rather than being read/written lazily through
 * a FUSE-backed mount.
 *
 * <p>Selected by {@code SCALEOUT_BUILD_S3_BUCKET} being set (see {@link AgentConfig#s3Bucket()}); when
 * it is not set, the agent uses the mount-based path unchanged, and this class is never touched.
 *
 * <p>Reuses {@code stagingRelativePath} both as a mount-relative filesystem path (mount mode) and as
 * an S3 key prefix (this mode) — the same string works as either, since {@link StagingLayout}'s
 * layout was already POSIX-style and mount-agnostic before this mode existed.
 */
final class S3Io {

    private static final Logger LOG = LoggerFactory.getLogger(S3Io.class);

    private final S3Client s3Client;
    private final String bucket;

    S3Io(S3Client s3Client, String bucket) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /**
     * Downloads every object under {@code keyPrefix} into {@code localDir}, preserving the
     * structure relative to the prefix (so {@code <prefix>/lib/dep.jar} lands at
     * {@code <localDir>/lib/dep.jar}).
     *
     * @return number of objects downloaded
     * @throws IOException if the prefix has no objects at all, which in practice means the staged
     *         inputs never arrived — mirrors {@code BuildEnvironment.resolveStagingRoot}'s equivalent
     *         check for the mount mode
     */
    int downloadStagingDirectory(String keyPrefix, Path localDir) throws IOException {
        String normalizedPrefix = keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/";
        List<String> keys = listAllKeys(normalizedPrefix);
        if (keys.isEmpty()) {
            throw new IOException(
                    "No staged inputs found under s3://" + bucket + "/" + normalizedPrefix);
        }
        Files.createDirectories(localDir);
        for (String key : keys) {
            String relativePath = key.substring(normalizedPrefix.length());
            if (relativePath.isEmpty()) {
                continue; // a zero-byte "directory marker" object some tools create; not a real file
            }
            Path destination = localDir.resolve(relativePath).normalize();
            if (!destination.startsWith(localDir.normalize())) {
                throw new IOException("Staged key escapes the local staging directory: " + key);
            }
            Files.createDirectories(destination.getParent());
            LOG.debug("Downloading s3://{}/{} to {}", bucket, key, destination);
            try {
                s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
                        destination);
            } catch (SdkException e) {
                throw new IOException("Failed to download s3://" + bucket + "/" + key, e);
            }
        }
        LOG.info("Downloaded {} object(s) from s3://{}/{} to {}", keys.size(), bucket,
                normalizedPrefix, localDir);
        return keys.size();
    }

    /**
     * Uploads every regular file under {@code localOutputDir} to {@code keyPrefix}, preserving the
     * structure relative to {@code localOutputDir} (so {@code <localOutputDir>/app} lands at
     * {@code <prefix>/app}).
     *
     * <p>Uploads under the same {@code output/} prefix {@code S3ArtifactRetriever} already looks
     * under on the plugin side ({@code StagingLayout.OUTPUT_DIR_NAME}) — that class needs no changes
     * to retrieve artifacts produced by this I/O mode.
     *
     * @return number of files uploaded
     */
    int uploadOutputDirectory(Path localOutputDir, String keyPrefix) throws IOException {
        String normalizedPrefix = keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/";
        List<Path> files = listRegularFiles(localOutputDir);
        for (Path file : files) {
            String relativePath = localOutputDir.relativize(file).toString().replace('\\', '/');
            String key = normalizedPrefix + relativePath;
            LOG.debug("Uploading {} to s3://{}/{}", file, bucket, key);
            try {
                s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), file);
            } catch (SdkException e) {
                throw new IOException("Failed to upload " + file + " to s3://" + bucket + "/" + key,
                        e);
            }
        }
        LOG.info("Uploaded {} file(s) from {} to s3://{}/{}", files.size(), localOutputDir, bucket,
                normalizedPrefix);
        return files.size();
    }

    private List<String> listAllKeys(String normalizedPrefix) throws IOException {
        List<String> keys = new ArrayList<>();
        try {
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(normalizedPrefix);
                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }
                var response = s3Client.listObjectsV2(requestBuilder.build());
                for (S3Object object : response.contents()) {
                    keys.add(object.key());
                }
                continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (SdkException e) {
            throw new IOException("Failed to list s3://" + bucket + "/" + normalizedPrefix, e);
        }
        return keys;
    }

    private List<Path> listRegularFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}

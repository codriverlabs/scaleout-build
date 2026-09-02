/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.staging;

import cloud.plasticity.jobrunr.build.StagingLayout;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlan;
import cloud.plasticity.jobrunr.maven.planner.StagedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stages a build's inputs into S3, deduplicating classpath jars by content hash.
 *
 * <p>Layout, from {@link StagingLayout}: classpath jars go through a content-addressed blob store
 * ({@code cas/{sha256}}) shared across every build and architecture, and are materialised into the
 * build's own key space with a server-side {@link CopyObjectRequest} — no re-upload for a jar this
 * sink has already seen once, ever. Only the generated or pass-through argfile, which is small and
 * unique per build, is uploaded directly every time.
 *
 * <p>This is what makes the plugin's "first build ships the classpath, every later build ships
 * kilobytes" behaviour possible: see the design notes on why content-hash dedup matters when a
 * project's {@code lib/} directory is tens of megabytes of jars that rarely change.
 *
 * <p>The resulting S3 keys are also the paths the container agent sees through its ECS S3 Files
 * volume mount — a plain filesystem view of the same bucket — so nothing else needs to know this
 * sink used S3 at all.
 */
public final class S3StagingSink implements StagingSink {

    private static final Logger LOG = LoggerFactory.getLogger(S3StagingSink.class);
    private static final HexFormat HEX = HexFormat.of();

    private final S3Client s3Client;
    private final String bucket;
    private final StagingLayout layout;

    public S3StagingSink(S3Client s3Client, String bucket) {
        this(s3Client, bucket, StagingLayout.defaults());
    }

    public S3StagingSink(S3Client s3Client, String bucket, StagingLayout layout) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucket = requireNonBlank(bucket, "bucket");
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    @Override
    public long stage(NativeImageInputPlan plan, String stagingRelativePath) throws IOException {
        Objects.requireNonNull(plan, "plan");
        requireNonBlank(stagingRelativePath, "stagingRelativePath");

        long transferred = 0;
        for (StagedFile file : plan.files()) {
            String destinationKey = joinKey(stagingRelativePath, file.relativePath());
            transferred += stageThroughContentAddressedStore(file, destinationKey);
        }

        if (plan.generatedArgsContent().isPresent()) {
            String argsKey = joinKey(stagingRelativePath, plan.argsFileName());
            byte[] bytes = plan.generatedArgsContent().get().getBytes(StandardCharsets.UTF_8);
            putObject(argsKey, RequestBody.fromBytes(bytes));
            transferred += bytes.length;
        }
        return transferred;
    }

    /**
     * Uploads {@code file} into the content-addressed store if not already present, then makes a
     * server-side copy at {@code destinationKey}.
     *
     * @return bytes actually uploaded: the file's size if this is the first time this content has
     *         been seen, or zero if the blob already existed and only the (free, server-side) copy
     *         was needed
     */
    private long stageThroughContentAddressedStore(StagedFile file, String destinationKey)
            throws IOException {
        String sha256Hex = sha256Hex(file.source());
        String casKey = layout.casKey(sha256Hex);
        long fileSize = Files.size(file.source());

        boolean alreadyPresent = objectExists(casKey);
        if (!alreadyPresent) {
            LOG.debug("Uploading new blob {} ({} bytes) for {}", casKey, fileSize,
                    file.relativePath());
            putObject(casKey, RequestBody.fromFile(file.source()));
        } else {
            LOG.debug("Blob {} already present, skipping upload for {}", casKey,
                    file.relativePath());
        }

        copyObject(casKey, destinationKey);
        return alreadyPresent ? 0 : fileSize;
    }

    /** Uploads the container agent jar for {@code agentVersion} if not already present. */
    public boolean ensureAgentJarUploaded(Path agentJarPath, String agentVersion) throws IOException {
        Objects.requireNonNull(agentJarPath, "agentJarPath");
        requireNonBlank(agentVersion, "agentVersion");
        String key = layout.agentJarKey(agentVersion);
        if (objectExists(key)) {
            LOG.debug("Agent jar for version {} already present at {}", agentVersion, key);
            return false;
        }
        LOG.info("Uploading agent jar for version {} to {}", agentVersion, key);
        putObject(key, RequestBody.fromFile(agentJarPath));
        return true;
    }

    private boolean objectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private void putObject(String key, RequestBody body) throws IOException {
        try {
            s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), body);
        } catch (SdkException e) {
            throw new IOException("Failed to upload s3://" + bucket + "/" + key, e);
        }
    }

    private void copyObject(String sourceKey, String destinationKey) throws IOException {
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(sourceKey)
                    .destinationBucket(bucket).destinationKey(destinationKey)
                    .build());
        } catch (SdkException e) {
            throw new IOException(
                    "Failed to copy s3://" + bucket + "/" + sourceKey + " to " + destinationKey, e);
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM per the Java security standard names.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String joinKey(String prefix, String relativePath) {
        return prefix.endsWith("/") ? prefix + relativePath : prefix + "/" + relativePath;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

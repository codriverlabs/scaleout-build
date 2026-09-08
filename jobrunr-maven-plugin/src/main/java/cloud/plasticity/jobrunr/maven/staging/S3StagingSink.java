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
     * <p>Uses S3's own conditional-write support ({@code If-None-Match: *} on {@code PutObject},
     * GA since August 2024 — confirmed against AWS's own conditional-writes documentation, not
     * assumed) rather than a check-then-act {@code HeadObject}-then-{@code PutObject} pair. That
     * matters under concurrency: with two separate calls (one to check, one to act), two callers
     * racing to stage the very same not-yet-uploaded content can both observe "not present yet"
     * and both perform a full upload — S3 accepting both writes is harmless (they write identical
     * bytes to the same content-addressed key), but it silently defeats the dedup this store
     * exists for, and neither the caller's "bytes transferred" accounting nor its "was this a
     * cache hit" signal is accurate anymore. A single conditional {@code PutObject} closes that
     * window atomically and cross-process (not just within one JVM — a per-process/in-memory lock
     * would do nothing for two separate {@code mvn} invocations racing against the same shared
     * bucket): S3 guarantees "the first write operation to finish succeeds… [and] fails subsequent
     * writes with a 412 Precondition Failed response" for the same key, so at most one caller ever
     * actually uploads, and every other concurrent or later caller for the same content reliably
     * observes the failure and treats it as a cache hit.
     *
     * @return bytes actually uploaded: the file's size if this call performed the real upload
     *         (S3 accepted the conditional {@code PutObject}), or zero if the blob already existed
     *         — whether from an earlier build entirely or from another caller racing for the same
     *         content right now — and only the (free, server-side) copy was needed
     */
    private long stageThroughContentAddressedStore(StagedFile file, String destinationKey)
            throws IOException {
        String sha256Hex = sha256Hex(file.source());
        String casKey = layout.casKey(sha256Hex);
        long fileSize = Files.size(file.source());

        boolean uploaded = putIfAbsent(casKey, RequestBody.fromFile(file.source()));
        if (uploaded) {
            LOG.debug("Uploaded new blob {} ({} bytes) for {}", casKey, fileSize,
                    file.relativePath());
        } else {
            LOG.debug("Blob {} already present (uploaded by this call or a concurrent one), "
                    + "skipping upload for {}", casKey, file.relativePath());
        }

        copyObject(casKey, destinationKey);
        return uploaded ? fileSize : 0;
    }

    /**
     * Attempts a conditional {@code PutObject} with {@code If-None-Match: *}, succeeding only if
     * no object exists at {@code key} yet.
     *
     * @return {@code true} if this call's upload was the one S3 accepted, {@code false} if an
     *         object already existed at {@code key} (S3 responded {@code 412 Precondition
     *         Failed}), whether uploaded by an earlier, unrelated call or by another caller racing
     *         for the same key right now
     */
    private boolean putIfAbsent(String key, RequestBody body) throws IOException {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*").build(),
                    body);
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 412) {
                return false;
            }
            throw new IOException("Failed to conditionally upload s3://" + bucket + "/" + key, e);
        } catch (SdkException e) {
            throw new IOException("Failed to conditionally upload s3://" + bucket + "/" + key, e);
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

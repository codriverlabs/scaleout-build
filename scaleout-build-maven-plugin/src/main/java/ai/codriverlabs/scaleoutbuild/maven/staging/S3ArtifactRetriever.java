/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.staging;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads a completed remote build's produced binaries from S3.
 *
 * <p>Mirrors {@code ai.codriverlabs.scaleoutbuild.build.ArtifactCollector}'s local-filesystem logic —
 * named artifacts first, a directory listing as a fallback for builds whose argfile decides its own
 * output name — but against S3 keys instead of a local directory, since a remote build's output
 * never touches the plugin's local disk until this retriever pulls it there.
 */
public final class S3ArtifactRetriever {

    private static final Logger LOG = LoggerFactory.getLogger(S3ArtifactRetriever.class);

    /** Suffixes that are never the artifact itself; mirrors {@code ArtifactCollector}. */
    private static final Set<String> IGNORED_SUFFIXES =
            Set.of(".jar", ".args", ".json", ".txt", ".log", ".o", ".a", ".properties", ".class");

    private final S3Client s3Client;
    private final String bucket;
    private final StagingLayout layout;

    public S3ArtifactRetriever(S3Client s3Client, String bucket) {
        this(s3Client, bucket, StagingLayout.defaults());
    }

    public S3ArtifactRetriever(S3Client s3Client, String bucket, StagingLayout layout) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucket = requireNonBlank(bucket, "bucket");
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    /**
     * Downloads the artifacts of one build into {@code destinationDir}, named artifacts first, a key
     * listing under the build's staging prefix as a fallback.
     *
     * @param buildId        the build identifier used when staging inputs
     * @param buildKind      which build kind's staging root to look under
     * @param architecture   which architecture's staging root to look under
     * @param expectedNames  artifact file names to look for first; may be empty
     * @param destinationDir local directory the downloaded files are written into, created if
     *                       missing
     * @return absolute local paths of the downloaded files, in discovery order
     */
    public List<Path> retrieve(String buildId, BuildKind buildKind,
                               Architecture architecture, List<String> expectedNames,
                               Path destinationDir) throws IOException {
        Objects.requireNonNull(buildId, "buildId");
        Objects.requireNonNull(buildKind, "buildKind");
        Objects.requireNonNull(architecture, "architecture");
        Objects.requireNonNull(destinationDir, "destinationDir");
        Files.createDirectories(destinationDir);

        String stagingPrefix = layout.stagingPath(buildId, buildKind, architecture);
        String outputPrefix = layout.outputPath(buildId, buildKind, architecture);

        List<String> keys = new ArrayList<>();
        for (String name : expectedNames) {
            for (String candidate : List.of(joinKey(outputPrefix, name), joinKey(stagingPrefix, name))) {
                if (objectExists(candidate)) {
                    keys.add(candidate);
                    break;
                }
            }
        }
        if (keys.isEmpty()) {
            keys.addAll(listArtifactKeys(outputPrefix));
            keys.addAll(listArtifactKeys(stagingPrefix));
        }

        Set<Path> downloaded = new LinkedHashSet<>();
        for (String key : keys) {
            downloaded.add(download(key, destinationDir));
        }
        return List.copyOf(downloaded);
    }

    private List<String> listArtifactKeys(String prefix) {
        String normalizedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        List<String> keys = new ArrayList<>();
        try {
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(normalizedPrefix).delimiter("/");
                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }
                var response = s3Client.listObjectsV2(requestBuilder.build());
                for (S3Object object : response.contents()) {
                    if (!isIgnored(object.key())) {
                        keys.add(object.key());
                    }
                }
                continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (SdkException e) {
            throw new UncheckedIOException(
                    new IOException("Failed to list s3://" + bucket + "/" + normalizedPrefix, e));
        }
        return keys;
    }

    private boolean objectExists(String key) {
        try {
            s3Client.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (SdkException e) {
            return false;
        }
    }

    private Path download(String key, Path destinationDir) throws IOException {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        Path destination = destinationDir.resolve(fileName);
        LOG.info("Downloading s3://{}/{} to {}", bucket, key, destination);
        try {
            s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
                    destination);
        } catch (SdkException e) {
            throw new IOException("Failed to download s3://" + bucket + "/" + key, e);
        }
        return destination.toAbsolutePath();
    }

    private static boolean isIgnored(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.isEmpty() || IGNORED_SUFFIXES.stream().anyMatch(name::endsWith);
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

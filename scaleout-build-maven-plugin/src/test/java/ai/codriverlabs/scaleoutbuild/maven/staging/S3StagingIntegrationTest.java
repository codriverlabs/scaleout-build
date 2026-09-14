/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.staging;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import ai.codriverlabs.scaleoutbuild.maven.planner.NativeImageInputPlan;
import ai.codriverlabs.scaleoutbuild.maven.planner.StagedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Exercises {@link S3StagingSink} and {@link S3ArtifactRetriever} against SeaweedFS's S3 gateway,
 * a real third-party implementation of the S3 API rather than an AWS-emulating test double.
 *
 * <p>This matters specifically because {@link S3StagingSink}'s content-hash dedup depends on
 * server-side {@code CopyObject} — the very thing that makes the design's "first build ships the
 * classpath, every later build ships kilobytes" claim true. Exercising that against independently
 * implemented S3-protocol server code, rather than a same-vendor emulator, is a stronger check that
 * the sink's use of {@code CopyObject} is portable, standard S3 behaviour and not an artifact of one
 * emulator's interpretation of the API.
 *
 * <p>SeaweedFS's S3 gateway runs fully unauthenticated ("Allow All" mode) whenever no AWS
 * credentials are configured on the server side, which is what the plain {@code server -s3} command
 * below does — no {@code LOCALSTACK_AUTH_TOKEN}-style gate to work around.
 */
@Testcontainers
class S3StagingIntegrationTest {

    @Container
    private static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>("chrislusf/seaweedfs:4.23")
            .withCommand("server", "-s3", "-s3.port=8333", "-dir=/data",
                    "-master.volumeSizeLimitMB=100", "-s3.allowEmptyFolder=true")
            .withExposedPorts(8333)
            .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final String BUCKET = "scaleout-build-test";

    private static S3Client s3Client;

    @BeforeAll
    static void createClientAndBucket() {
        String endpoint = "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333);
        s3Client = S3Client.builder()
                .endpointOverride(java.net.URI.create(endpoint))
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @Test
    void stagesFilesThroughTheContentAddressedStoreAndDeduplicatesOnASecondBuild(
            @TempDir Path sourceDir) throws IOException {
        Path artifact = sourceDir.resolve("app.jar");
        Files.writeString(artifact, "fake-jar-bytes-shared-across-builds");
        Path dependency = sourceDir.resolve("dep.jar");
        Files.writeString(dependency, "fake-dep-bytes-shared-across-builds");

        NativeImageInputPlan plan = NativeImageInputPlan.generated(
                List.of(new StagedFile("app.jar", artifact), new StagedFile("lib/dep.jar", dependency)),
                "-cp\napp.jar:lib/dep.jar\n-o\noutput/app\ndemo.Main\n", List.of("app"));

        S3StagingSink sink = new S3StagingSink(s3Client, BUCKET);

        long firstBuildTransferred = sink.stage(plan, "builds/build-1/x86_64");
        long expectedArgsBytes =
                plan.generatedArgsContent().orElseThrow().getBytes(StandardCharsets.UTF_8).length;
        assertThat(firstBuildTransferred)
                .isEqualTo(Files.size(artifact) + Files.size(dependency) + expectedArgsBytes);

        assertObjectExists("builds/build-1/x86_64/app.jar");
        assertObjectExists("builds/build-1/x86_64/lib/dep.jar");
        assertObjectExists("builds/build-1/x86_64/native-image.args");

        // Second build reuses the same source files: only the (unique) argfile should be uploaded;
        // the classpath jars are already in the content-addressed store and only need a real,
        // server-side CopyObject -- proven against SeaweedFS's own CopyObject implementation, not
        // an emulator's.
        long secondBuildTransferred = sink.stage(plan, "builds/build-2/x86_64");
        assertThat(secondBuildTransferred).isEqualTo(expectedArgsBytes);
        assertObjectExists("builds/build-2/x86_64/app.jar");
        assertObjectExists("builds/build-2/x86_64/lib/dep.jar");

        // The copy must be a real, independent object -- not a reference -- so the content is
        // actually readable from the second build's own key.
        String copiedContent = s3Client.getObjectAsBytes(
                b -> b.bucket(BUCKET).key("builds/build-2/x86_64/app.jar")).asUtf8String();
        assertThat(copiedContent).isEqualTo("fake-jar-bytes-shared-across-builds");
    }

    @Test
    void deduplicatesTheSameClasspathAcrossFourConcurrentCellsRacingForTheFirstUpload()
            throws Exception {
        // Simulates a realistic single plugin invocation's matrix: four cells that would really
        // share the same resolved classpath -- NATIVE/PGO_INSTRUMENT/PGO_OPTIMIZE for one
        // architecture are independently-triggerable builds of the same project (see BuildKind's
        // own class Javadoc), and the same project's classpath doesn't change by target
        // architecture either. Each cell still gets its own staging prefix and its own generated
        // argfile content (real build-kind-specific native-image flags aren't identical either),
        // exactly like BuildMojo.runRemoteCells actually assembles.
        //
        // Deliberately staged through *separate* S3StagingSink instances (rather than one shared
        // instance called from many threads) -- the class itself is stateless per call, so a
        // shared-vs-per-cell instance should behave identically, and using separate instances is a
        // closer match to how superviseAllCellsConcurrently's per-cell threads each work against
        // shared AWS clients but don't share any sink-side cache.
        String dependencyContent = "shared-dependency-bytes-" + "x".repeat(4096); // larger payload
        String appContent = "shared-app-bytes-" + "y".repeat(1024);
        Path dependency = Files.createTempFile("dep", ".jar");
        Path app = Files.createTempFile("app", ".jar");
        Files.writeString(dependency, dependencyContent);
        Files.writeString(app, appContent);

        record Cell(BuildKind buildKind, Architecture architecture, String argfileContent) {
        }
        List<Cell> cells = List.of(
                new Cell(BuildKind.NATIVE, Architecture.X86_64, "-cp\napp.jar:lib/dep.jar\n-o\noutput/app\n"),
                new Cell(BuildKind.NATIVE_PGO_INSTRUMENT, Architecture.X86_64,
                        "-cp\napp.jar:lib/dep.jar\n--pgo-instrument\n-o\noutput/app\n"),
                new Cell(BuildKind.NATIVE_PGO_OPTIMIZE, Architecture.X86_64,
                        "-cp\napp.jar:lib/dep.jar\n--pgo=default.iprof\n-o\noutput/app\n"),
                new Cell(BuildKind.NATIVE, Architecture.ARM64, "-cp\napp.jar:lib/dep.jar\n-o\noutput/app\n"));

        // A CyclicBarrier forces all four threads to call stage() at the same instant rather than
        // hoping timing lines up -- this maximises the chance of exposing a real race in the
        // HeadObject-then-PutObject dedup check (two threads both observing "not present yet" for
        // the same not-yet-uploaded content and both uploading), rather than the test passing only
        // because one thread happened to finish before another started.
        CyclicBarrier barrier = new CyclicBarrier(cells.size());
        ExecutorService executor = Executors.newFixedThreadPool(cells.size());
        try {
            List<Future<Long>> futures = cells.stream().map(cell -> executor.submit(() -> {
                NativeImageInputPlan plan = NativeImageInputPlan.generated(
                        List.of(new StagedFile("app.jar", app),
                                new StagedFile("lib/dep.jar", dependency)),
                        cell.argfileContent(), List.of("app"));
                S3StagingSink sink = new S3StagingSink(s3Client, BUCKET);
                String stagingPath = StagingLayout.defaults()
                        .stagingPath("build-concurrent", cell.buildKind(), cell.architecture());
                barrier.await(10, TimeUnit.SECONDS);
                return sink.stage(plan, stagingPath);
            })).toList();

            long totalTransferred = 0;
            for (Future<Long> future : futures) {
                totalTransferred += future.get(30, TimeUnit.SECONDS);
            }

            // The two shared blobs (dependency, app) must each have been uploaded exactly once in
            // aggregate across all four cells, no matter how their HeadObject checks interleaved --
            // this is the actual dedup claim under concurrency, not just "the final state looks
            // right by luck". Each cell's own unique argfile content is never deduped (it's never
            // seen before, by construction), so it's real, expected transfer on top of that.
            long expectedArgfileBytes = cells.stream()
                    .mapToLong(cell -> cell.argfileContent().getBytes(StandardCharsets.UTF_8).length)
                    .sum();
            long expectedSharedBlobBytes = Files.size(dependency) + Files.size(app);
            assertThat(totalTransferred).isEqualTo(expectedSharedBlobBytes + expectedArgfileBytes);

            // Every cell's own files still land at its own distinct prefix, with correct content --
            // dedup must not mean "some cells silently missed their copy".
            for (Cell cell : cells) {
                String stagingPath = StagingLayout.defaults()
                        .stagingPath("build-concurrent", cell.buildKind(), cell.architecture());
                assertThat(getObjectAsString(stagingPath + "/app.jar")).isEqualTo(appContent);
                assertThat(getObjectAsString(stagingPath + "/lib/dep.jar")).isEqualTo(dependencyContent);
                assertThat(getObjectAsString(stagingPath + "/native-image.args"))
                        .isEqualTo(cell.argfileContent());
            }

            // The underlying CAS blobs themselves are real, singular objects with the expected
            // content -- not four half-written or conflicting copies under one key.
            String dependencyCasKey =
                    StagingLayout.defaults().casKey(sha256Hex(dependencyContent));
            String appCasKey = StagingLayout.defaults().casKey(sha256Hex(appContent));
            assertThat(getObjectAsString(dependencyCasKey)).isEqualTo(dependencyContent);
            assertThat(getObjectAsString(appCasKey)).isEqualTo(appContent);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retrievesAnExpectedArtifactByName(@TempDir Path destinationDir) {
        String key = "builds/build-3/native/x86_64/output/my-app";
        s3Client.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString("binary-content"));

        S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, BUCKET);
        List<Path> downloaded = assertDoesNotThrow(() -> retriever.retrieve("build-3",
                ai.codriverlabs.scaleoutbuild.build.BuildKind.NATIVE, Architecture.X86_64,
                List.of("my-app"), destinationDir));

        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0)).exists().hasContent("binary-content");
    }

    @Test
    void retrievesByScanningWhenNoExpectedNamesAreGiven(@TempDir Path destinationDir) {
        String key = "builds/build-4/native/arm64/output/self-named-binary";
        s3Client.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString("arm64-binary"));

        S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, BUCKET);
        List<Path> downloaded = assertDoesNotThrow(() -> retriever.retrieve("build-4",
                ai.codriverlabs.scaleoutbuild.build.BuildKind.NATIVE, Architecture.ARM64, List.of(),
                destinationDir));

        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0).getFileName().toString()).isEqualTo("self-named-binary");
        assertThat(downloaded.get(0)).hasContent("arm64-binary");
    }

    @Test
    void retrievingTheSameArtifactTwiceIntoTheSameDestinationDirectoryDoesNotFail(
            @TempDir Path destinationDir) {
        // Regression test: S3Client#getObject(request, Path) throws if the destination file
        // already exists (it does not overwrite, unlike `aws s3api get-object` or Transfer
        // Manager's download) -- a real local `mvn package` re-run against a target/ directory
        // left over from a previous successful run hit exactly this, surfaced as a misleadingly
        // opaque "could not be downloaded" MojoFailureException with no real cause detail.
        String key = "builds/build-5/native/x86_64/output/my-app";
        s3Client.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString("first-run-content"));

        S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, BUCKET);
        List<Path> firstDownload = assertDoesNotThrow(() -> retriever.retrieve("build-5",
                ai.codriverlabs.scaleoutbuild.build.BuildKind.NATIVE, Architecture.X86_64,
                List.of("my-app"), destinationDir));
        assertThat(firstDownload).hasSize(1);
        assertThat(firstDownload.get(0)).hasContent("first-run-content");

        // A second, later build overwrites the same object with new content (a real rebuild would
        // produce a genuinely different binary) and is retrieved into the very same destination
        // directory -- the second retrieve() must succeed and reflect the new content, not fail
        // because the old file from the first retrieve() is still sitting there.
        s3Client.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString("second-run-content"));
        List<Path> secondDownload = assertDoesNotThrow(() -> retriever.retrieve("build-5",
                ai.codriverlabs.scaleoutbuild.build.BuildKind.NATIVE, Architecture.X86_64,
                List.of("my-app"), destinationDir));
        assertThat(secondDownload).hasSize(1);
        assertThat(secondDownload.get(0)).hasContent("second-run-content");
    }

    private static void assertObjectExists(String key) {
        assertThat(s3Client.headObject(b -> b.bucket(BUCKET).key(key)).contentLength()).isNotNegative();
    }

    private static String getObjectAsString(String key) {
        return s3Client.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asUtf8String();
    }

    /** Mirrors {@code S3StagingSink}'s own content-hash computation, so the CAS key this test
     * checks is derived the same way the sink itself derives it, not independently guessed. */
    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static <T> T assertDoesNotThrow(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw new AssertionError("Expected no exception, but got: " + e, e);
        }
    }
}

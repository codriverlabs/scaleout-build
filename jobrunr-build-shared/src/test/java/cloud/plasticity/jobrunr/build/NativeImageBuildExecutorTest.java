/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the executor against a fake {@code native-image} shell script rather than a real GraalVM
 * distribution, so these tests run in any JDK-only environment. Skipped on platforms without
 * {@code /bin/sh} (i.e. non-POSIX), since the fake script is a shell script.
 */
class NativeImageBuildExecutorTest {

    @BeforeEach
    void requiresPosixShell() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires a POSIX shell to run the fake tool");
    }

    @Test
    void runsTheConfiguredCommandAndCollectsTheProducedArtifact(@TempDir Path mountRoot) throws Exception {
        Path fakeNativeImage = writeFakeNativeImage(mountRoot, 0, "output/app");
        BuildEnvironment environment = BuildEnvironment.builder(mountRoot)
                .nativeImageCommand(List.of(fakeNativeImage.toString()))
                .build();
        NativeImageBuildExecutor executor = new NativeImageBuildExecutor(environment);

        String stagingRelativePath = "builds/b1/x86_64";
        Path stagingRoot = mountRoot.resolve(stagingRelativePath);
        Files.createDirectories(stagingRoot);
        Files.writeString(stagingRoot.resolve("native-image.args"), "-o\noutput/app\n");

        BuildJobRequest request = BuildJobRequest.builder()
                .buildId("b1")
                .architecture(Architecture.host().orElse(Architecture.X86_64))
                .stagingRelativePath(stagingRelativePath)
                .expectedArtifacts(List.of("app"))
                .build();

        BuildResult result = executor.execute(request, BuildLog.discarding());

        assertThat(result.exitCode()).isZero();
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0)).exists();
    }

    @Test
    void raisesBuildFailedExceptionOnNonZeroExit(@TempDir Path mountRoot) throws IOException {
        Path fakeNativeImage = writeFakeNativeImage(mountRoot, 1, null);
        BuildEnvironment environment = BuildEnvironment.builder(mountRoot)
                .nativeImageCommand(List.of(fakeNativeImage.toString()))
                .build();
        NativeImageBuildExecutor executor = new NativeImageBuildExecutor(environment);

        String stagingRelativePath = "builds/b2/x86_64";
        Path stagingRoot = mountRoot.resolve(stagingRelativePath);
        Files.createDirectories(stagingRoot);
        Files.writeString(stagingRoot.resolve("native-image.args"), "-o\noutput/app\n");

        BuildJobRequest request = BuildJobRequest.builder()
                .buildId("b2")
                .architecture(Architecture.host().orElse(Architecture.X86_64))
                .stagingRelativePath(stagingRelativePath)
                .build();

        assertThatThrownBy(() -> executor.execute(request, BuildLog.discarding()))
                .isInstanceOf(BuildFailedException.class)
                .hasMessageContaining("exit code 1");
    }

    @Test
    void raisesBuildFailedExceptionWhenTheArgfileIsMissing(@TempDir Path mountRoot) throws IOException {
        BuildEnvironment environment = BuildEnvironment.builder(mountRoot).build();
        NativeImageBuildExecutor executor = new NativeImageBuildExecutor(environment);

        String stagingRelativePath = "builds/b3/x86_64";
        Files.createDirectories(mountRoot.resolve(stagingRelativePath));

        BuildJobRequest request = BuildJobRequest.builder()
                .buildId("b3")
                .architecture(Architecture.host().orElse(Architecture.X86_64))
                .stagingRelativePath(stagingRelativePath)
                .build();

        assertThatThrownBy(() -> executor.execute(request, BuildLog.discarding()))
                .isInstanceOf(BuildFailedException.class)
                .hasMessageContaining("Argument file not found");
    }

    /** Writes a shell script that exits with {@code exitCode} and optionally creates a binary. */
    private static Path writeFakeNativeImage(Path baseDir, int exitCode, String outputRelativePath)
            throws IOException {
        Path script = baseDir.resolve("fake-native-image.sh");
        StringBuilder body = new StringBuilder("#!/bin/sh\nset -e\n");
        if (outputRelativePath != null) {
            body.append("mkdir -p \"$(dirname '").append(outputRelativePath).append("')\"\n");
            body.append("printf 'binary' > '").append(outputRelativePath).append("'\n");
        }
        body.append("exit ").append(exitCode).append('\n');
        Files.writeString(script, body.toString());
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return script;
    }
}

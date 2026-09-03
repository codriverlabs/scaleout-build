/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One matrix cell's build: the unit of work run either directly (local-first) or inside a Fargate
 * task launched by the Step Functions state machine's {@code RunTask.sync} state.
 *
 * <p>Deliberately plain data with no framework coupling — the plugin constructs one directly for a
 * local-first build, and serialises an equivalent set of fields into ECS task-override environment
 * variables for a remote one. See {@code docs/DESIGN.md} §5 for the task-override contract and §6
 * for the agent's env var names.
 */
public final class BuildCellRequest {

    private String buildId;
    private BuildKind buildKind;
    private Architecture architecture;
    private String stagingRelativePath;
    private String argFileName = StagingLayout.DEFAULT_ARGS_FILE_NAME;
    private String profileRelativePath;
    private List<String> expectedArtifacts = new ArrayList<>();
    private List<String> extraNativeImageArgs = new ArrayList<>();
    private String moduleSelector;
    private int timeoutMinutes;

    private BuildCellRequest(Builder builder) {
        this.buildId = builder.buildId;
        this.buildKind = builder.buildKind;
        this.architecture = builder.architecture;
        this.stagingRelativePath = builder.stagingRelativePath;
        this.argFileName = builder.argFileName;
        this.profileRelativePath = builder.profileRelativePath;
        this.expectedArtifacts = List.copyOf(builder.expectedArtifacts);
        this.extraNativeImageArgs = List.copyOf(builder.extraNativeImageArgs);
        this.moduleSelector = builder.moduleSelector;
        this.timeoutMinutes = builder.timeoutMinutes;
    }

    /** Identifier shared by every cell of one plugin invocation. */
    public String getBuildId() {
        return buildId;
    }

    /** Which build kind this cell produces. */
    public BuildKind getBuildKind() {
        return buildKind;
    }

    /**
     * Target architecture; {@code null} for {@link BuildKind#JVM}, asserted against the host before
     * a native-image build starts.
     */
    public Architecture getArchitecture() {
        return architecture;
    }

    /**
     * Staging root relative to the worker's mount root. Also the working directory for
     * {@code native-image}, which is what makes the argfile's relative paths portable between a
     * local run and a remote one.
     */
    public String getStagingRelativePath() {
        return stagingRelativePath;
    }

    /** Name of the native-image argument file inside the staging root. */
    public String getArgFileName() {
        return argFileName;
    }

    /**
     * Path to the {@code .iprof} profile inside the staging root, relative like every other path
     * here. Only present for {@link BuildKind#NATIVE_PGO_OPTIMIZE}.
     */
    public String getProfileRelativePath() {
        return profileRelativePath;
    }

    /**
     * Artifact file names the build is expected to produce, searched for in the staging root and its
     * {@code output/} directory. May be empty, in which case produced binaries are discovered by
     * scanning.
     */
    public List<String> getExpectedArtifacts() {
        return expectedArtifacts;
    }

    /** Arguments appended after the argfile reference and any build-kind-specific flags. */
    public List<String> getExtraNativeImageArgs() {
        return extraNativeImageArgs;
    }

    /** Reserved for future per-module fan-out; {@code null} means "the whole project". */
    public String getModuleSelector() {
        return moduleSelector;
    }

    /** Soft timeout applied to the {@code native-image} process; 0 means no timeout. */
    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    @Override
    public String toString() {
        return "BuildCellRequest{buildId=" + buildId
                + ", buildKind=" + buildKind
                + ", architecture=" + architecture
                + ", stagingRelativePath=" + stagingRelativePath
                + ", argFileName=" + argFileName
                + ", profileRelativePath=" + profileRelativePath
                + ", moduleSelector=" + moduleSelector
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; validates the fields a build cannot recover from being absent. */
    public static final class Builder {
        private String buildId;
        private BuildKind buildKind;
        private Architecture architecture;
        private String stagingRelativePath;
        private String argFileName = StagingLayout.DEFAULT_ARGS_FILE_NAME;
        private String profileRelativePath;
        private List<String> expectedArtifacts = List.of();
        private List<String> extraNativeImageArgs = List.of();
        private String moduleSelector;
        private int timeoutMinutes;

        public Builder buildId(String buildId) {
            this.buildId = buildId;
            return this;
        }

        public Builder buildKind(BuildKind buildKind) {
            this.buildKind = buildKind;
            return this;
        }

        public Builder architecture(Architecture architecture) {
            this.architecture = architecture;
            return this;
        }

        public Builder stagingRelativePath(String stagingRelativePath) {
            this.stagingRelativePath = stagingRelativePath;
            return this;
        }

        public Builder argFileName(String argFileName) {
            this.argFileName = argFileName;
            return this;
        }

        public Builder profileRelativePath(String profileRelativePath) {
            this.profileRelativePath = profileRelativePath;
            return this;
        }

        public Builder expectedArtifacts(List<String> expectedArtifacts) {
            this.expectedArtifacts = expectedArtifacts == null ? List.of() : expectedArtifacts;
            return this;
        }

        public Builder extraNativeImageArgs(List<String> extraNativeImageArgs) {
            this.extraNativeImageArgs =
                    extraNativeImageArgs == null ? List.of() : extraNativeImageArgs;
            return this;
        }

        public Builder moduleSelector(String moduleSelector) {
            this.moduleSelector = moduleSelector;
            return this;
        }

        public Builder timeoutMinutes(int timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
            return this;
        }

        public BuildCellRequest build() {
            Objects.requireNonNull(buildId, "buildId");
            Objects.requireNonNull(buildKind, "buildKind");
            if (buildKind.requiresArchitecture()) {
                Objects.requireNonNull(architecture,
                        "architecture is required for build kind " + buildKind);
            }
            if (buildKind.requiresProfile() && (profileRelativePath == null
                    || profileRelativePath.isBlank())) {
                throw new IllegalArgumentException(
                        "profileRelativePath is required for build kind " + buildKind);
            }
            Objects.requireNonNull(stagingRelativePath, "stagingRelativePath");
            if (argFileName == null || argFileName.isBlank()) {
                throw new IllegalArgumentException("argFileName must not be blank");
            }
            if (timeoutMinutes < 0) {
                throw new IllegalArgumentException("timeoutMinutes must not be negative");
            }
            return new BuildCellRequest(this);
        }
    }
}

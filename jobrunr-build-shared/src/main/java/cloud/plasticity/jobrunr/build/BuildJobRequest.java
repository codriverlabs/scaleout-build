/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jobrunr.jobs.lambdas.JobRequest;
import org.jobrunr.jobs.lambdas.JobRequestHandler;

/**
 * One remote native-image build: the unit of work enqueued into JobRunr.
 *
 * <p>JobRunr serialises job arguments to JSON and stores them in the database, so this payload
 * stays deliberately small. Build inputs travel out of band through the staging area and are
 * referenced here only by relative path.
 *
 * <p>{@link #getModuleSelector()} is reserved: today one job builds one architecture for the whole
 * project, but carrying the field from the start means per-module fan-out can be added later without
 * changing the stored payload shape.
 *
 * <p>Mutable with a no-argument constructor because JobRunr's JSON mapper instantiates it
 * reflectively on the worker side.
 */
public class BuildJobRequest implements JobRequest {

    private String buildId;
    private Architecture architecture;
    private String stagingRelativePath;
    private String argFileName = StagingLayout.DEFAULT_ARGS_FILE_NAME;
    private List<String> expectedArtifacts = new ArrayList<>();
    private List<String> extraNativeImageArgs = new ArrayList<>();
    private String moduleSelector;
    private int timeoutMinutes;

    public BuildJobRequest() {
        // for JSON deserialization
    }

    private BuildJobRequest(Builder builder) {
        this.buildId = builder.buildId;
        this.architecture = builder.architecture;
        this.stagingRelativePath = builder.stagingRelativePath;
        this.argFileName = builder.argFileName;
        this.expectedArtifacts = List.copyOf(builder.expectedArtifacts);
        this.extraNativeImageArgs = List.copyOf(builder.extraNativeImageArgs);
        this.moduleSelector = builder.moduleSelector;
        this.timeoutMinutes = builder.timeoutMinutes;
    }

    @Override
    public Class<? extends JobRequestHandler> getJobRequestHandler() {
        return BuildJobRequestHandler.class;
    }

    /** Identifier shared by all architectures of one plugin invocation. */
    public String getBuildId() {
        return buildId;
    }

    public void setBuildId(String buildId) {
        this.buildId = buildId;
    }

    /** Target architecture; asserted against the host before the build starts. */
    public Architecture getArchitecture() {
        return architecture;
    }

    public void setArchitecture(Architecture architecture) {
        this.architecture = architecture;
    }

    /**
     * Staging root relative to the worker's mount root. Also the working directory for
     * {@code native-image}, which is what makes the argfile's relative paths portable between a
     * local run and the container.
     */
    public String getStagingRelativePath() {
        return stagingRelativePath;
    }

    public void setStagingRelativePath(String stagingRelativePath) {
        this.stagingRelativePath = stagingRelativePath;
    }

    /** Name of the native-image argument file inside the staging root. */
    public String getArgFileName() {
        return argFileName;
    }

    public void setArgFileName(String argFileName) {
        this.argFileName = argFileName;
    }

    /**
     * Artifact file names the build is expected to produce, searched for in the staging root and its
     * {@code output/} directory. May be empty, in which case produced binaries are discovered by
     * scanning.
     */
    public List<String> getExpectedArtifacts() {
        return expectedArtifacts;
    }

    public void setExpectedArtifacts(List<String> expectedArtifacts) {
        this.expectedArtifacts = expectedArtifacts == null ? new ArrayList<>() : expectedArtifacts;
    }

    /** Arguments appended after the argfile reference, e.g. diagnostics or memory limits. */
    public List<String> getExtraNativeImageArgs() {
        return extraNativeImageArgs;
    }

    public void setExtraNativeImageArgs(List<String> extraNativeImageArgs) {
        this.extraNativeImageArgs =
                extraNativeImageArgs == null ? new ArrayList<>() : extraNativeImageArgs;
    }

    /** Reserved for future per-module fan-out; {@code null} means "the whole project". */
    public String getModuleSelector() {
        return moduleSelector;
    }

    public void setModuleSelector(String moduleSelector) {
        this.moduleSelector = moduleSelector;
    }

    /** Soft timeout applied to the {@code native-image} process; 0 means no timeout. */
    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    @Override
    public String toString() {
        return "BuildJobRequest{buildId=" + buildId
                + ", architecture=" + architecture
                + ", stagingRelativePath=" + stagingRelativePath
                + ", argFileName=" + argFileName
                + ", moduleSelector=" + moduleSelector
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; validates the fields the worker cannot recover from being absent. */
    public static final class Builder {
        private String buildId;
        private Architecture architecture;
        private String stagingRelativePath;
        private String argFileName = StagingLayout.DEFAULT_ARGS_FILE_NAME;
        private List<String> expectedArtifacts = List.of();
        private List<String> extraNativeImageArgs = List.of();
        private String moduleSelector;
        private int timeoutMinutes;

        public Builder buildId(String buildId) {
            this.buildId = buildId;
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

        public BuildJobRequest build() {
            Objects.requireNonNull(buildId, "buildId");
            Objects.requireNonNull(architecture, "architecture");
            Objects.requireNonNull(stagingRelativePath, "stagingRelativePath");
            if (argFileName == null || argFileName.isBlank()) {
                throw new IllegalArgumentException("argFileName must not be blank");
            }
            if (timeoutMinutes < 0) {
                throw new IllegalArgumentException("timeoutMinutes must not be negative");
            }
            return new BuildJobRequest(this);
        }
    }
}

/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.store;

import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The persisted form of a build. Deliberately separate from the API's {@code BuildStatus} so the
 * wire contract and the storage schema can change independently.
 *
 * <p>Build <em>state</em> lives here; build <em>logs</em> stay in CloudWatch Logs. That separation is
 * what keeps status lookups cheap and lets the log stream be resumed independently of it.
 */
public final class BuildRecord {

    private String buildId;
    private String ownerKey;
    private String ownerArn;
    private boolean ownerPerHuman;
    private BuildState state = BuildState.PENDING;
    private BuildSpec buildSpec;
    private List<InputDescriptor> inputs = new ArrayList<>();
    private List<CellRecord> cells = new ArrayList<>();
    private String appliedCpu;
    private String appliedMemory;
    private int appliedEphemeralStorageGiB;
    private int overallTimeoutMinutes;
    private Instant createdAt;
    private Instant startedAt;
    private Instant updatedAt;
    private Instant lastHeartbeatAt;
    private Instant expiresAt;
    private long ttl;

    /** One matrix cell's persisted state. */
    public static final class CellRecord {
        private String cell;
        private CellState state = CellState.PENDING;
        private String taskArn;
        private String taskDefinitionArn;
        private Integer exitCode;
        private String failureReason;
        private int spotInterruptions;
        private List<String> artifactPaths = new ArrayList<>();

        public String getCell() {
            return cell;
        }

        public void setCell(String cell) {
            this.cell = cell;
        }

        public CellState getState() {
            return state;
        }

        public void setState(CellState state) {
            this.state = state;
        }

        public String getTaskArn() {
            return taskArn;
        }

        public void setTaskArn(String taskArn) {
            this.taskArn = taskArn;
        }

        public String getTaskDefinitionArn() {
            return taskDefinitionArn;
        }

        public void setTaskDefinitionArn(String taskDefinitionArn) {
            this.taskDefinitionArn = taskDefinitionArn;
        }

        public Integer getExitCode() {
            return exitCode;
        }

        public void setExitCode(Integer exitCode) {
            this.exitCode = exitCode;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public void setFailureReason(String failureReason) {
            this.failureReason = failureReason;
        }

        public int getSpotInterruptions() {
            return spotInterruptions;
        }

        public void setSpotInterruptions(int spotInterruptions) {
            this.spotInterruptions = spotInterruptions;
        }

        public List<String> getArtifactPaths() {
            return artifactPaths;
        }

        public void setArtifactPaths(List<String> artifactPaths) {
            this.artifactPaths = artifactPaths == null ? new ArrayList<>() : artifactPaths;
        }
    }

    public String getBuildId() {
        return buildId;
    }

    public void setBuildId(String buildId) {
        this.buildId = buildId;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey) {
        this.ownerKey = ownerKey;
    }

    public String getOwnerArn() {
        return ownerArn;
    }

    public void setOwnerArn(String ownerArn) {
        this.ownerArn = ownerArn;
    }

    public boolean isOwnerPerHuman() {
        return ownerPerHuman;
    }

    public void setOwnerPerHuman(boolean ownerPerHuman) {
        this.ownerPerHuman = ownerPerHuman;
    }

    public BuildState getState() {
        return state;
    }

    public void setState(BuildState state) {
        this.state = state;
    }

    public BuildSpec getBuildSpec() {
        return buildSpec;
    }

    public void setBuildSpec(BuildSpec buildSpec) {
        this.buildSpec = buildSpec;
    }

    public List<InputDescriptor> getInputs() {
        return inputs;
    }

    public void setInputs(List<InputDescriptor> inputs) {
        this.inputs = inputs == null ? new ArrayList<>() : inputs;
    }

    public List<CellRecord> getCells() {
        return cells;
    }

    public void setCells(List<CellRecord> cells) {
        this.cells = cells == null ? new ArrayList<>() : cells;
    }

    public String getAppliedCpu() {
        return appliedCpu;
    }

    public void setAppliedCpu(String appliedCpu) {
        this.appliedCpu = appliedCpu;
    }

    public String getAppliedMemory() {
        return appliedMemory;
    }

    public void setAppliedMemory(String appliedMemory) {
        this.appliedMemory = appliedMemory;
    }

    public int getAppliedEphemeralStorageGiB() {
        return appliedEphemeralStorageGiB;
    }

    public void setAppliedEphemeralStorageGiB(int appliedEphemeralStorageGiB) {
        this.appliedEphemeralStorageGiB = appliedEphemeralStorageGiB;
    }

    public int getOverallTimeoutMinutes() {
        return overallTimeoutMinutes;
    }

    public void setOverallTimeoutMinutes(int overallTimeoutMinutes) {
        this.overallTimeoutMinutes = overallTimeoutMinutes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getTtl() {
        return ttl;
    }

    public void setTtl(long ttl) {
        this.ttl = ttl;
    }

    /** @return the cell with this identifier, or {@code null} */
    public CellRecord cell(String cellId) {
        return cells.stream().filter(c -> cellId.equals(c.getCell())).findFirst().orElse(null);
    }

    /** @return whether every cell has reached a terminal state */
    public boolean allCellsTerminal() {
        return !cells.isEmpty() && cells.stream().allMatch(c -> c.getState().isTerminal());
    }
}

/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli.command;

import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.client.ControlPlaneClientException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code scaleout builds} — build lifecycle subcommands.
 *
 * <p>All subcommands read the endpoint and region from the parent {@code ScaleoutCli} options.
 */
@Command(
        name = "builds",
        description = "Manage native-image builds",
        subcommands = {
            BuildsCommand.ListCommand.class,
            BuildsCommand.StatusCommand.class,
            BuildsCommand.CancelCommand.class,
        },
        mixinStandardHelpOptions = true)
public final class BuildsCommand implements Callable<Integer> {

    @ParentCommand
    ai.codriverlabs.scaleoutbuild.cli.ScaleoutCli parent;

    @Override
    public Integer call() {
        new picocli.CommandLine(this).usage(System.out);
        return 0;
    }

    // ── list ─────────────────────────────────────────────────────────────────────────────────

    @Command(name = "list", description = "List your most recent builds", mixinStandardHelpOptions = true)
    static final class ListCommand implements Callable<Integer> {

        @ParentCommand
        BuildsCommand builds;

        @Option(names = {"--limit", "-n"}, description = "Maximum results (default: ${DEFAULT-VALUE})",
                defaultValue = "20")
        int limit;

        @Override
        public Integer call() throws ControlPlaneClientException {
            List<BuildStatus> statuses = builds.parent.client().listBuilds(limit);
            System.out.println(prettyOrRaw(statuses));
            return 0;
        }
    }

    // ── status ───────────────────────────────────────────────────────────────────────────────

    @Command(name = "status", description = "Get the status of a build", mixinStandardHelpOptions = true)
    static final class StatusCommand implements Callable<Integer> {

        @ParentCommand
        BuildsCommand builds;

        @picocli.CommandLine.Parameters(index = "0", description = "Build ID")
        String buildId;

        @Override
        public Integer call() throws ControlPlaneClientException {
            BuildStatus status = builds.parent.client().status(buildId);
            System.out.println(prettyOrRaw(status));
            return 0;
        }
    }

    // ── cancel ───────────────────────────────────────────────────────────────────────────────

    @Command(name = "cancel", description = "Cancel a running build", mixinStandardHelpOptions = true)
    static final class CancelCommand implements Callable<Integer> {

        @ParentCommand
        BuildsCommand builds;

        @picocli.CommandLine.Parameters(index = "0", description = "Build ID")
        String buildId;

        @Override
        public Integer call() throws ControlPlaneClientException {
            BuildStatus status = builds.parent.client().cancel(buildId);
            System.out.println(prettyOrRaw(status));
            return 0;
        }
    }

    // ── shared utility ───────────────────────────────────────────────────────────────────────

    /*
     * Configured identically to the client's internal mapper (JavaTimeModule, lenient
     * deserialization isn't needed here since we only ever serialize, not parse). Kept as its own
     * instance rather than exposing the client's internal mapper: pretty-printing is a CLI display
     * concern, not something the client should have an opinion about.
     */
    private static final ObjectMapper PRETTY = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    static String prettyOrRaw(Object dto) {
        try {
            return PRETTY.writeValueAsString(dto);
        } catch (Exception e) {
            return String.valueOf(dto);
        }
    }
}

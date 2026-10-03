/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli.command;

import ai.codriverlabs.scaleoutbuild.cli.CliException;
import ai.codriverlabs.scaleoutbuild.cli.ControlPlaneHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
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
        public Integer call() throws CliException {
            String json = builds.parent.client().listBuilds(limit);
            System.out.println(prettyOrRaw(json));
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
        public Integer call() throws CliException {
            String json = builds.parent.client().getBuild(buildId);
            System.out.println(prettyOrRaw(json));
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
        public Integer call() throws CliException {
            String json = builds.parent.client().cancelBuild(buildId);
            System.out.println(prettyOrRaw(json));
            return 0;
        }
    }

    // ── shared utility ───────────────────────────────────────────────────────────────────────

    static String prettyOrRaw(String json) {
        try {
            JsonNode node = ControlPlaneHttpClient.MAPPER.readTree(json);
            return ControlPlaneHttpClient.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return json;
        }
    }
}

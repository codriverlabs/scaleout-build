/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli.command;

import ai.codriverlabs.scaleoutbuild.cli.CliException;
import ai.codriverlabs.scaleoutbuild.cli.ControlPlaneHttpClient;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * {@code scaleout images} — MicroVM agent image subcommands.
 *
 * <p>Requires the caller's IAM role to be in the configured admin role list
 * ({@code SCALEOUT_MICROVM_ADMIN_ROLE_ARNS}). Non-admin callers receive 404.
 */
@Command(
        name = "images",
        description = "Manage Lambda MicroVM agent images (admin only)",
        subcommands = {
            ImagesCommand.BuildCommand.class,
            ImagesCommand.StatusCommand.class,
        },
        mixinStandardHelpOptions = true)
public final class ImagesCommand implements Callable<Integer> {

    @ParentCommand
    ai.codriverlabs.scaleoutbuild.cli.ScaleoutCli parent;

    @Override
    public Integer call() {
        new picocli.CommandLine(this).usage(System.out);
        return 0;
    }

    // ── build ────────────────────────────────────────────────────────────────────────────────

    @Command(
            name = "build",
            description = {
                "Trigger a new MicroVM agent image build.",
                "The code artifact ZIP must already be in S3 before calling this.",
                "The build takes ~10–15 minutes; poll 'images status' until state is CREATED."
            },
            mixinStandardHelpOptions = true)
    static final class BuildCommand implements Callable<Integer> {

        @ParentCommand
        ImagesCommand images;

        @Option(
                names = {"--artifact", "-a"},
                description = "S3 URI of the code artifact ZIP, e.g. s3://bucket/agent.zip",
                required = true)
        String artifactS3Uri;

        @Override
        public Integer call() throws CliException {
            String json = images.parent.client().triggerImageBuild(artifactS3Uri);
            System.out.println(BuildsCommand.prettyOrRaw(json));

            // Parse the returned imageVersion for convenience
            try {
                var node = ControlPlaneHttpClient.MAPPER.readTree(json);
                String version = node.path("imageVersion").asText(null);
                String state = node.path("state").asText("?");
                if (version != null) {
                    System.err.println("\nImage build submitted (version " + version + ", state " + state + ")");
                    System.err.println("Poll: scaleout images status scaleout-build-agent");
                }
            } catch (Exception ignored) {}
            return 0;
        }
    }

    // ── status ───────────────────────────────────────────────────────────────────────────────

    @Command(
            name = "status",
            description = "Get the current status of a MicroVM agent image.",
            mixinStandardHelpOptions = true)
    static final class StatusCommand implements Callable<Integer> {

        @ParentCommand
        ImagesCommand images;

        @Parameters(
                index = "0",
                description = "Image name or ARN (default: scaleout-build-agent)",
                defaultValue = "scaleout-build-agent")
        String imageIdentifier;

        @Override
        public Integer call() throws CliException {
            String json = images.parent.client().getImageStatus(imageIdentifier);
            System.out.println(BuildsCommand.prettyOrRaw(json));
            return 0;
        }
    }
}

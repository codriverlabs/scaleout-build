/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli;

import ai.codriverlabs.scaleoutbuild.cli.command.BuildsCommand;
import ai.codriverlabs.scaleoutbuild.cli.command.ImagesCommand;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.Option;

/**
 * Entry point for the scaleout-build CLI.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * # Endpoint from SSM (recommended):
 * EP=$(aws ssm get-parameter --name /scaleout-build/control-plane/endpoint \
 *   --region eu-central-1 --query 'Parameter.Value' --output text)
 *
 * scaleout --endpoint "$EP" --region eu-central-1 builds list
 * scaleout --endpoint "$EP" --region eu-central-1 builds status 1789867513606-abc123
 * scaleout --endpoint "$EP" --region eu-central-1 builds cancel 1789867513606-abc123
 * scaleout --endpoint "$EP" --region eu-central-1 images build --artifact s3://bucket/agent.zip
 * scaleout --endpoint "$EP" --region eu-central-1 images status
 * </pre>
 *
 * <p>The endpoint defaults to {@code SCALEOUT_ENDPOINT} and the region to {@code AWS_REGION}, so
 * both can be set in the environment to avoid repeating them.
 */
@Command(
        name = "scaleout",
        description = "scaleout-build control plane CLI",
        version = "scaleout-build-cli 1.0.1",
        subcommands = {BuildsCommand.class, ImagesCommand.class},
        mixinStandardHelpOptions = true)
public class ScaleoutCli implements Callable<Integer> {

    @Option(
            names = {"--endpoint", "-e"},
            description = "Control plane endpoint URL. Default: $SCALEOUT_ENDPOINT",
            defaultValue = "${SCALEOUT_ENDPOINT:}",
            required = false)
    String endpoint;

    @Option(
            names = {"--region", "-r"},
            description = "AWS region for SigV4 signing. Default: $AWS_REGION",
            defaultValue = "${AWS_REGION:-eu-central-1}")
    String region;

    @Option(
            names = {"--json"},
            description = "Print raw JSON without pretty-printing")
    boolean rawJson;

    private ControlPlaneHttpClient lazyClient;

    /**
     * Lazily constructs the HTTP client. Called by subcommands after picocli has bound the options.
     */
    public ControlPlaneHttpClient client() {
        if (lazyClient == null) {
            if (endpoint == null || endpoint.isBlank()) {
                throw new picocli.CommandLine.ParameterException(
                        new picocli.CommandLine(this),
                        "Missing required option '--endpoint' (or set $SCALEOUT_ENDPOINT)");
            }
            lazyClient = new ControlPlaneHttpClient(endpoint, region);
        }
        return lazyClient;
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new ScaleoutCli());
        cmd.setExecutionExceptionHandler(errorHandler());
        int exit = cmd.execute(args);
        System.exit(exit);
    }

    /**
     * Formats {@link CliException} as a single-line error rather than a stack trace.
     * Network errors and HTTP errors both surface with just their message.
     */
    private static IExecutionExceptionHandler errorHandler() {
        return (ex, commandLine, parseResult) -> {
            if (ex instanceof CliException ce) {
                System.err.println("error: " + ce.getMessage());
            } else {
                System.err.println("error: " + ex.getMessage());
                ex.printStackTrace(System.err);
            }
            return 1;
        };
    }
}

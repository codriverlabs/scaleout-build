# User Guide: Quick Start

Get a two-architecture native build running in about 5 minutes.

> **Before you start**: Replace the following placeholders throughout this guide:
>
> | Placeholder | Replace with |
> |-------------|-------------|
> | `https://REPLACE.lambda-url.eu-west-1.on.aws/` | Your control plane endpoint (ask whoever deployed it, or `aws ssm get-parameter --name /scaleout-build/control-plane/endpoint --query Parameter.Value --output text`) |
> | `eu-west-1` | Your AWS region |
> | `YOUR_GITHUB_USERNAME` | Your GitHub username |
> | `my-app` | Your binary name |

## Prerequisites

- **A deployed control plane.** Someone in your organisation runs it, once per team. If nobody has,
  see [Installing the control plane](../../README.md#installation) — it is one command.
- **AWS credentials with exactly two permissions** on that function, and nothing else:
  ```
  lambda:InvokeFunctionUrl
  lambda:InvokeFunction
  ```
  No ECS, S3, CloudWatch or ECR access. If you were previously granted those for native builds, they can be
  revoked.
- **Java 17+** and Maven. You do **not** need GraalVM or Mandrel — the remote workers bring their own
  compiler.

## Step 1: Add the plugin

```xml
<build>
  <plugins>
    <plugin>
      <groupId>ai.codriverlabs</groupId>
      <artifactId>scaleout-build-maven-plugin</artifactId>
      <version><!-- latest at github.com/codriverlabs/scaleout-build/releases --></version>
      <executions>
        <execution>
          <phase>package</phase>
          <goals><goal>build</goal></goals>
        </execution>
      </executions>
      <configuration>
        <buildKinds>
          <buildKind>native</buildKind>
        </buildKinds>
        <architectures>
          <architecture>x86_64</architecture>
          <architecture>arm64</architecture>
        </architectures>
        <imageName>my-app</imageName>
        <endpoint>https://REPLACE.lambda-url.eu-west-1.on.aws/</endpoint>
      </configuration>
    </plugin>
  </plugins>
</build>
```

**Keep the endpoint out of a shared POM.** Read it from a gitignored properties file instead — see
[the example app](../examples/scaleout-build-example-app) for the pattern, which binds
`properties-maven-plugin` to the `validate` phase.

## Step 2: Make your framework emit its arguments

Skip this step for a plain GraalVM project. Otherwise one extra command, because the framework normally runs
the compile itself and here it must hand over the arguments instead:

```bash
# Quarkus
mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true

# Spring Boot AOT, or Helidon
mvn -Pnative package
mvn native:write-args-file
```

Full detail, including what is verified against which versions, in
[Framework support](frameworks.md).

## Step 3: Build

```bash
mvn package
```

For Spring Boot and Helidon, tell the plugin where the argfile landed:

```bash
mvn scaleout-build:build -Dscaleout-build.argsFileDirectory=target
```

The matrix cell matching your host builds locally; the rest go to remote workers, one per cell, in parallel.
Logs from every cell stream back into your Maven output.

## Step 4: Collect

```bash
ls target/scaleout-build/remote-artifacts/
# NATIVE-ARM64  NATIVE-X86_64

file target/scaleout-build/remote-artifacts/NATIVE-ARM64/my-app
# ELF 64-bit LSB executable, ARM aarch64, version 1 (SYSV), dynamically linked
```

Artifacts are also attached to the reactor with per-architecture classifiers, so
`mvn install` or `deploy` publishes them alongside your jar:

```
my-app-1.0.0-native-linux-arm64-my-app
my-app-1.0.0-native-linux-x86_64-my-app
```

## Tear down

Nothing to tear down on your side — remote workers exist only while a build runs and stop on their own.

To stop using the plugin, remove it from your POM. To remove the shared control plane, see
[Uninstalling](../../README.md#uninstalling); note that the S3 bucket and DynamoDB table are retained
deliberately and must be deleted by hand.

## Next steps

| | |
|---|---|
| Your project is Spring Boot, Helidon or Quarkus | [Framework support](frameworks.md) |
| You are running inside a hosted agent sandbox | [Agent sandboxes](agent-sandboxes.md) |
| You need to tune workers, timeouts or capacity | [Configuration reference](configuration.md) |
| A build failed | [Troubleshooting](troubleshooting.md) |
| You want to know what a build costs | [`COST_ANALYSIS.md`](../COST_ANALYSIS.md) |

# scaleout-build-maven-plugin example app

> **Setup below is out of date.** It describes the direct-ECS path, which has been removed along with
> `scaleout-test-infra`. This example is migrated to the control plane in migration step 4: the 22
> `aws-ecs.*` parameters go away and `deployment.properties` becomes a single
> `scaleout-build.endpoint` line pointing at the control plane's Function URL. Until then the
> instructions here will not work — see
> [`../../design/control-plane/migration-from-direct-ecs-access.md`](../../design/control-plane/migration-from-direct-ecs-access.md).

Minimal, reflection-free example exercising `aws-ecs:build` end to end against real AWS:
`native-image` compilation for both `x86_64` and `arm64`, offloaded to ECS Fargate tasks, using
[`scaleout-test-infra`](../../../scaleout-test-infra)'s deployed stack (plain S3 staging, the
agent's direct-S3-calls I/O mode).

Verified against a real deployment in `eu-west-1`: both architectures compiled successfully,
producing real, distinct ELF binaries (`x86-64` and `aarch64`), downloaded locally and attached to
the Maven build with classifiers `native-linux-x86_64`/`native-linux-arm64`.

## Setup

1. Deploy `scaleout-test-infra` (see its own README) — the default (`includeS3Files=false`)
   deployment mode, matching this example's `agentUsesDirectS3Io=true` configuration.
2. Build and push the agent image: `./build-local.sh --multi-arch <AgentRepositoryUri>` from the
   repo root (see `scaleout-test-infra/README.md`).
3. Copy `deployment.properties.example` to `deployment.properties` and fill in the real values
   from your `cdk deploy` output — see that file's own header comment for exactly where each value
   comes from. **`deployment.properties` is gitignored; never commit it** — it holds
   account-specific resource identifiers (account ID, IAM role ARNs, subnet/security-group IDs,
   bucket name) that are meaningless to anyone else's AWS account.
4. `mvn package`.

## Why a properties file instead of environment variables or hardcoded values

`deployment.properties` is read into Maven properties at the `validate` phase via
[`properties-maven-plugin`](https://www.mojohaus.org/properties-maven-plugin/)'s
`read-project-properties` goal, then referenced as `${aws-ecs.*}` placeholders throughout the
`scaleout-build-maven-plugin` `<configuration>` block in `pom.xml`. This keeps the committed example free
of any one AWS account's specific resource identifiers, while still being a real, runnable,
self-contained Maven project — no environment variables to remember to set, no separate wrapper
script, just a config file checked in as a template (`.example`) and filled in locally.

If `deployment.properties` is missing, the build fails clearly at the `validate` phase (the plugin
is deliberately not configured with `quiet=true`) rather than proceeding with unresolved `${...}`
placeholders passed literally into the AWS SDK calls.

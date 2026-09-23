#!/usr/bin/env bash
#
# Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
#
# Builds and deploys the scaleout-build control plane, then writes the one value a developer needs.
#
# Order matters and is enforced here rather than left to the operator:
#
#   1. Build the reaper jar and the service's GraalVM native binary. CDK's Code.fromAsset resolves
#      the paths at synth time, so a missing artifact fails the deploy with a confusing CDK error
#      rather than a clear build error.
#   2. cdk deploy, which creates (or updates) the cluster, bucket, table, service and reaper.
#   3. Build and push the agent container image to the ECR repository the stack just created. This
#      cannot happen earlier: the repository does not exist until the stack is deployed.
#   4. Read the Function URL back out of CloudFormation and write it where clients look for it.
#
# Usage:
#   AWS_REGION=eu-west-1 ./deploy.sh                 # full build + deploy
#   AWS_REGION=eu-west-1 ./deploy.sh --skip-build    # deploy already-built artifacts
#   AWS_REGION=eu-west-1 ./deploy.sh --skip-agent    # skip the agent image build/push
#   AWS_REGION=eu-west-1 ./deploy.sh --yes           # no interactive approval (for CI)
#
# Requires AWS credentials, the CDK CLI, Docker (for the native build and the agent image), and a
# bootstrapped account/region.
#
# Export AWS_REGION, not CDK_DEFAULT_REGION: the CDK CLI computes CDK_DEFAULT_* from the active
# credentials and overwrites anything set in the parent shell. This is documented the hard way in
# scaleout-build-control-plane-infra/src/.../ControlPlaneInfraApp.java's class javadoc.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA_DIR="${REPO_ROOT}/scaleout-build-control-plane-infra"
STACK_NAME="ScaleoutBuildControlPlane"

SKIP_BUILD=false
SKIP_AGENT=false
CDK_APPROVAL=()
CDK_CONTEXT=()

# JVM by default: it needs no GraalVM toolchain, so a first deploy works from a plain `mvn package`.
RUNTIME_MODE="jvm"

# A native image cannot be cross-compiled, so the deployable architecture is the BUILD HOST's, not a
# choice. Derived here rather than left as a flag, because getting it wrong produces an "Exec format
# error" at cold start that names nothing useful.
case "$(uname -m)" in
    x86_64|amd64)  HOST_NATIVE_ARCH="x86" ;;
    aarch64|arm64) HOST_NATIVE_ARCH="arm64" ;;
    *) HOST_NATIVE_ARCH="" ;;
esac

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jvm) RUNTIME_MODE="jvm"; shift ;;
        --native) RUNTIME_MODE="native"; shift ;;
        --context) CDK_CONTEXT+=(-c "${2:?--context requires key=value}"); shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --skip-agent) SKIP_AGENT=true; shift ;;
        --yes) CDK_APPROVAL=(--require-approval never); shift ;;
        -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-}}"
if [[ -z "$REGION" ]]; then
    echo "Set AWS_REGION (not CDK_DEFAULT_REGION -- the CDK CLI overwrites that)." >&2
    exit 1
fi

echo "==> Target: $(aws sts get-caller-identity --query Account --output text) / ${REGION}"

if [[ "$RUNTIME_MODE" == "native" && -z "$HOST_NATIVE_ARCH" ]]; then
    echo "Unrecognised host architecture $(uname -m): cannot determine the native target." >&2
    exit 1
fi

if ! $SKIP_BUILD; then
    "${REPO_ROOT}/build-local.sh" --function "$RUNTIME_MODE"
fi

for artifact in "${REPO_ROOT}/scaleout-build-control-plane/target/function-${RUNTIME_MODE}.zip" \
                "${REPO_ROOT}/scaleout-build-control-plane-reaper/target/reaper.jar"; do
    if [[ ! -f "$artifact" ]]; then
        echo "Missing deployable artifact: ${artifact}" >&2
        echo "Run without --skip-build, or build it for this mode: ./build-local.sh --function ${RUNTIME_MODE}" >&2
        exit 1
    fi
done

CDK_MODE_CONTEXT=(-c "runtimeMode=${RUNTIME_MODE}" -c "development=true")
if [[ "$RUNTIME_MODE" == "native" ]]; then
    CDK_MODE_CONTEXT+=(-c "nativeArch=${HOST_NATIVE_ARCH}")
fi

echo "==> cdk deploy ${STACK_NAME} (runtimeMode=${RUNTIME_MODE}$([[ "$RUNTIME_MODE" == native ]] && echo "/${HOST_NATIVE_ARCH}" || echo "/arm64"))"
(cd "$INFRA_DIR" && mvn -q compile && cdk deploy "${CDK_APPROVAL[@]}" \
    "${CDK_MODE_CONTEXT[@]}" "${CDK_CONTEXT[@]}" --outputs-file cdk-outputs.json)

outputs() {
    aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK_NAME" \
        --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

ENDPOINT="$(outputs ControlPlaneEndpoint)"
AGENT_REPO="$(outputs AgentRepositoryUri)"

if ! $SKIP_AGENT; then
    echo "==> Building and pushing the agent image to ${AGENT_REPO}"
    # Multi-arch, because the whole point is building for an architecture the host is not.
    "${REPO_ROOT}/build-local.sh" --multi-arch "${AGENT_REPO}"
fi

echo
echo "==> Deployed."
echo "    Control plane endpoint : ${ENDPOINT}"
echo "    Runtime mode           : $(outputs ServiceRuntimeMode)"
echo "    Agent image            : ${AGENT_REPO}:latest"
echo "    Cluster                : $(outputs ClusterArn)"
echo "    Staging bucket         : $(outputs StagingBucketName)   (RemovalPolicy.RETAIN)"
echo "    Builds table           : $(outputs BuildsTableName)      (RemovalPolicy.RETAIN)"
echo
echo "    Also published to SSM at /scaleout-build/control-plane/endpoint"
echo
echo "    Point a build at it with:"
echo "      mvn package -Dscaleout-build.endpoint=${ENDPOINT}"
echo
echo "    Callers need lambda:InvokeFunctionUrl and lambda:InvokeFunction on"
echo "    scaleout-build-control-plane, and nothing else -- no ECS, S3, CloudWatch or ECR access."

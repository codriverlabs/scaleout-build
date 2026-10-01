#!/usr/bin/env bash
#
# Installs the scaleout-build control plane into your AWS account from a release bundle.
#
# This script expects to be run from an unpacked release tarball, which already contains a
# pre-synthesized CDK app in cdk.out/. That means no Maven, no JDK, and no CDK source build at install
# time -- only the AWS CLI, Node (for the CDK CLI), and Docker.
#
# What it does, in order:
#   1. checks prerequisites and prints what it is about to create
#   2. deploys the CloudFormation stack from the pre-synthesized app
#   3. copies the published agent image from GHCR into the ECR repository the stack created
#   4. prints the endpoint and the two IAM permissions your developers need
#
# The agent image is copied registry-to-registry with `docker buildx imagetools create`, which moves the
# multi-architecture manifest without pulling layers to this machine.
#
set -euo pipefail

REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-1}}"
CAPACITY_STRATEGY="spot-preferred"
AGENT_SOURCE=""
ASSUME_YES=false
SKIP_AGENT=false

usage() {
    cat <<'USAGE'
Usage: ./install.sh [options]

  --region <region>              AWS region (default: $AWS_REGION, else eu-west-1)
  --capacity <strategy>          spot-preferred | on-demand-preferred | spot-only | on-demand-only
                                 (default: spot-preferred -- cheaper, occasionally reclaimed mid-build)
  --agent-image <ref>            Source image to copy into your ECR.
                                 Default: the version recorded in this bundle's VERSION file.
  --skip-agent                   Deploy the stack but do not copy the agent image.
                                 The control plane cannot run builds until you do.
  --yes                          Do not prompt before creating AWS resources
  -h, --help                     This message

Creates, in your account: a VPC, an ECS cluster, an ECR repository, an S3 bucket, a DynamoDB table,
two Lambda functions, and the IAM roles they need. The bucket and table are created with
RemovalPolicy.RETAIN, so destroying the stack leaves them behind on purpose.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --region)       REGION="$2"; shift 2 ;;
        --capacity)     CAPACITY_STRATEGY="$2"; shift 2 ;;
        --agent-image)  AGENT_SOURCE="$2"; shift 2 ;;
        --skip-agent)   SKIP_AGENT=true; shift ;;
        --yes)          ASSUME_YES=true; shift ;;
        -h|--help)      usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

die() { echo "error: $*" >&2; exit 1; }

# --- prerequisites --------------------------------------------------------------------------------

command -v aws >/dev/null || die "aws CLI not found. https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
command -v npx >/dev/null || die "npx not found (needed for the CDK CLI). Install Node.js 20 or later."
[[ -d cdk.out ]] || die "cdk.out/ not found. Run this from an unpacked release tarball, not from a source checkout."

if [[ "$SKIP_AGENT" == false ]]; then
    if ! declare -f _copy_agent_image >/dev/null 2>&1; then
        command -v docker >/dev/null || die "docker not found, and it is needed to copy the agent image. Re-run with --skip-agent to defer."
    fi
fi

ACCOUNT="$(aws sts get-caller-identity --query Account --output text 2>/dev/null)" \
    || die "no usable AWS credentials. Try 'aws login' or 'aws configure'."

VERSION="$(cat VERSION 2>/dev/null || echo unknown)"
if [[ -z "$AGENT_SOURCE" ]]; then
    AGENT_SOURCE="$(cat AGENT_IMAGE 2>/dev/null || true)"
    [[ -n "$AGENT_SOURCE" ]] || die "no AGENT_IMAGE in this bundle; pass --agent-image explicitly."
fi

cat <<EOF

  scaleout-build control plane installer

    bundle version      ${VERSION}
    AWS account         ${ACCOUNT}
    region              ${REGION}
    capacity strategy   ${CAPACITY_STRATEGY}
    agent image source  ${AGENT_SOURCE}

  This creates billable AWS resources. Nothing runs continuously: the Lambda functions are invoked
  per build and the ECS tasks exist only while a build runs.

EOF

if [[ "$ASSUME_YES" == false ]]; then
    read -r -p "  Proceed? [y/N] " reply
    [[ "$reply" == "y" || "$reply" == "Y" ]] || { echo "  Aborted."; exit 0; }
fi

# --- deploy ---------------------------------------------------------------------------------------

export AWS_REGION="$REGION"
STACK=ScaleoutBuildControlPlane

echo
echo "==> Bootstrapping CDK in ${ACCOUNT}/${REGION} if needed"
npx --yes aws-cdk@2 bootstrap "aws://${ACCOUNT}/${REGION}" >/dev/null

echo "==> Deploying ${STACK}"
npx --yes aws-cdk@2 deploy "$STACK" \
    --app cdk.out \
    --require-approval never \
    --outputs-file install-outputs.json \
    -c "fargateCapacityStrategy=${CAPACITY_STRATEGY}"

ENDPOINT="$(python3 -c "import json;print(json.load(open('install-outputs.json'))['$STACK']['ControlPlaneEndpoint'])")"
AGENT_REPO="$(python3 -c "import json;print(json.load(open('install-outputs.json'))['$STACK']['AgentRepositoryUri'])")"

# --- agent image ----------------------------------------------------------------------------------

if [[ "$SKIP_AGENT" == false ]]; then
    echo
    echo "==> Copying the agent image into ${AGENT_REPO}"
    echo "    ${AGENT_SOURCE} -> ${AGENT_REPO}:latest  (multi-arch, registry to registry)"

    # Fail with a useful message rather than a bare 401 from imagetools. A GHCR package is private by
    # default even when its repository is public, and that is the likeliest cause.
    if ! docker manifest inspect "$AGENT_SOURCE" >/dev/null 2>&1; then
        die "cannot read ${AGENT_SOURCE} anonymously.
  If this is a ghcr.io image, its package visibility is probably still private -- a GHCR package does not
  inherit the repository's visibility. Either make the package public, or 'docker login ghcr.io' with a
  token that can read it and re-run."
    fi
    # Logout first: a stale credential from a previous install (different region, rotated token,
    # or re-used machine) causes docker to respond 400 rather than 401, which looks like a
    # network error rather than an auth error. Clearing first is always safe -- the login that
    # follows immediately replaces it.
    docker logout "${AGENT_REPO%%/*}" >/dev/null 2>&1 || true
    aws ecr get-login-password --region "$REGION" \
        | docker login --username AWS --password-stdin "${AGENT_REPO%%/*}" >/dev/null \
    || die "ECR login failed for ${AGENT_REPO%%/*} in region ${REGION}. Check that your AWS credentials have ecr:GetAuthorizationToken."
    docker buildx imagetools create -t "${AGENT_REPO}:latest" "$AGENT_SOURCE"
fi

# --- report ---------------------------------------------------------------------------------------

cat <<EOF

==> Installed.

    Control plane endpoint : ${ENDPOINT}
    Also published to SSM  : /scaleout-build/control-plane/endpoint
    Agent image            : ${AGENT_REPO}:latest
$(if [[ "$SKIP_AGENT" == true ]]; then echo "
    NOTE: --skip-agent was passed, so no agent image was copied. Builds will fail until you run:
      docker buildx imagetools create -t ${AGENT_REPO}:latest ${AGENT_SOURCE}"; fi)

    Point a build at it:
      mvn package -Dscaleout-build.endpoint=${ENDPOINT}

    Your developers need exactly two IAM permissions on the control plane function, and nothing else --
    no ECS, S3, CloudWatch or ECR access:

      lambda:InvokeFunctionUrl
      lambda:InvokeFunction

    The capacity strategy is '${CAPACITY_STRATEGY}'. On spot-preferred a build is occasionally
    reclaimed mid-compile and fails with no automatic retry; re-run it. Deploy with
    --capacity on-demand-preferred where a lost build is expensive.

    Uninstall:
      npx aws-cdk@2 destroy ${STACK} --app cdk.out
      # the S3 bucket and DynamoDB table are RETAINed and must be deleted by hand

EOF

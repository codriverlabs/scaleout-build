#!/usr/bin/env bash
#
# Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
#
# Deploys scaleout-test-infra's CDK stack, then captures its CloudFormation stack outputs directly
# (via `aws cloudformation describe-stacks`, not by parsing `cdk deploy`'s human-readable console
# output, which isn't meant to be machine-parsed) into a deployment.properties file in the exact
# format docs/examples/scaleout-build-example-app's pom.xml expects — closing the manual-transcription
# gap between "here's what cdk deploy printed" and "here's what deployment.properties needs".
#
# Usage:
#   ./deploy-and-capture-outputs.sh                       # deploy (plain S3, the default), write
#                                                          # to ../docs/examples/scaleout-build-example-app/deployment.properties
#   ./deploy-and-capture-outputs.sh --include-s3-files    # deploy the mount-based mechanism instead
#   ./deploy-and-capture-outputs.sh --output <path>        # write elsewhere
#   ./deploy-and-capture-outputs.sh --yes                  # skip cdk deploy's interactive approval
#                                                          # prompt (use for CI/non-interactive runs;
#                                                          # omit it to review the changeset yourself
#                                                          # first, since this creates/modifies real
#                                                          # AWS resources)
#   ./deploy-and-capture-outputs.sh --skip-deploy          # capture outputs from an already-deployed
#                                                          # stack, without deploying again
#
# Requires: the AWS CLI, `cdk`, `mvn`, and either `jq` or `python3` (jq preferred if present).
# Honors AWS_REGION the same way `cdk deploy` itself does — see scaleout-test-infra/README.md and
# InfraApp's own class Javadoc for why CDK_DEFAULT_REGION alone doesn't work as an override here.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STACK_NAME="ScaleoutBuildTestInfra"
DEFAULT_OUTPUT="${SCRIPT_DIR}/../docs/examples/scaleout-build-example-app/deployment.properties"

INCLUDE_S3_FILES=false
OUTPUT_PATH="$DEFAULT_OUTPUT"
SKIP_DEPLOY=false
# Empty by default: omitting --require-approval entirely gives cdk deploy's own real default
# (prompt only on IAM/security-group changes that broaden permissions -- equivalent to explicitly
# passing --require-approval broadening). --yes below switches this to "never" for CI/non-interactive
# runs; nothing else should set it, since this deploys/modifies real AWS resources and the default
# interactive prompt on broadening changes is a real safety net worth keeping unless asked to skip it.
APPROVAL_FLAG=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --include-s3-files) INCLUDE_S3_FILES=true; shift ;;
        --output) OUTPUT_PATH="${2:?--output requires a path}"; shift 2 ;;
        --yes) APPROVAL_FLAG="--require-approval never"; shift ;;
        --skip-deploy) SKIP_DEPLOY=true; shift ;;
        -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

REGION="${AWS_REGION:-$(aws configure get region)}"
if [[ -z "$REGION" ]]; then
    echo "No region set — export AWS_REGION=<region> (this is also what cdk deploy itself reads" \
         "to pick a region; see InfraApp's class Javadoc for why CDK_DEFAULT_REGION alone isn't" \
         "enough)." >&2
    exit 1
fi

if ! $SKIP_DEPLOY; then
    echo "==> Compiling the CDK app"
    (cd "$SCRIPT_DIR" && mvn -q compile)

    CONTEXT_FLAG=""
    $INCLUDE_S3_FILES && CONTEXT_FLAG="-c includeS3Files=true"
    echo "==> Deploying ${STACK_NAME} to ${REGION} (includeS3Files=${INCLUDE_S3_FILES})"
    (cd "$SCRIPT_DIR" && AWS_REGION="$REGION" cdk deploy $APPROVAL_FLAG $CONTEXT_FLAG)
fi

echo "==> Reading stack outputs for ${STACK_NAME} in ${REGION}"
OUTPUTS_JSON=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$REGION" \
    --query 'Stacks[0].Outputs' --output json)

get_output() {
    local key="$1"
    if command -v jq &>/dev/null; then
        echo "$OUTPUTS_JSON" | jq -r --arg k "$key" '.[] | select(.OutputKey == $k) | .OutputValue // empty'
    else
        python3 -c "
import json, sys
outputs = json.loads('''$OUTPUTS_JSON''')
for o in outputs:
    if o['OutputKey'] == '$key':
        print(o['OutputValue'])
        break
"
    fi
}

CLUSTER_ARN=$(get_output ClusterArn)
SUBNET_IDS=$(get_output SubnetIds)
SECURITY_GROUP_ID=$(get_output SecurityGroupId)
EXECUTION_ROLE_ARN=$(get_output ExecutionRoleArn)
TASK_ROLE_ARN=$(get_output TaskRoleArn)
LOG_GROUP_NAME=$(get_output LogGroupName)
S3_BUCKET=$(get_output S3Bucket)
AGENT_REPOSITORY_URI=$(get_output AgentRepositoryUri)
STACK_REGION=$(get_output Region)
S3_FILES_FILE_SYSTEM_ARN=$(get_output S3FilesFileSystemArn)

if [[ -z "$CLUSTER_ARN" ]]; then
    echo "No outputs found for stack ${STACK_NAME} in ${REGION} — has it been deployed there?" >&2
    exit 1
fi

echo "==> Writing ${OUTPUT_PATH}"
mkdir -p "$(dirname "$OUTPUT_PATH")"
{
    echo "# Generated by deploy-and-capture-outputs.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# from CloudFormation stack ${STACK_NAME} in ${STACK_REGION}."
    echo "# Do not commit this file -- see deployment.properties.example and .gitignore."
    echo
    echo "aws-ecs.region=${STACK_REGION}"
    echo "aws-ecs.clusterArn=${CLUSTER_ARN}"

    # Split into individually numbered properties, matching pom.xml's <subnetId> elements one to
    # one -- not one comma-joined value; see the example app's own README for why.
    IFS=',' read -ra SUBNET_ARRAY <<< "$SUBNET_IDS"
    for i in "${!SUBNET_ARRAY[@]}"; do
        echo "aws-ecs.subnetId.$((i + 1))=${SUBNET_ARRAY[$i]}"
    done

    IFS=',' read -ra SG_ARRAY <<< "$SECURITY_GROUP_ID"
    for i in "${!SG_ARRAY[@]}"; do
        echo "aws-ecs.securityGroupId.$((i + 1))=${SG_ARRAY[$i]}"
    done

    echo "aws-ecs.executionRoleArn=${EXECUTION_ROLE_ARN}"
    echo "aws-ecs.taskRoleArn=${TASK_ROLE_ARN}"
    echo "aws-ecs.logGroupName=${LOG_GROUP_NAME}"
    echo "aws-ecs.s3Bucket=${S3_BUCKET}"
    # AgentRepositoryUri has no tag; deployment.properties needs one -- :latest matches what
    # build-local.sh --multi-arch pushes by default.
    echo "aws-ecs.agentImageUri=${AGENT_REPOSITORY_URI}:latest"
    if [[ -n "$S3_FILES_FILE_SYSTEM_ARN" ]]; then
        echo "aws-ecs.s3FilesFileSystemArn=${S3_FILES_FILE_SYSTEM_ARN}"
    fi
} > "$OUTPUT_PATH"

echo "==> Done. ${OUTPUT_PATH} is gitignored -- verify with 'git check-ignore ${OUTPUT_PATH}' if unsure."
echo "    Remember to also build and push the agent image if you haven't yet:"
echo "      ./build-local.sh --multi-arch ${AGENT_REPOSITORY_URI}"

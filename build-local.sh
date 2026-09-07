#!/usr/bin/env bash
#
# Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
#
# Builds the jobrunr-build-agent image locally with docker buildx, using
# jobrunr-build-agent/Dockerfile (the same Dockerfile the CI workflow builds from — see
# .github/workflows/build-agent-image.yml).
#
# By default, builds for the *host's own* architecture only and loads the result into the local
# Docker daemon — fast, no emulation, immediately runnable with `docker run`. Cross-architecture
# builds (e.g. building an arm64 image on an x86_64 host) need QEMU/binfmt registered locally
# (`docker run --privileged --rm tonistiigi/binfmt --install all`); this script does not register
# that for you, since doing so system-wide is a real, host-level change this script shouldn't make
# silently.
#
# A genuine multi-arch (amd64 + arm64) build is also supported via --multi-arch, in one buildx
# invocation. This is a real docker/buildx constraint, not a limitation of this script: a manifest
# list (what makes an image "multi-arch") is a registry-level construct — the classic docker
# exporter used by `--load` explicitly rejects it ("docker exporter does not support exporting
# manifest lists, use the oci exporter instead"), so a genuine multi-arch result can only be
# produced by pushing to a registry. --multi-arch therefore always pushes, to ECR, exactly like
# --push does for a single arch — see https://docs.docker.com/build/building/multi-platform/.
#
# Usage:
#   ./build-local.sh                                   # build for the host arch, load locally, no push
#   ./build-local.sh --push <ECR-REPO-URI>              # build for the host arch, tag with an arch
#                                                        # suffix, and push
#   ./build-local.sh --multi-arch <ECR-REPO-URI>        # build amd64 AND arm64 in one buildx
#                                                        # invocation and push a real multi-arch
#                                                        # manifest list (arm64 runs under QEMU on
#                                                        # an amd64 host, and vice versa — see
#                                                        # https://docs.docker.com/build/building/multi-platform/#qemu)
#   ./build-local.sh --platform linux/arm64 ...         # override the target platform explicitly
#                                                        # (single-arch modes only)
#
# Requires: docker buildx (bundled with modern Docker); for --push/--multi-arch against ECR, AWS
# credentials (`aws sts get-caller-identity` must succeed) and the AWS CLI. ECR login and
# repository creation (if the repo doesn't exist yet) are handled by this script, the same way
# KubeECS's own build-local.sh handles them for its own image.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKERFILE="${REPO_ROOT}/jobrunr-build-agent/Dockerfile"

PUSH=false
MULTI_ARCH=false
IMAGE_URI=""
PLATFORM=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --push)
            PUSH=true
            IMAGE_URI="${2:?--push requires an image URI argument, e.g. --push 123456789012.dkr.ecr.us-east-1.amazonaws.com/jobrunr-build-agent}"
            shift 2
            ;;
        --multi-arch)
            MULTI_ARCH=true
            IMAGE_URI="${2:?--multi-arch requires an image URI argument, e.g. --multi-arch 123456789012.dkr.ecr.us-east-1.amazonaws.com/jobrunr-build-agent}"
            shift 2
            ;;
        --platform)
            PLATFORM="${2:?--platform requires a value, e.g. linux/amd64 or linux/arm64}"
            shift 2
            ;;
        -h|--help)
            grep '^#' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if $PUSH && $MULTI_ARCH; then
    echo "--push and --multi-arch are mutually exclusive — --multi-arch already pushes." >&2
    exit 1
fi

# Logs in to ECR and creates the repository if it doesn't exist yet, given a full repo URI
# (<account>.dkr.ecr.<region>.amazonaws.com/<name>). Mirrors KubeECS's own build-local.sh, which
# does the same ECR-login + idempotent-create-if-missing dance before pushing its own image.
ecr_login_and_ensure_repo() {
    local repo_uri="$1"
    if [[ ! "$repo_uri" =~ ^([0-9]+)\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com/(.+)$ ]]; then
        echo "Not an ECR URI (${repo_uri}) — skipping ECR login/repo-creation; ensure you're already logged in to the target registry." >&2
        return 0
    fi
    local region="${BASH_REMATCH[2]}"
    local repo_name="${BASH_REMATCH[3]}"
    local registry="${repo_uri%%/*}"
    echo "==> ECR login (${region})"
    aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry"
    if ! aws ecr describe-repositories --repository-names "$repo_name" --region "$region" &>/dev/null; then
        echo "==> Creating ECR repository ${repo_name}"
        aws ecr create-repository --repository-name "$repo_name" --region "$region" \
            --output text --query 'repository.repositoryUri'
    fi
}

# Detect the host platform if not overridden, and map it to the matching Mandrel builder-image
# base — the same two tags the Dockerfile's own usage comment documents. Both are the amd64 and
# arm64 variants of the same jdk-25 release; which one a given tag resolves to is determined by the
# platform docker/buildx builds for, not by the tag string itself.
if [[ -z "$PLATFORM" ]]; then
    case "$(uname -m)" in
        x86_64|amd64) PLATFORM="linux/amd64" ;;
        aarch64|arm64) PLATFORM="linux/arm64" ;;
        *) echo "Unrecognised host architecture $(uname -m); pass --platform explicitly." >&2; exit 1 ;;
    esac
fi

case "$PLATFORM" in
    linux/amd64) BASE_IMAGE="quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25" ;;
    linux/arm64) BASE_IMAGE="quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25" ;;
    *) echo "Unsupported platform $PLATFORM (expected linux/amd64 or linux/arm64)" >&2; exit 1 ;;
esac

echo "==> Building the agent jar (mvn install, so jobrunr-build-shared is built first too)"
mvn -q -pl jobrunr-build-shared,jobrunr-build-agent -am install -DskipTests
[[ -f "${REPO_ROOT}/jobrunr-build-agent/target/agent.jar" ]] || {
    echo "agent.jar was not produced at jobrunr-build-agent/target/agent.jar — build failed silently?" >&2
    exit 1
}

if $MULTI_ARCH; then
    ecr_login_and_ensure_repo "$IMAGE_URI"
    TAG="${IMAGE_URI}:latest"
    echo "==> Building linux/amd64 + linux/arm64 in one buildx invocation and pushing a real multi-arch manifest list to ${TAG}"
    echo "    (the non-native arch for this host builds under QEMU — see docs.docker.com/build/building/multi-platform for why"
    echo "     this can't be --load'ed locally instead: a manifest list only exists once pushed to a registry)"
    docker buildx build \
        --platform linux/amd64,linux/arm64 \
        --build-arg "BASE_IMAGE=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25" \
        -f "$DOCKERFILE" \
        -t "$TAG" \
        --push \
        "$REPO_ROOT"
    echo "==> Pushed multi-arch manifest ${TAG}"
    docker buildx imagetools inspect "$TAG"
elif $PUSH; then
    ecr_login_and_ensure_repo "$IMAGE_URI"
    ARCH_SUFFIX="${PLATFORM#linux/}"
    TAG="${IMAGE_URI}:${ARCH_SUFFIX}"
    echo "==> Building and pushing ${TAG} for platform ${PLATFORM} (base ${BASE_IMAGE})"
    docker buildx build \
        --platform "$PLATFORM" \
        --build-arg "BASE_IMAGE=${BASE_IMAGE}" \
        -f "$DOCKERFILE" \
        -t "$TAG" \
        --push \
        "$REPO_ROOT"
    echo "==> Pushed ${TAG}"
    echo "    To assemble a multi-arch manifest once both arch tags are pushed:"
    echo "      docker buildx imagetools create -t ${IMAGE_URI}:latest ${IMAGE_URI}:amd64 ${IMAGE_URI}:arm64"
    echo "    Or just use --multi-arch to do both arches and the manifest join in one command."
else
    TAG="jobrunr-build-agent:local-${PLATFORM#linux/}"
    echo "==> Building ${TAG} for platform ${PLATFORM} (base ${BASE_IMAGE}) and loading into the local Docker daemon"
    docker buildx build \
        --platform "$PLATFORM" \
        --build-arg "BASE_IMAGE=${BASE_IMAGE}" \
        -f "$DOCKERFILE" \
        -t "$TAG" \
        --load \
        "$REPO_ROOT"
    echo "==> Built ${TAG}. Run it with, e.g.:"
    echo "    docker run --rm -e JOBRUNR_BUILD_ID=test ${TAG}"
fi

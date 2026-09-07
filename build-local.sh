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
# Usage:
#   ./build-local.sh                              # build for the host arch, load locally, no push
#   ./build-local.sh --push <ECR-REPO-URI>         # build for the host arch, tag with an arch
#                                                   # suffix, and push (does not assemble a
#                                                   # multi-arch manifest — see the CI workflow for
#                                                   # that step, or run `docker buildx imagetools
#                                                   # create` yourself against two pushed arch tags)
#   ./build-local.sh --platform linux/arm64 ...    # override the target platform explicitly
#
# Requires: docker buildx (bundled with modern Docker), and for --push, credentials already
# configured for the target registry (e.g. `aws ecr get-login-password | docker login --username
# AWS --password-stdin <ECR-REPO-URI>` for ECR).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKERFILE="${REPO_ROOT}/jobrunr-build-agent/Dockerfile"

PUSH=false
IMAGE_URI=""
PLATFORM=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --push)
            PUSH=true
            IMAGE_URI="${2:?--push requires an image URI argument, e.g. --push 123456789012.dkr.ecr.us-east-1.amazonaws.com/jobrunr-build-agent}"
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

if $PUSH; then
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

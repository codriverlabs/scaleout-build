# Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
#
# Container agent image: the unmodified Quarkus/Red Hat Mandrel builder image with only an
# entrypoint override and the agent jar added. No custom build step runs inside this image — the
# base image already ships native-image and a JDK; this Dockerfile exists purely to bake in the
# agent jar and switch to a non-root user, both one-time concerns that don't belong in the task
# definition.
#
# Build with a specific architecture's Mandrel variant as the base, e.g.:
#   docker build --build-arg BASE_IMAGE=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25 \
#       -t jobrunr-build-agent:x86_64 -f jobrunr-build-agent/Dockerfile .
#   docker build --build-arg BASE_IMAGE=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
#       -t jobrunr-build-agent:arm64 -f jobrunr-build-agent/Dockerfile .
#
# (Verified via `docker buildx imagetools inspect`: both tags above are themselves already
# multi-arch OCI image indexes covering linux/amd64 and linux/arm64/v8 — either one works as
# BASE_IMAGE for a single-arch *or* a multi-platform buildx build; docker/buildx resolves the
# matching platform manifest automatically per build leg. There is no meaningful difference
# between the two tags for this Dockerfile's purposes; either is fine as the default.)

ARG BASE_IMAGE=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25
FROM ${BASE_IMAGE}

# The Mandrel image already runs as a non-root user — verified as uid/gid 1001 ("quarkus"), home
# /home/quarkus — so this asserts that user explicitly rather than assuming a name or uid, which
# means a future base image change that reverts to root or renames the user fails the build
# instead of silently shipping a root container.
USER 1001:1001

# The agent jar is a shaded, dependency-complete fat jar (see jobrunr-build-agent's shade plugin
# configuration) precisely so this image needs nothing beyond a JDK to run it — no Maven, no
# classpath assembly, no separate dependency layer. Placed in the existing non-root user's home
# directory rather than inventing a new path, since /home/quarkus is already owned by uid 1001.
COPY --chown=1001:1001 jobrunr-build-agent/target/agent.jar /home/quarkus/agent.jar

# native-image writes heavy scratch traffic to TMPDIR; keep it on the container's own writable
# layer (backed by Fargate ephemeral storage) rather than on the S3 Files mount, which the runtime
# JOBRUNR_BUILD_MOUNT_ROOT / JOBRUNR_BUILD_TEMP_DIR environment variables also control.
ENV JOBRUNR_BUILD_TEMP_DIR=/tmp
ENV JOBRUNR_BUILD_MOUNT_ROOT=/mnt/build

# ---------------------------------------------------------------------------------------------
# IMPORTANT — S3 Files mount ownership (verified locally with a bind mount standing in for it):
#
# native-image fails outright if it cannot write to its output directory, and it does not create
# that directory itself — the agent's BuildEnvironment/NativeImageBuildExecutor create it before
# invoking native-image, but the *mount* still has to be writable by this container's non-root
# user (uid/gid 1001). A bind-mount test against this exact image reproduced the failure directly:
# a host directory not writable by uid 1001 makes native-image fail with "directory without write
# access", which surfaces as an opaque build failure with no obvious cause unless you know to
# check mount permissions specifically.
#
# For the real S3 Files volume (Task 5), this means the mounted filesystem must grant write access
# to uid/gid 1001, not just to the task's IAM role — S3 Files controls object-level S3 access via
# IAM, but the ECS-side POSIX permissions on the mounted view are a separate concern. Verify this
# explicitly once the S3 Files filesystem exists, rather than assuming IAM permissions are enough.
# ---------------------------------------------------------------------------------------------

# Entrypoint override: the upstream image's own entrypoint expects to run native-image directly.
# This container instead runs the agent, which invokes native-image itself once it has claimed a
# job — see AgentMain and NativeImageBuildExecutor.
ENTRYPOINT ["java", "-jar", "/home/quarkus/agent.jar"]

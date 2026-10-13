# Code-artifact Dockerfile for the agent's Lambda MicroVM image.
#
# This is NOT built by this module's own build (no COPY of this repo's sources, no Maven
# invocation here) -- it is submitted as a separate code-artifact ZIP to CreateMicrovmImage via
# MicroVmImageManager.triggerBuild. See docs/design/control-plane/microvm-build-backend-phase1.md's
# "Packaging: a second entrypoint, not a second module" section for why this lives here rather
# than as a top-level repo Dockerfile.
#
# IMPORTANT, confirmed against AWS's own documentation (Lambda MicroVMs' "Create your first Lambda
# MicroVM" page) after an earlier version of this file got this wrong: "The FROM instruction sets
# the container image for your application layers... The Lambda managed base image (which
# provides the MicroVM operating system and service components) is specified separately with
# --base-image-arn" in the CreateMicrovmImage call (already passed correctly by
# MicroVmImageManager.triggerBuild). FROM here must NOT reference the managed base image's own
# ARN/placeholder -- that is a platform layer composed in separately, not something this
# Dockerfile names. A literal "BASE_IMAGE_PLACEHOLDER" was in this file before this correction,
# never actually submitted to a real build, so the mistake was never caught until checked against
# the documentation directly.
#
# What FROM must still provide: native-image itself. The agent (AgentHookServer -> AgentMain's
# unchanged runWithDirectS3Io -> NativeImageBuildExecutor) runs a real native-image compile, not
# just a JRE workload -- a plain JRE base (the first version of this fix, also wrong) has no
# native-image at all. quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 is the same
# Mandrel builder image this project's Fargate/ECS path already uses for exactly this reason (see
# TaskDefinitionRegistrarTest, docs/DESIGN.md, and this module's own jib profile) -- the arm64
# variant specifically, confirmed required because Lambda MicroVMs run ARM64 only
# (MicroVmImageManager.AL2023_BASE_IMAGE_ARN_SUFFIX's own javadoc: "ARM64 only: Lambda MicroVMs
# run on ARM64, and GraalVM does not cross-compile").
#
# The only difference from the Fargate/ECS path's entrypoint (AgentMain) is the main class: same
# jar, same shaded artifact (scaleout-build-agent's maven-shade-plugin "agent" build), different
# CMD. AgentHookServer listens on AgentHookServer.DEFAULT_PORT (9000), which must match
# MicroVmImageManager.AGENT_HOOK_PORT on the control-plane side that configures Hooks.port() for
# this image -- see AgentHookServer's own javadoc for why that is a second copy of the same value
# rather than a shared constant.

FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25

# Same non-root user the Fargate/ECS path's own Dockerfile already asserts (verified there as
# uid/gid 1001, home /home/quarkus) -- same base image, so the same user exists.
USER 1001:1001

COPY --chown=1001:1001 agent.jar /home/quarkus/agent.jar

EXPOSE 9000

# ENTRYPOINT must be overridden explicitly, not just CMD -- confirmed the hard way, by actually
# building and running this image: `docker inspect` on the base image shows
# ENTRYPOINT ["native-image"] with an empty CMD. A Dockerfile that only sets CMD (the first
# version of this file) has that CMD appended as ARGUMENTS to the inherited ENTRYPOINT, so the
# container actually ran `native-image -cp /home/quarkus/agent.jar java ai.codriverlabs...` --
# nonsense arguments to native-image, not the agent at all. That silently produced no server on
# port 9000, which is why the platform's /ready hook timed out after its 2-minute budget with no
# agent-side log output to explain why (confirmed via CloudWatch: zero log events for the build,
# because the agent process that would have logged anything never actually started).
ENTRYPOINT ["java", "-cp", "/home/quarkus/agent.jar", "ai.codriverlabs.scaleoutbuild.build.agent.AgentHookServer"]

FROM public.ecr.aws/ubuntu/ubuntu:24.04

# Non-interactive apt
ENV DEBIAN_FRONTEND=noninteractive

# skopeo: daemonless OCI copy tool (Ubuntu 24.04 repos).
# awscli: the deployment tool.
# nodejs: needed for the CDK CLI (Node 18 from Ubuntu repos works with CDK 2).
# npm is bundled with nodejs.
# awscli is not in the Ubuntu 24.04 minimal repo; install from the official binary.
# skopeo, nodejs, npm and unzip are in the default repos.
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends \
       skopeo \
       nodejs \
       npm \
       curl \
       ca-certificates \
       python3 \
       unzip \
       groff \
    && rm -rf /var/lib/apt/lists/* \
    && ARCH=$(uname -m | sed "s/x86_64/x86_64/;s/aarch64/aarch64/") \
    && curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${ARCH}.zip" -o /tmp/awscli.zip \
    && unzip -q /tmp/awscli.zip -d /tmp \
    && /tmp/aws/install \
    && rm -rf /tmp/awscli.zip /tmp/aws \
    && npm install -g aws-cdk@2 --silent \
    && node --version \
    && aws --version \
    && skopeo --version \
    && cdk --version

# Region this installer bundle was synthesized for. Used to warn on mismatch.
ENV BUNDLE_REGION=us-east-1

WORKDIR /installer

# Pre-synthesized CDK app. Assets are embedded in cdk.out/ so cdk deploy --app cdk.out
# uploads them from inside the container; no external fetches at deploy time.
COPY scaleout-build-control-plane-infra/cdk.out/ ./cdk.out/
COPY scripts/install.sh ./install.sh
COPY VERSION AGENT_IMAGE LICENSE README.md ./

RUN chmod +x install.sh

# Entrypoint wrapper: injects a skopeo-based _copy_agent_image before install.sh runs.
# install.sh checks `declare -f _copy_agent_image` and skips its own Docker-based
# definition when this one is already present. No Docker socket needed.
RUN cat > /usr/local/bin/run-installer.sh <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail

# Override the copy function. install.sh honours this via a `declare -f` guard.
# skopeo authenticates to ECR by fetching a token from the AWS credential chain.
_copy_agent_image() {
    local src="$1" dst="$2" region="$3"
    echo "    (daemonless copy via skopeo)"

    # Get an ECR auth token from the AWS credential chain.
    local token
    token=$(aws ecr get-login-password --region "${region}")

    # Determine the ECR registry from the destination URI.
    local registry="${dst%%/*}"

    skopeo copy \
        --all \
        --src-no-creds \
        --dest-creds "AWS:${token}" \
        "docker://${src}" \
        "docker://${dst}:latest"
}
export -f _copy_agent_image


# Check that the target region matches the bundled one.
TARGET_REGION="${AWS_REGION:-}"
# Extract --region flag from arguments
prev=""
for i in "$@"; do
    [[ "$prev" == "--region" ]] && TARGET_REGION="$i"
    prev="$i"
done
if [[ -n "${BUNDLE_REGION:-}" && -n "$TARGET_REGION" && "$TARGET_REGION" != "$BUNDLE_REGION" && "$TARGET_REGION" != "--"* ]]; then
    echo ""
    echo "WARNING: This installer bundle was synthesized for ${BUNDLE_REGION}."
    echo "  You specified --region ${TARGET_REGION}."
    echo "  Deploying a pre-synthesized CDK app to a different region may fail because"
    echo "  Lambda code assets are published to the ${BUNDLE_REGION} bootstrap bucket."
    echo ""
    echo "  For a different region, use the tarball and re-synthesize from source:"
    echo "    tar xzf scaleout-build-installer-<version>.tar.gz"
    echo "    cd scaleout-build-installer-<version>"
    echo "    ./install.sh --region ${TARGET_REGION} --yes"
    echo ""
    echo "  To proceed anyway, re-run with --force-region."
    echo ""
    [[ " $* " != *" --force-region "* ]] && exit 1
fi

exec /installer/install.sh "$@"
WRAPPER
RUN chmod +x /usr/local/bin/run-installer.sh

ENTRYPOINT ["/usr/local/bin/run-installer.sh"]
CMD ["--help"]

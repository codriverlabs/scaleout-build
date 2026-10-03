FROM public.ecr.aws/ubuntu/ubuntu:26.04

# Non-interactive apt
ENV DEBIAN_FRONTEND=noninteractive

# Ubuntu 26.04 ships Node 22 LTS in the default repos — no nodesource PPA needed.
# awscli is not in the Ubuntu repos; install from the official binary.
# skopeo, nodejs, npm, and unzip are all in the default repos.
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


exec /installer/install.sh "$@"
WRAPPER
RUN chmod +x /usr/local/bin/run-installer.sh

ENTRYPOINT ["/usr/local/bin/run-installer.sh"]
CMD ["--help"]

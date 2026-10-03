FROM public.ecr.aws/amazonlinux/amazonlinux:2023

# AL2023 ships nodejs22, skopeo, and awscli-2 in the default repos — no external
# downloads or PPAs needed. nodejs22 is the explicit package name; the default
# 'nodejs' package on AL2023 is Node 18.
RUN dnf install -y --quiet \
       nodejs22 \
       skopeo \
       awscli-2 \
       unzip \
       python3 \
    && dnf clean all \
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

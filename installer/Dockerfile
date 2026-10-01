FROM public.ecr.aws/amazonlinux/amazonlinux:2023

# al2023 ships curl-minimal which conflicts with full curl; --allowerasing replaces it.
# amazon-ecr-credential-helper lets crane authenticate to ECR via the AWS credential chain.
RUN dnf install -y --allowerasing \
        nodejs22 aws-cli tar gzip curl findutils python3 \
        amazon-ecr-credential-helper \
    && dnf clean all \
    && npm install -g aws-cdk@2 --silent

# crane: daemonless OCI copy tool. Pre-downloaded by the CI workflow and COPYed in
# to avoid network calls during docker build, which may not have outbound internet.
# The workflow downloads the arch-matching binary to installer/crane before building.
COPY installer/crane /usr/local/bin/crane
RUN chmod +x /usr/local/bin/crane && crane version

WORKDIR /installer

# Pre-synthesized CDK app. Assets are embedded in cdk.out/ so cdk deploy --app cdk.out
# uploads them from inside the container; no external fetches at deploy time.
COPY scaleout-build-control-plane-infra/cdk.out/ ./cdk.out/
COPY scripts/install.sh ./install.sh
COPY VERSION AGENT_IMAGE LICENSE README.md ./

RUN chmod +x install.sh

# Entrypoint wrapper: injects a crane-based _copy_agent_image before install.sh runs.
# install.sh checks `declare -f _copy_agent_image` and skips its own Docker-based
# definition when this one is already present. No Docker socket needed.
RUN cat > /usr/local/bin/run-installer.sh <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail

# Configure crane to use the ECR credential helper so it authenticates to ECR via the
# AWS credential chain (IAM role, env vars, ~/.aws) without an explicit docker login.
mkdir -p ~/.docker
cat > ~/.docker/config.json <<'CRANECFG'
{"credsStore": "ecr-login"}
CRANECFG

# Override the copy function. install.sh honours this via a `declare -f` guard.
_copy_agent_image() {
    local src="$1" dst="$2" region="$3"
    echo "    (daemonless copy via crane + ecr-login credential helper)"
    crane copy "${src}" "${dst}:latest"
}
export -f _copy_agent_image

exec /installer/install.sh "$@"
WRAPPER
RUN chmod +x /usr/local/bin/run-installer.sh

ENTRYPOINT ["/usr/local/bin/run-installer.sh"]
CMD ["--help"]

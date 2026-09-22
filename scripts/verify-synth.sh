#!/usr/bin/env bash
# verify-synth.sh — assert the CDK template is correct in BOTH deployment modes.
#
# Usage:
#   ./scripts/verify-synth.sh
#
# Why this exists as a check rather than a manual synth: the two modes differ in five coupled
# properties (runtime, architecture, handler, zip, Web Adapter layer variant), and a mismatch between
# any two of them produces a function that deploys successfully and then fails at cold start --
# "Exec format error" for a wrong architecture, or a missing handler for a wrong runtime. Neither is
# visible in `cdk diff`. A wrong memory size is worse still: it just runs slowly.
#
# This caught a real bug when written: the JVM memory bump had been applied to the reaper instead of
# the service, so in JVM mode the service ran on 512 MB and the reaper on 1024 MB.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA_DIR="${REPO_ROOT}/scaleout-build-control-plane-infra"

# Synth only reads these paths to hash them as assets; content is irrelevant to the assertions.
mkdir -p "${REPO_ROOT}/scaleout-build-control-plane/target" \
         "${REPO_ROOT}/scaleout-build-control-plane-reaper/target"
for placeholder in scaleout-build-control-plane/target/function-jvm.zip \
                   scaleout-build-control-plane/target/function-native.zip \
                   scaleout-build-control-plane-reaper/target/reaper.jar; do
    [[ -e "${REPO_ROOT}/${placeholder}" ]] || touch "${REPO_ROOT}/${placeholder}"
done

cd "$INFRA_DIR"
mvn -q compile

for mode in jvm native; do
    extra=()
    [[ "$mode" == native ]] && extra=(-c nativeArch=x86)
    echo "==> synth runtimeMode=${mode}"
    npx cdk synth -c "runtimeMode=${mode}" "${extra[@]}" -c development=true --json \
        > "/tmp/scaleout-synth-${mode}.json"
done

python3 - <<'PY'
import json, sys

failures = []

def check(mode, label, actual, expected):
    if actual != expected:
        failures.append(f"{mode}/{label}: expected {expected!r}, got {actual!r}")

for mode, want in {
    # JVM: architecture-neutral bytecode, so arm64 is a free price/performance choice. run.sh is the
    # handler because the Web Adapter's exec wrapper starts it; the script derives its own jar name.
    "jvm":    {"runtime": "java25",          "arch": "arm64",  "handler": "run.sh",    "mem": 1024,
               "lwa": "Arm64"},
    # Native: architecture-specific binary, so x86_64 must match the Mandrel image that built it.
    # bootstrap IS the binary, renamed.
    "native": {"runtime": "provided.al2023", "arch": "x86_64", "handler": "bootstrap", "mem": 512,
               "lwa": "X86"},
}.items():
    resources = json.load(open(f"/tmp/scaleout-synth-{mode}.json"))["Resources"]
    functions = [v["Properties"] for v in resources.values()
                 if v["Type"] == "AWS::Lambda::Function"]

    service = [f for f in functions if f.get("Handler") in ("run.sh", "bootstrap")]
    reapers = [f for f in functions if "BuildReaperHandler" in str(f.get("Handler"))]

    if len(service) != 1 or len(reapers) != 1:
        failures.append(f"{mode}: expected exactly 1 service + 1 reaper, "
                        f"got {len(service)} + {len(reapers)}")
        continue
    svc, reaper = service[0], reapers[0]

    check(mode, "service.runtime", svc["Runtime"], want["runtime"])
    check(mode, "service.architecture", svc["Architectures"], [want["arch"]])
    check(mode, "service.handler", svc["Handler"], want["handler"])
    check(mode, "service.memory", svc["MemorySize"], want["mem"])

    # The Web Adapter layer is per-architecture. Pairing an Arm64 layer with an x86_64 function is
    # the kind of mismatch that only surfaces at cold start.
    layer = str(svc.get("Layers", ["<none>"])[0])
    if f"LambdaAdapterLayer{want['lwa']}" not in layer:
        failures.append(f"{mode}/service.lwaLayer: expected LambdaAdapterLayer{want['lwa']}, "
                        f"got {layer}")

    # The reaper is deliberately invariant: always a JVM jar on arm64, never fronted by the adapter.
    # It must not drift with the service's mode. It has no Function URL either -- only the
    # EventBridge rule may invoke it, which is why it is a separate function at all.
    check(mode, "reaper.runtime", reaper["Runtime"], "java25")
    check(mode, "reaper.architecture", reaper["Architectures"], ["arm64"])
    check(mode, "reaper.memory", reaper["MemorySize"], 512)
    if reaper.get("Layers"):
        failures.append(f"{mode}/reaper: must not carry the Web Adapter layer")

    urls = [v["Properties"] for v in resources.values() if v["Type"] == "AWS::Lambda::Url"]
    if len(urls) != 1:
        failures.append(f"{mode}: expected exactly 1 Function URL, got {len(urls)}")
    else:
        check(mode, "url.authType", urls[0].get("AuthType"), "AWS_IAM")
        check(mode, "url.invokeMode", urls[0].get("InvokeMode"), "RESPONSE_STREAM")

if failures:
    print("SYNTH ASSERTIONS FAILED:")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("All synth assertions passed for both jvm and native modes.")
PY

#!/usr/bin/env bash
# verify-agent-image.sh — assert a pushed agent image is actually usable, on both architectures.
#
# Usage:
#   ./scripts/verify-agent-image.sh <image-uri>            # e.g. …/scaleout-build-agent:latest
#
# Why a script rather than a unit test: the properties worth checking only exist in a built, pushed,
# multi-architecture image, and asserting them needs a container runtime per architecture. That does
# not belong in `mvn verify`, which must stay runnable without Docker or registry credentials.
#
# What it catches that the build cannot:
#
#   * Non-root. Neither Docker's USER nor Jib's <user> validates that the uid exists in the base
#     image -- both merely set it. A base image that renamed or removed uid 1001 would still build
#     and push cleanly. The Dockerfile used to claim this assertion "fails the build", which was
#     simply untrue; running the image and asking is the only real check.
#
#   * native-image present and runnable for THAT architecture. The whole point of the image.
#
#   * The environment the agent reads at startup. Missing SCALEOUT_BUILD_TEMP_DIR sends native-image's
#     scratch traffic somewhere unintended, which shows up as a slow or failing build rather than as a
#     configuration error. The Jib profile was missing both of these, and shipped a ROOT container.
#
#   * That the entrypoint starts the agent at all. The Dockerfile path runs a fat jar; the Jib path
#     runs a computed classpath. Both must reach AgentMain, which is only observable by running it.
set -euo pipefail

IMAGE="${1:?Usage: verify-agent-image.sh <image-uri>}"
FAILURES=0

# Both by default, which is what a consumer pulls. CI overrides this to check one architecture on its
# own native runner, so the per-arch checks need no QEMU emulation:
#   PLATFORMS=linux/arm64 ./scripts/verify-agent-image.sh <uri>
read -r -a PLATFORM_LIST <<<"${PLATFORMS:-linux/amd64 linux/arm64}"

fail() { echo "  FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }

for platform in "${PLATFORM_LIST[@]}"; do
    echo "==> ${IMAGE} on ${platform}"

    out="$(docker run --rm --platform "$platform" --entrypoint sh "$IMAGE" -c '
        id -u; id -g
        native-image --version 2>&1 | head -1
        echo "${SCALEOUT_BUILD_TEMP_DIR:-<unset>}"
        echo "${SCALEOUT_BUILD_MOUNT_ROOT:-<unset>}"
    ' 2>&1 | tail -5)" || { fail "could not run the image for ${platform}"; continue; }

    uid="$(sed -n 1p <<<"$out")"
    gid="$(sed -n 2p <<<"$out")"
    ni="$(sed -n 3p <<<"$out")"
    tmp="$(sed -n 4p <<<"$out")"
    mount="$(sed -n 5p <<<"$out")"

    [[ "$uid" == "1001" && "$gid" == "1001" ]] \
        || fail "expected uid/gid 1001:1001, got ${uid}:${gid} (a root container)"
    [[ "$ni" == native-image* ]] \
        || fail "native-image not runnable: ${ni}"
    [[ "$tmp" == "/tmp" ]] \
        || fail "SCALEOUT_BUILD_TEMP_DIR is ${tmp}, expected /tmp"
    [[ "$mount" == "/mnt/build" ]] \
        || fail "SCALEOUT_BUILD_MOUNT_ROOT is ${mount}, expected /mnt/build"

    echo "    uid=${uid}:${gid}  ${ni}  TMPDIR=${tmp}  MOUNT=${mount}"

    # The entrypoint must reach AgentMain. With no job configured it should refuse with a named
    # configuration error; anything else (a stack trace, ClassNotFoundException, an empty exit) means
    # the entrypoint or classpath is wrong.
    entry="$(docker run --rm --platform "$platform" "$IMAGE" 2>&1 | head -2 || true)"
    if grep -q 'SCALEOUT_BUILD_ID must be set' <<<"$entry"; then
        echo "    entrypoint reaches AgentMain"
    else
        fail "entrypoint did not reach AgentMain cleanly: ${entry}"
    fi
done

if [[ "${PLATFORMS:-}" != "" && ${#PLATFORM_LIST[@]} -lt 2 ]]; then
    echo
    echo "==> skipping the manifest-list assertion (PLATFORMS pinned to ${PLATFORM_LIST[*]})"
    if (( FAILURES > 0 )); then
        echo "${FAILURES} check(s) failed for ${IMAGE}" >&2
        exit 1
    fi
    echo "Checks passed for ${IMAGE} on ${PLATFORM_LIST[*]}"
    exit 0
fi

echo
echo "==> manifest"
docker buildx imagetools inspect "$IMAGE" 2>/dev/null | grep -E 'MediaType:|Platform:' | sed 's/^/    /'

platforms="$(docker buildx imagetools inspect "$IMAGE" --raw 2>/dev/null \
    | python3 -c 'import json,sys; m=json.load(sys.stdin); print(" ".join(sorted(
        x["platform"]["architecture"] for x in m.get("manifests", [])
        if x.get("platform", {}).get("architecture") not in (None, "unknown"))))' 2>/dev/null || true)"
[[ "$platforms" == "amd64 arm64" ]] \
    || fail "expected a manifest list covering amd64 and arm64, got: ${platforms:-<none>}"

echo
if (( FAILURES > 0 )); then
    echo "${FAILURES} check(s) failed for ${IMAGE}" >&2
    exit 1
fi
echo "All checks passed for ${IMAGE}"

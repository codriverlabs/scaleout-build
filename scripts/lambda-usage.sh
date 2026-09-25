#!/usr/bin/env bash
# lambda-usage.sh — report memory and duration usage for the control-plane Lambda.
#
# Usage:
#   ./scripts/lambda-usage.sh [minutes]      # default 60
#
# Reads the REPORT lines Lambda writes for every invocation, which carry the only authoritative
# per-invocation memory figure: Max Memory Used. There is no CloudWatch metric for it -- the metrics
# namespace has Duration, Invocations, Errors and Throttles, but memory exists solely in these log lines.
#
# CPU is not reported at all, by design: Lambda allocates vCPU in proportion to configured memory
# (roughly one vCPU at 1769 MB) and bills on GB-seconds, so duration at a given memory size IS the CPU
# signal. That is why this reports the two together rather than pretending to a CPU percentage.
#
# Init Duration appears only on cold starts, so its count is the cold-start count for the window.
set -euo pipefail

MINUTES="${1:-60}"
REGION="${AWS_REGION:-eu-west-1}"
FUNCTION="${FUNCTION:-scaleout-build-control-plane}"

CONFIG=$(aws lambda get-function-configuration --function-name "$FUNCTION" --region "$REGION" \
    --query '[Runtime,Architectures[0],MemorySize,CodeSize]' --output text)
echo "==> ${FUNCTION} (${REGION}), last ${MINUTES}m"
echo "    configured: $(awk '{printf "runtime=%s arch=%s memory=%sMB code=%.1fMB", $1, $2, $3, $4/1048576}' <<<"$CONFIG")"
echo

# --output json, not text. A REPORT message contains literal tabs between its fields, and `--output text`
# also separates events with tabs -- so splitting the text output on tabs shreds every REPORT line into
# fragments and the parse silently finds nothing. That is exactly what happened when this was first
# written: 0 invocations reported while 25 REPORT lines sat in the window.
aws logs filter-log-events \
    --log-group-name "/aws/lambda/${FUNCTION}" \
    --region "$REGION" \
    --start-time "$(( ($(date +%s) - MINUTES * 60) * 1000 ))" \
    --output json 2>/dev/null \
  | python3 -c '
import sys, json, re, statistics

events = json.load(sys.stdin).get("events", [])
dur, mem, init, billed = [], [], [], []
for event in events:
    line = event.get("message", "")
    if not line.startswith("REPORT"):
        continue
    m = re.search(r"(?<!Billed )Duration: ([\d.]+) ms", line)
    if m:
        dur.append(float(m.group(1)))
    m = re.search(r"Billed Duration: (\d+) ms", line)
    if m:
        billed.append(int(m.group(1)))
    m = re.search(r"Max Memory Used: (\d+) MB", line)
    if m:
        mem.append(int(m.group(1)))
    m = re.search(r"Init Duration: ([\d.]+) ms", line)
    if m:
        init.append(float(m.group(1)))

def show(label, xs, unit):
    if not xs:
        print(f"    {label:22} (none)")
        return
    xs = sorted(xs)
    p95 = xs[min(len(xs) - 1, int(len(xs) * 0.95))]
    print(f"    {label:22} n={len(xs):<5} min={xs[0]:<8.0f} median={statistics.median(xs):<8.0f} "
          f"p95={p95:<8.0f} max={xs[-1]:<8.0f} {unit}")

print(f"==> {len(dur)} invocation(s) in {len(events)} log events")
show("max memory used", mem, "MB")
show("duration", dur, "ms")
show("billed duration", billed, "ms")
show("init (cold starts)", init, "ms")
'

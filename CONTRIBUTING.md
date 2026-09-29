# Contributing

Thanks for your interest. This document covers the contributor agreement, how to build and test, and the
conventions this codebase actually follows.

---

## Contributor License Agreement (CLA)

Before we can merge your Pull Request, you must sign our Contributor License Agreement. It is a one-time,
frictionless process:

1. Open a Pull Request.
2. The CLA Assistant bot will post a comment with a link.
3. Click the link, sign in with GitHub, and accept the agreement.
4. The bot marks your PR as ready — takes about five seconds.

The CLA grants Plasticity.Cloud Limited and CoDriverLabs Limited a non-exclusive, perpetual, worldwide,
royalty-free license to use, modify, and distribute your contribution. You retain full copyright ownership
of your code.

---

## Prerequisites

| | Needed for |
|---|---|
| **Java 25** | Building everything. GraalVM or Mandrel only if you want to build native locally |
| **Maven 3.9+** | — |
| **Docker** | Verifying the agent image; running a compile in a container |
| **AWS CLI + credentials** | Deploying a control plane and running anything end to end |
| **CDK CLI** (`npm install -g aws-cdk`) | Deploying the control plane |

You can build and run the full test suite with just Java and Maven. Everything that needs AWS is opt-in.

---

## Repository structure

```
scaleout-build-maven-plugin/       the Maven plugin -- runs on a developer machine
scaleout-build-shared/             build matrix types, staging layout, native-image executor
scaleout-build-control-plane-api/  wire contract: JAX-RS interfaces, DTOs, SigV4 client filter
scaleout-build-ecs/                ECS orchestration, S3 staging, input planning
scaleout-build-control-plane/      the Quarkus service, deployed as a Lambda
scaleout-build-control-plane-reaper/  scheduled Lambda that stops orphaned tasks
scaleout-build-control-plane-infra/   AWS CDK app (Java)
scaleout-build-agent/              the container that runs one build cell and exits
docs/design/                       why things are the way they are
```

---

## Build and test

```bash
mvn -B clean verify          # everything, ~166 tests
mvn -B -pl scaleout-build-ecs test -Dtest=SomeTest
./scripts/verify-synth.sh    # CDK synthesises in both deployment modes
```

Optional, and needs AWS:

```bash
AWS_REGION=eu-west-1 ./scripts/deploy-local.sh --native --skip-agent --yes
./scripts/verify-agent-image.sh <image-uri>
./scripts/lambda-usage.sh 20          # memory and duration of recent invocations
```

---

## Conventions that matter here

These are not style preferences; each one exists because breaking it caused a real bug.

**Verify versions, do not remember them.** Dependency versions are pinned to the latest stable release,
confirmed by actually resolving them against Maven Central. See
[`.kiro/steering/tech.md`](.kiro/steering/tech.md) for why — twice a version was pinned from memory and was
wrong, once for a release that had never been published.

**The wire contract is versioned by compatibility, not by a number.** Adding a field to a record in
`scaleout-build-control-plane-api` is fine: an old client sends nothing and the server defaults. Adding a
value to an enum such as `CellState` is **not** fine — it breaks old clients on deserialisation. Say which
you are doing in the PR.

**Per-cell state is a map, never a scalar.** `LogEvent.nextSince` is `Map<String, Long>` keyed by cell
because a single watermark silently truncated logs for whichever cell ran faster. If you find yourself
collapsing a per-cell map to one value, that is the bug returning.

**Fail loudly rather than fall through.** `argsFileDirectory` has no default precisely because a wrong
guess would build successfully and then produce a binary that misbehaves at run time. Prefer an exception
naming the fix over a silent fallback.

**Do not compare binaries by digest.** `native-image` output is not bit-reproducible — two builds of
identical inputs on the same toolchain produced binaries of exactly the same length differing in 135 million
bytes. Compare architecture, length, and behaviour against a control build instead.

---

## Pull requests

- Small and reviewable. Several focused commits beat one large one; this project merges with merge commits
  so individual messages survive.
- **Write the reasoning in the commit message, not only the diff.** If you investigated something and it
  turned out differently than expected, that is the most valuable part.
- CI must be green: `ci.yml` runs the full suite plus CDK synthesis on both modes.
- If you change something measured — a memory size, a timeout, a task shape — include the measurement.
  `docs/COST_ANALYSIS.md` and `docs/design/control-plane/*-resource-usage.md` carry figures that go stale,
  and a number without provenance is worse than no number.
- If a claim is unverified, say so in the text. There are several such notes in the docs already; they are
  deliberate.

---

## Reporting bugs

Open an issue with the plugin version, the framework (Quarkus, Spring Boot AOT, plain GraalVM), the
architectures involved, and the streamed build output. The agent logs the full `native-image` invocation,
which is usually enough to reproduce.

**Security issues do not go in public issues** — see [`SECURITY.md`](SECURITY.md).

# scaleout-builder-control-plane

## Status: Proposed — design only, no code written

Companion documents:
[`migration-from-direct-ecs-access.md`](migration-from-direct-ecs-access.md) covers how to get from
today's model to this one without a flag-day cutover.
[`sigv4-client-signing.md`](sigv4-client-signing.md) is the reference design for how clients
authenticate to this service, and is written to be usable outside this repository.

## Problem

Today `aws-ecs:build` is a thick client. `BuildMojo` exposes **33 `aws-ecs.*` parameters**, and
only 11 of them describe what the user actually wants built. The other 22 describe the AWS
environment the build runs in: `clusterArn`, `subnetIds`, `securityGroupIds`, `executionRoleArn`,
`taskRoleArn`, `s3Bucket`, `logGroupName`, `agentImageUri`, `launchType`, `capacityProviderName`,
`assignPublicIp`, the three `s3Files*` values, `ec2HostMountPath`, `agentUsesDirectS3Io`, `region`,
and the four agent sizing knobs. See
[`migration-from-direct-ecs-access.md`](migration-from-direct-ecs-access.md) for the exact
parameter-by-parameter inventory.

Two consequences follow, and the second is the expensive one:

1. **Every developer must be handed a working copy of the infrastructure's identity.** They need a
   populated `deployment.properties` — role ARNs, subnet and security-group IDs, bucket name — and
   they need direct IAM permissions on ECS (`RunTask`/`DescribeTasks`/`StopTask`), S3, CloudWatch
   Logs, and ECR. Rotating any of it means every developer edits a local file.

2. **Per-developer isolation becomes an IAM problem instead of an application problem.**
   `docs/design/ci-cd/developer-test-infra-isolation.md` worked this through and concluded that,
   because ECS has no resource-based policy equivalent to an S3 bucket policy, the permission
   *must* be attached to each developer's own IAM identity. That forces one inline policy per
   developer, a CI role holding `PutUserPolicy`/`AttachRolePolicy` restricted by two independent
   conditions (policy name *and* target principal), and an unresolved question about whether
   `cdk destroy` cleans the grant up.

This document proposes removing the cause rather than engineering around the consequence. A
control-plane service holds the infrastructure knowledge and the AWS permissions; the plugin
becomes a thin client that describes a build and streams its output.

## Goals

- A developer can run `mvn package` knowing exactly one deployment-specific value: the service
  endpoint URL.
- No developer holds standing IAM permissions on ECS, S3, CloudWatch Logs, or ECR.
- Per-developer isolation is enforced in one auditable place, not across N IAM policies.
- `Ctrl+C` interrupts a remote build, with the same observable behaviour as a local build.
- An orphaned build cannot bill indefinitely, even if the client dies without cancelling.
- Swapping the execution backend (ECS Fargate today, MicroVM later) does not change the client
  contract.

## Non-goals

- **Multi-tenant SaaS.** This is an internal developer-portal service. Callers are AWS principals
  in one account. Tenant-level isolation of untrusted code is explicitly out of scope; see
  "Security posture" for why that matters and when it stops being true.
- **Replacing the local build path.** When the host architecture matches a matrix cell and
  `forceRemote` is false, the plugin still builds locally with no service call at all.
- **A web UI.** The client is the Maven plugin (and later a CLI). If a browser UI is ever wanted,
  see "When to add API Gateway".

## Decision: single Lambda behind a Function URL, no API Gateway

One Quarkus Lambda, packaged as a GraalVM native image on `provided.al2023`, fronted by a Lambda
Function URL with `AuthType: AWS_IAM` and `InvokeMode: RESPONSE_STREAM`, running the AWS Lambda Web
Adapter with `AWS_LWA_INVOKE_MODE=response_stream`. All routes — control operations and the log
stream — are served by that one function on that one URL. No API Gateway.

### Why this shape

This is the shape `express-compute-control-plane` converged on for its tenant-service, and it got
there by hitting the failure mode first. Three findings from that project are load-bearing here:

- **API Gateway cannot front a streaming Lambda.** Per that project's
  `docs/design/improvements/remove-tenant-http-api.md`, putting an HTTP API in front of a Lambda
  running LWA in `response_stream` mode made *every* request return HTTP 500, because the proxy
  integration could not parse the streaming response format. Their CLI masked it for a while by
  treating `DELETE` timeouts as success. They removed the HTTP API and made the Function URL the
  sole entry point.
- **A Function URL in `RESPONSE_STREAM` mode serves buffered responses correctly too.** This is
  why one function can serve both `POST /builds` and `GET /builds/{id}/logs`, rather than needing a
  split into a buffered function plus a streaming function.
- **Long operations do not fit API Gateway's integration timeout.** Their tenant provisioning takes
  3–8 minutes against a 29-second default, which is what drove the Function URL in the first place.
  Builds are longer still.

Two corrections to that project's stated *reasoning*, recorded here so they are not copied forward
as fact (the repo's own `.kiro/steering/tech.md` warns specifically about design-doc claims
propagating unverified):

- Their docs describe "a hard 29-second timeout" on API Gateway. The 29-second value is the
  *default* integration timeout; it can be raised via a Service Quotas increase, but only for
  Regional and private **REST** APIs, and AWS warns the increase "might require a reduction in your
  account-level throttle quota limit."
- Their docs state that API Gateway's proxy integration cannot parse LWA's streaming format. That
  is true of what they built, which was an **HTTP API** (`ApiGatewayV2`). Lambda response streaming
  *is* supported through an API Gateway **REST** API proxy integration; HTTP APIs do not support it.

Neither correction changes the decision — a Function URL is simpler, cheaper, and has no timeout
to negotiate — but the decision rests on "we chose not to trade account-wide throttle headroom for
a feature we don't need," not on an absolute platform limitation.

### What is given up

No AWS WAF, no custom domain without fronting CloudFront, no Cognito or Lambda authorizers, no
request validation or mapping templates, no usage plans or API keys, no per-method throttling, no
response caching, and only Lambda-level CloudWatch metrics rather than API-level access logs with
X-Ray integration. For an internal service where every caller is an AWS principal authenticating
with SigV4, none of these are load-bearing.

### When to add API Gateway

Adopt it if any of these become true:

- A browser UI needs to authenticate users who are not AWS principals (Cognito or OIDC).
- The endpoint must be public and needs WAF or a custom domain.
- Per-developer rate limiting is needed beyond a single global concurrency cap.
- The service moves inside `express-compute-control-plane`, inheriting its existing API Gateway for
  short operations.

To keep that additive, route shapes stay API-Gateway-compatible: conventional REST paths, no
reliance on Function-URL-specific behaviour beyond the IAM identity field noted below.

## API specification

Base URL: the Function URL (`https://<url-id>.lambda-url.<region>.on.aws`). Every request is
SigV4-signed for service `lambda`. Callers need `lambda:InvokeFunctionUrl` and
`lambda:InvokeFunction` on this function.

All request and response bodies are JSON unless stated. All timestamps are ISO-8601 UTC. Errors use
a consistent envelope:

```json
{ "error": "BuildNotFound", "message": "No build with id 01J8...", "requestId": "..." }
```

### `POST /builds` — register a build and negotiate uploads

Creates a build record in `PENDING` state and tells the client which inputs it still has to upload.
Does **not** launch anything.

The staging layer is content-addressed today (`StagingLayout`, and `S3StagingSink`'s dedup), so the
client sends a manifest of digests rather than the files, and the server replies with only the
digests it does not already hold. This preserves the existing dedup benefit across builds and
developers, and keeps the classpath upload cost near zero for unchanged dependency jars.

```json
{
  "buildSpec": {
    "buildKinds": ["native"],
    "architectures": ["x86_64", "arm64"],
    "mainClass": "ai.codriverlabs.example.HelloNative",
    "imageName": "hello-native",
    "nativeImageCommand": "native-image",
    "extraNativeImageArgs": ["-O2"],
    "extraBuildArgs": [],
    "timeoutMinutes": 0,
    "overallTimeoutMinutes": 120
  },
  "inputs": [
    { "path": "scaleout-build-example-app.jar", "sha256": "a1b2...", "sizeBytes": 5060 },
    { "path": "lib/commons-lang3-3.19.0.jar",   "sha256": "c3d4...", "sizeBytes": 709075 },
    { "path": "default.iprof",                  "sha256": "e5f6...", "sizeBytes": 120400 }
  ],
  "requestedResources": { "cpu": "4096", "memory": "16384", "ephemeralStorageGiB": 40 },
  "clientVersion": "scaleout-build-maven-plugin/1.1.0"
}
```

Response `201`:

```json
{
  "buildId": "01J8ZQ...",
  "state": "PENDING",
  "cells": [
    { "cell": "NATIVE/X86_64", "state": "PENDING" },
    { "cell": "NATIVE/ARM64",  "state": "PENDING" }
  ],
  "uploads": [
    { "sha256": "c3d4...", "method": "PUT", "url": "https://...", "expiresAt": "..." }
  ],
  "alreadyStaged": ["a1b2...", "e5f6..."],
  "heartbeatIntervalSeconds": 30,
  "expiresAt": "..."
}
```

`uploads` contains one presigned `PUT` per missing digest. `alreadyStaged` is informational and
lets the client report dedup hits. The client needs no S3 permissions — the presigned URL carries
the service's authority, scoped to one key.

Validation rejects a `buildSpec` whose `architectures` or `buildKinds` are not in the server's
allow-list, and a `requestedResources` outside the configured envelope (see "Resource policy").

### `POST /builds/{buildId}/start` — launch the matrix

Verifies every manifest digest is present in S3, then registers task definitions and launches one
task per matrix cell. Returns `202` immediately; launching is not synchronous with the response.

Returns `409 InputsMissing` with the outstanding digests if an upload did not complete. Returns
`409 InvalidState` if the build is not `PENDING`.

### `GET /builds/{buildId}` — status

```json
{
  "buildId": "01J8ZQ...",
  "state": "RUNNING",
  "ownerArn": "arn:aws:iam::864899852480:role/Developers",
  "createdAt": "...", "startedAt": "...", "updatedAt": "...",
  "cells": [
    { "cell": "NATIVE/X86_64", "state": "RUNNING", "taskArn": "...", "spotInterruptions": 0 },
    { "cell": "NATIVE/ARM64",  "state": "SUCCEEDED", "exitCode": 0,
      "artifacts": [{ "path": "hello-native", "sha256": "...", "sizeBytes": 13241624 }] }
  ]
}
```

Build states: `PENDING` → `STAGED` → `RUNNING` → `SUCCEEDED` | `FAILED` | `CANCELLED` | `EXPIRED`.
Cell states: `PENDING` → `PROVISIONING` → `RUNNING` → `SUCCEEDED` | `FAILED` | `CANCELLED`.

`taskArn` is deliberately included. It is an opaque troubleshooting handle, not something the client
acts on, and hiding it would make production incidents harder to diagnose for no security benefit
inside a single account.

### `GET /builds/{buildId}/logs` — SSE log stream

Query parameters: `cell` (optional; omit to interleave all cells, each event labelled), `since`
(optional ISO-8601 or epoch-millis watermark, exclusive).

`Content-Type: text/event-stream`. One event per log line or batch:

```
data: {"cell":"NATIVE/ARM64","timestamp":1789867513606,"message":"Finished generating 'hello-native' in 46.3s."}

data: {"cell":"NATIVE/ARM64","type":"cell-state","state":"SUCCEEDED"}

data: {"type":"stream-end","reason":"lambda-timeout","nextSince":1789867601234}
```

The stream terminates for one of three reasons, and the client must distinguish them:

- `build-terminal` — all cells reached a terminal state. Do not reconnect.
- `lambda-timeout` — the function is approaching its 15-minute ceiling. **Reconnect with
  `?since=nextSince`.**
- `payload-limit` — approaching the streamed-response size ceiling. Reconnect the same way.

The server emits a keepalive comment frame at least every 20 seconds so an idle stream is not
mistaken for a dead one.

**The watermark is per cell, not per stream.** This is not incidental. Commit `7782771` in this
repo fixed a bug where one watermark shared across two concurrent tasks' log streams caused
genuinely-new lines from the slower task to be discarded as already-seen — the ARM64-labelled
output carried 8 x86_64 mentions against 6 arm64 ones. The same failure is available here if
`nextSince` is a single scalar across cells. `nextSince` is therefore a map:

```json
{"type":"stream-end","reason":"lambda-timeout","nextSince":{"NATIVE/X86_64":1789867601234,"NATIVE/ARM64":1789867598001}}
```

### `DELETE /builds/{buildId}` — cancel

Stops every non-terminal cell's task and moves the build to `CANCELLED`. Idempotent: cancelling an
already-terminal build returns `200` with the existing state rather than an error.

Returns `200` only after `StopTask` has been *issued* for every running cell. It must not be
fire-and-forget: a cancel that silently no-ops leaves paid-for Fargate tasks running, which is
exactly the class of bug the tenant-service CLI hid by treating `DELETE` timeouts as success.

### `POST /builds/{buildId}/heartbeat` — liveness

The client calls this every `heartbeatIntervalSeconds` while it is waiting. The server records
`lastHeartbeatAt`. See "Orphan prevention".

### `GET /builds/{buildId}/artifacts` — download

Returns one presigned `GET` per produced artifact, per cell. The client needs no S3 permissions.
Presigned URLs expire; the client re-requests rather than caching them.

## Authorization and isolation

**Behind the Lambda Web Adapter the service is a plain HTTP server, so there is no Lambda event
object to read.** LWA forwards the Lambda request context as an `x-amzn-request-context` request
*header* containing the JSON that would otherwise have been `event.requestContext`. The verified
caller is `userArn` inside it. This is the mechanism `express-compute-control-plane`'s
`CallerIdentityFilter` uses, and it is worth stating explicitly because the obvious alternative —
reading `requestContext.authorizer.iam.userArn` from an event payload — only applies to a function
invoked *without* LWA.

Extraction therefore happens in a `ContainerRequestFilter`, parsing that header with Jackson (not
string scanning) and putting the normalized principal on the request context for downstream use.

**The header must fail closed.** It is attacker-controlled input in any deployment where the service
is reachable other than through the Function URL, because LWA is the only thing that guarantees it
was set by AWS rather than by the client. Two rules follow:

1. If the header is absent or unparseable, reject with `401` — do **not** proceed with a null owner.
   Proceeding is a fail-open path that would let an unauthenticated caller create builds owned by
   nobody, and then read them back by asking for the same null owner.
2. Local development, which has no LWA in front, must opt in explicitly (a dev-only profile
   supplying a fixed principal) rather than being served by the production code path treating a
   missing header as anonymous.

Assumed-role ARNs are normalized to a stable principal before comparison, so the same role always
resolves to the same owner regardless of session name:

```
arn:aws:sts::123:assumed-role/Developers/alice  →  arn:aws:iam::123:role/Developers
```

`ownerArn` is written on create and immutable. Every read, cancel, and artifact request checks it.
A build belonging to another principal returns `404`, not `403`, so the existence of other
developers' builds is not enumerable.

**This is what replaces the per-developer IAM policies.** Authorization moves from IAM resource
ARNs to an application-level `ownerArn` check, so *one shared IAM policy* granting
`lambda:InvokeFunctionUrl` + `lambda:InvokeFunction` on one function serves every developer. The
mechanism in `developer-test-infra-isolation.md` — N inline policies, a CI role with
`PutUserPolicy` restricted by two conditions, the deprovisioning question — is no longer needed.

One caveat to state plainly: normalizing to the role ARN means every developer who assumes the
*same* role shares one identity, and therefore sees each other's builds. If per-human isolation is
required, developers need distinct roles, or the session name must be included in `ownerArn` —
which then breaks the "same role resolves to the same owner" property for CI. This is an open item.

## Data model

One DynamoDB table, `scaleout-builds`:

| Attribute | Notes |
|---|---|
| `buildId` (PK) | ULID — sortable by creation time |
| `ownerArn` | normalized principal, immutable |
| `state` | build-level state |
| `cells` | list of per-cell records (state, `taskArn`, exit code, artifacts, spot interruptions) |
| `buildSpec` | as submitted, for reproducibility and debugging |
| `inputManifest` | digests, for `start` validation and dedup accounting |
| `createdAt` / `startedAt` / `updatedAt` / `lastHeartbeatAt` | |
| `ttl` | DynamoDB TTL, for record expiry well after the build ends |

GSI `owner-index`: PK `ownerArn`, SK `buildId` — for `list my builds` without a table scan.

Build *state* lives here, separate from build *logs* which stay in CloudWatch Logs. That separation
is what makes `GET /builds/{id}` cheap and the log stream resumable independently.

## Orphan prevention

Cancellation has three layers, because the first two are best-effort:

1. **Explicit cancel.** A Maven shutdown hook issues `DELETE /builds/{id}` on `Ctrl+C`. This is the
   normal path and gives local-build-like behaviour.
2. **Heartbeat expiry.** If `lastHeartbeatAt` is older than a configured threshold (default: 4
   missed intervals), the build is cancelled server-side. This covers `kill -9`, a closed terminal,
   a crashed JVM, and a lost network — all cases where the shutdown hook never runs.
3. **Absolute TTL.** Every build has a hard wall-clock deadline derived from
   `overallTimeoutMinutes`, capped by server policy. Reaching it cancels the build regardless of
   heartbeats.

Layers 2 and 3 run in a scheduled reaper (EventBridge rule, every minute) that scans for expired
builds and stops their tasks. This is a genuine addition over today's behaviour, not a port: today
the client *is* the supervisor, so a dead client and a dead supervisor are the same event. Once the
client is thin, nothing stops the task unless the server does.

Streaming makes this sharper. Per AWS's response-streaming documentation, "streamed responses are
not interrupted or stopped when the invoking client connection is broken. Customers are billed for
the full function duration." A client hanging up neither stops the streaming Lambda nor the build.
Client disconnect is therefore **not** a usable cancellation signal, and the design does not rely
on it.

## Resource policy

The four agent sizing parameters (`agentCpu`, `agentMemory`, `agentEphemeralStorageGiB`,
`maxSpotInterruptionsBeforeOnDemand`) move server-side as a policy envelope: the server defines
defaults and permitted maxima, and `requestedResources` is clamped to them rather than rejected
outright, with the applied values echoed in the `POST /builds` response. Uncapped client-specified
Fargate sizing is a cost-control hole once the client no longer pays for its own IAM.

Concurrency is capped by the function's reserved concurrency plus an explicit per-owner limit on
concurrent `RUNNING` builds, checked at `start`. Reserved concurrency alone is the wrong control —
it limits streaming connections, not Fargate tasks.

## Observability

- Structured JSON logs from the service, with `buildId` and `ownerArn` on every line.
- CloudWatch metrics: builds submitted/succeeded/failed/cancelled, cells per build, queue-to-start
  latency, build duration by cell, dedup hit ratio, reaper cancellations (a rising reaper count
  means clients are dying without cancelling — a real signal, not noise).
- An alarm on reaper cancellations and on `FAILED` rate.

## Testing strategy

Mirroring `express-compute-control-plane`'s approach, where `@QuarkusTest` runs the app as a normal
HTTP server and SSE behaves identically — `RESPONSE_STREAM` mode only matters in the real Lambda
runtime:

- **Unit** — `ownerArn` normalization (including the assumed-role and Identity Center shapes),
  state-machine transitions, resource clamping, manifest dedup diffing.
- **`@QuarkusTest`** — full HTTP surface against mocked ECS/S3/CloudWatch clients, including SSE
  framing, reconnect with `?since=`, and the per-cell watermark map. A regression test asserting
  that a reconnect after `lambda-timeout` loses no lines is mandatory, given commit `7782771`.
- **Integration** — DynamoDB Local for the table and GSI.
- **End-to-end** — against real AWS using `scaleout-test-infra`, driving the example app through the
  service. The existing E2E assertion stays: both artifacts must be genuine, distinct
  `ELF x86-64` and `ELF ARM aarch64` binaries.

## Security posture

`native-image` executes arbitrary third-party dependency code at build time. Today that risk sits
in each developer's own blast radius; behind this service it becomes the service's shared ECS
cluster. For internal developers, Fargate task-level isolation is adequate — this is the same
judgement `developer-test-infra-isolation.md` made when it chose total isolation over ring-fencing,
except the boundary is now per-build rather than per-developer.

It stops being adequate the moment anything untrusted submits a build. If this is ever exposed
beyond the internal team — including via the EKS or eks-d-xpress/k3s-xpress variants — the
execution backend needs genuine VM-level isolation, which is the strongest argument for the MicroVM
backend and makes it less optional than "v2" implies.

The service role is the new concentration of privilege: it holds `ecs:RunTask`, `iam:PassRole` for
the task and execution roles, S3 read/write on the staging bucket, and CloudWatch Logs read. Its
`iam:PassRole` must be conditioned on the specific task/execution role ARNs, otherwise the service
becomes a generic privilege-escalation primitive for anyone who can invoke it.

## Open items

- **Per-human vs per-role ownership** (see "Authorization and isolation"). Needs a decision before
  the model is baked into the table.
- **Backend abstraction seam.** ECS today, MicroVM later. Whether that is an interface inside the
  service or a separate dispatch service is not decided, and should not be over-abstracted before
  the second backend exists.
- **Log transport.** The design assumes the service reads CloudWatch Logs and re-emits as SSE,
  reusing `CloudWatchLogTailer`'s semantics. An alternative is the agent writing log chunks to S3
  and the service streaming those, which removes the CloudWatch read path and its cost but adds
  latency. Not evaluated.
- **Artifact retention.** How long staged inputs and produced binaries live in S3, and whether
  content-addressed inputs are ever evicted, is undecided. Dedup value argues for keeping them;
  cost argues for a lifecycle policy.
- **Queueing.** At the per-owner concurrency cap, `start` currently rejects. Whether it should
  queue instead is undecided; rejecting is simpler and honest, queueing is friendlier.
- **Repository placement.** See the migration document's "Repository placement" section.

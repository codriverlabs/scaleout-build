# Security Policy

## Reporting a vulnerability

Email **support@codriverlabs.ai** with the subject prefix `[Security]`.

**Do not open a public issue for a security vulnerability.** This repository is public, and an issue
describing an unpatched weakness is a disclosure to everyone who reads it.

We aim to acknowledge security reports within **48 hours**.

Useful things to include, if you have them:

- what an attacker can do, not only what the code does
- the version, tag, or commit you tested
- whether it affects the Maven plugin (runs on a developer machine), the control plane (runs in your AWS
  account), or the build agent (runs as a short-lived container)

## What is in scope

This project has three components with very different exposure, and it is worth saying which is which.

| Component | Where it runs | Notes |
|---|---|---|
| `scaleout-build-maven-plugin` | Developer machine or CI | Holds AWS credentials; talks only to the control plane |
| Control plane (Lambda) | **Your** AWS account | Holds the ECS, S3 and CloudWatch permissions; internet-facing endpoint |
| Build agent (container) | **Your** ECS Fargate tasks | Executes a build described by its caller |

The control plane's endpoint is the interesting surface: it is a Lambda Function URL with
`AuthType: AWS_IAM`, so every request must carry a valid SigV4 signature. Reports about authentication,
authorisation, or cross-tenant isolation on that endpoint are the highest priority.

**Particularly interested in:**

- Any path by which one owner can read, write, or influence another owner's builds, staged inputs, or
  artifacts. Isolation is derived server-side from the caller's identity (`ownerHash`) and is never taken
  from client input — a way to bypass that is a serious bug.
- Anything that lets a caller cause the control plane to act outside the two IAM permissions it asks of
  clients (`lambda:InvokeFunctionUrl`, `lambda:InvokeFunction`).
- Privilege escalation through the task role, the execution role, or `iam:PassRole`.
- Argument or path injection through build inputs — the agent runs `native-image` with arguments derived
  from a project, and a project is attacker-controlled input if you build untrusted code.

## What is out of scope

- **Building untrusted code is not sandboxing.** The agent compiles what it is given, and a build is
  arbitrary code execution by design. If you build a hostile project, it runs in your task. Use a
  separate AWS account for that; do not report it as a vulnerability in this project.
- Cost: a caller with valid credentials can launch builds and therefore spend money. That is the feature.
  Rate limiting is the deployer's responsibility.
- Findings that require AWS credentials you were legitimately given, with no crossing of an isolation
  boundary.
- Vulnerabilities in GraalVM, Mandrel, Quarkus, or the AWS SDK. Report those upstream; tell us if a
  version we pin is affected and we will bump it.

## Supported versions

This project has not yet reached a stable release. Fixes land on `main`, and the most recent tag is the
only supported version. There are no backports.

## Deployment hardening

The control plane is deployed into your own account, so several security properties are yours to set. The
ones worth checking are in
[`docs/design/control-plane/storage-layout-and-isolation.md`](docs/design/control-plane/storage-layout-and-isolation.md)
and [`docs/design/control-plane/sigv4-client-signing.md`](docs/design/control-plane/sigv4-client-signing.md).

Two defaults worth knowing about:

- The staging bucket and the build-state table are created with `RemovalPolicy.RETAIN`, so destroying the
  stack leaves them — including any staged source. Delete them deliberately.
- Clients need exactly two IAM permissions and no ECS, S3, CloudWatch, or ECR access. If your developers
  currently hold broader permissions for native builds, those can be revoked; leaving them granted defeats
  the point.

# User Guide: Agent Sandboxes

Hosted agent sandboxes — [Kiro cloud sessions](https://kiro.dev/docs/cloud-sessions/), a CI container, any
per-session MicroVM — are a good fit, and for `arm64` usually the *only* fit.

**If the sandbox is `x86_64` and you cannot choose otherwise, `arm64` is not producible inside it.** GraalVM
does not cross-compile, and QEMU emulation of a compile that saturates 4 vCPU for minutes is not a practical
substitute. Kiro cloud sessions are `x86_64` in `us-east-1` with no architecture selection (observed, not
published), which is the common case rather than an unusual one.

Offloading also means the client needs **no GraalVM or Mandrel toolchain**:

```
-Dscaleout-build.forceRemote=true
```

Every cell then goes to the control plane and the local `native-image` path is never reached. The build
environment is the agent container image in ECR, pinned and multi-arch, so the sandbox does not have to
reproduce a toolchain it was never given.

The sandbox needs:

| | |
|---|---|
| Maven and a JDK 17+ | for the client and, with Quarkus, augmentation |
| `scaleout-build.endpoint` | the control plane Function URL |
| AWS credentials with the two IAM permissions | set as sandbox environment variables |
| Egress to the Function URL | a public HTTPS endpoint with `AWS_IAM` auth |

**Deploy the control plane in the same region as the sandbox.** Otherwise every artifact download crosses a
region boundary and is billed as inter-region transfer — about 256 MB per two-architecture build.

Trade-offs for this topology, including what offloading does *not* save when sandbox compute is bundled into a
subscription, are in [`COST_ANALYSIS.md`](../COST_ANALYSIS.md) §8.

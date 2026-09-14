# Per-developer test infra isolation via GitHub Actions + OIDC

## Status: Planned — not yet implemented

## Problem

`scaleout-test-infra`'s CDK stack (`ScaleoutBuildTestInfra`) currently deploys as a single,
hardcoded stack: one VPC, one ECS cluster, one S3 staging bucket, one ECR repo, one set of
task/execution roles. If multiple developers each deploy and use this stack to exercise
`aws-ecs:build` against real AWS, they share all of it — the same cluster, the same bucket, the
same IAM roles.

`native-image` (and build tooling generally) executes arbitrary third-party dependency code at
build time. A compromised or buggy dependency in one developer's build is a real, non-hypothetical
supply-chain risk, not just a noisy-neighbor annoyance. The question this document answers: **when
one developer's build goes wrong, what can it touch?** Today, the answer is "everything in the
shared stack." This document describes moving to **total isolation**: each developer gets their
own ECS cluster and S3 bucket, and can only see and use their own.

## Decision: total isolation, not a shared/ring-fenced cluster

Two models were considered:

1. **Ring-fenced single cluster** — one shared cluster and bucket, with per-developer access
   scoped via S3 key prefixes and IAM condition keys tied to a principal tag.
2. **Total isolation** — each developer gets their own cluster, own bucket, own ECR repo, own
   task/execution roles. Chosen.

Ring-fencing a single shared cluster requires real, easy-to-get-subtly-wrong IAM engineering: the
existing `StagingLayout`/`S3ArtifactRetriever` code keys everything off a `buildId` UUID, not
developer identity, so as written *any* task role can read any other developer's build prefix in a
shared bucket. Making that safe means retrofitting prefix-scoped bucket policies and
principal-tag-conditioned IAM statements across code that was never designed for multi-tenancy
within one stack — and getting it wrong looks fine right up until someone actually tests the
boundary.

Total isolation needs none of that: the CDK stack already provisions "one full environment per
deployment," so per-developer isolation is mostly a naming/parameterization change, not new
security logic layered on top of shared resources.

## VPC is shared; everything else is per-developer

The default AWS quota is 5 VPCs per region. Deploying a new VPC per developer risks hitting that
ceiling as the team grows, and buys nothing security-relevant here — ECS tasks are already isolated
from each other by security group and there's no cross-cluster networking requirement. The existing
VPC (public subnets only, no NAT gateway — see `scaleout-test-infra/README.md`) is deployed once and
shared. Every other resource — ECS cluster, S3 bucket, ECR repo, task role, execution role, log
group — is deployed per-developer, in its own stack.

## Provisioning mechanism: GitHub Actions + OIDC, not a self-service Lambda

`express-compute-control-plane` (a sibling project) uses a tenant-provisioning Lambda gated by an
IAM permissions boundary, because it provisions per-*external-customer* tenants on demand as a
product feature — frequent, on-demand, used by non-engineers. That complexity is justified there.

For this project, provisioning happens rarely, by engineers who already have AWS and CI access, for
a handful of internal developers. A GitHub Actions workflow that runs `cdk deploy` under a role
assumed via OIDC federation solves this with infrastructure that already exists (CDK, the existing
stack), no new Lambda/API surface to build and operate, and no long-lived AWS credentials stored as
GitHub secrets — GitHub issues a short-lived OIDC token per workflow run, AWS trusts it via an OIDC
identity provider, the workflow's job assumes a role scoped to only what this deploy needs.

## What one deploy actually produces

Triggered via `workflow_dispatch` with a developer identifier as input. The workflow (and the CDK
stack it invokes) does the following, atomically, in one `cdk deploy`:

1. **Derives a short, deterministic hash from the developer identifier** (git-style short hash) —
   used purely for collision-free resource naming (S3 bucket names must be globally unique; picking
   names by hand doesn't scale and is error-prone). This hash is a naming convenience, not a
   security mechanism.
2. **Creates the per-developer stack**: `ScaleoutBuildTestInfra-<developer>` — ECS cluster, S3
   staging bucket, ECR repo, task role, execution role, log group. Every resource tagged
   `developer=<developer>`.
3. **Grants that developer's *existing* AWS IAM identity** scoped access to the resources just
   created — see the next section for why this step exists and how it's expressed. This is not a
   separate process or a separate deploy; it's one more resource in the same CDK stack, imported by
   ARN rather than created fresh.

## Why granting the developer's IAM identity is unavoidable, and where it differs by service

For Alice to run ECS tasks or read/write her S3 bucket, **the permission has to be attached to
Alice's own IAM identity** (her IAM user or the role she assumes) — not to the cluster or the
bucket. This isn't a design choice; it follows directly from how each AWS service exposes access
control:

- **S3 supports resource-based policies.** A bucket can carry its own policy saying "only
  `arn:aws:iam::<account>:user/alice` may `GetObject`/`PutObject` here." This is attached directly
  to the bucket, in the same CDK stack, without touching Alice's IAM identity at all.
- **ECS has no equivalent.** There is no "cluster policy." The *only* place the permission for
  "who can call `ecs:RunTask`/`DescribeTasks`/`StopTask` on this cluster" can live is on the calling
  identity, with a resource ARN condition scoping it to that one cluster. There is no way around
  this for ECS specifically.

So the same CDK stack, in addition to creating the cluster and bucket, also:

- Attaches an **S3 bucket policy** (resource-based) restricting the bucket to Alice's principal ARN.
- **Imports Alice's existing IAM user by ARN** (`User.fromUserArn`, not `User(...)` — her IAM user
  already exists; this stack does not create IAM users for developers) and attaches a scoped inline
  policy to it: `ecs:RunTask`/`DescribeTasks`/`StopTask` restricted to her cluster ARN,
  `ecr:GetAuthorizationToken` (registry-level action, requires `Resource: "*"` per AWS's own API
  shape — this cannot be narrowed further), plus `ecr:BatchGetImage`/`GetDownloadUrlForLayer`
  restricted to her repo ARN.

One deploy, one atomic outcome: infrastructure plus the grant that makes it usable, both scoped to
one developer.

## Scoping the CI role itself: attach/detach restricted to one named policy

Granting a developer's IAM identity access from CI means the CI role now has the power to modify
*any* developer's IAM permissions, not just create infrastructure — a meaningfully larger blast
radius than "can run `cdk deploy`." This needs its own restriction, and naming alone is not
sufficient; two independent conditions are both required:

1. **Which policy CI may attach/detach.** The CI role's own IAM policy allows
   `iam:AttachRolePolicy`/`iam:PutUserPolicy`/`iam:DetachRolePolicy`/`iam:DeleteUserPolicy` only when
   the target policy name matches a fixed naming pattern — e.g. `ScaleoutBuildDeveloper-*`. This
   stops CI from ever attaching something broader (`AdministratorAccess`, or any other pre-existing
   managed policy) even if the workflow or its inputs were compromised.
2. **Which principal CI may attach it to.** Restricting the policy name alone does **not** restrict
   *who* CI can attach it to — without a second condition, CI could still attach
   `ScaleoutBuildDeveloper-*` to an arbitrary IAM user, not just the intended developer. This needs
   its own resource-ARN condition (e.g. `user/scaleout-build-dev-*`, if developers follow a naming
   convention, or an explicit allow-list of principal ARNs if not).

Both conditions must hold together. Restricting only the policy name is necessary but not
sufficient on its own.

### One policy per developer, not one shared policy with variables

Two ways to shape `ScaleoutBuildDeveloper-*`:

- **One managed policy shared by everyone**, using IAM policy variables (`${aws:username}`) so each
  attached principal is automatically scoped to their own resources by the variable substitution.
- **One inline policy per developer** (`ScaleoutBuildDeveloper-alice`, `ScaleoutBuildDeveloper-bob`,
  ...), each individually scoped by name at creation time to that developer's specific cluster and
  repo ARNs.

The per-developer model is chosen. It is more resources, but it is what actually delivers "total
isolation" as an auditable property — "did Alice's grant leak into Bob's?" is answerable by reading
one named policy, rather than by reasoning about how a policy variable resolves at runtime across
every principal it might ever be attached to. The shared-with-variables approach is cleverer and
uses fewer resources, but is easier to get subtly wrong and harder to audit at a glance.

## OIDC trust policy shape

The CI deploy role's trust policy uses `token.actions.githubusercontent.com` as the OIDC identity
provider, with a `sub` claim condition restricting which workflow may assume it — e.g.
`repo:codriverlabs/scaleout-build-maven-plugin:ref:refs/heads/main`, or narrower (restricted to
`workflow_dispatch` specifically) if the workflow trigger allows expressing that in the claim. No
long-lived AWS access keys are stored as GitHub secrets under this model.

## Open items (not yet decided)

- Exact developer-identifier-to-IAM-principal mapping: is the `workflow_dispatch` input the IAM
  principal ARN directly, the IAM username, or a separate identifier (e.g. GitHub username) that
  needs a lookup step? Assumed for now: developers already have an IAM user in this account (
  confirmed true for at least one developer as of this writing), and the input is that IAM username
  or ARN directly.
- Exact permissions-boundary shape for the CI deploy role's IAM-creation actions (the
  `TaskRole`/`ExecutionRole` it creates per-developer) — not yet designed; should mirror
  `express-compute-control-plane`'s tenant-role-boundary pattern
  (`docs/roadmap/security-hardening/tenant-iam-boundary-vpc-scoping.md` in that project) rather than
  being designed from scratch.
- Whether the deprovisioning path (`cdk destroy` for a departing developer) also needs to detach
  the inline policy from their IAM identity as an explicit step, or whether destroying the stack
  alone suffices (it does not — the inline policy is attached to the developer's IAM identity, not
  to the stack's own resources, so it will not be cleaned up by `cdk destroy` alone unless it is
  itself a stack-managed resource attached via CDK, which removes it on stack deletion by
  construction. This should be verified once implemented, not assumed.).
- CDK code changes needed: `InfraApp`/`BuildTestInfraStack` parameterization for developer identity,
  the hash-based naming scheme, the imported-principal inline policy, and the S3 bucket policy —
  none of this is implemented yet as of this document.

## Related documents

- `scaleout-test-infra/README.md` — current (pre-isolation) stack behavior, region-resolution
  gotcha (`AWS_REGION` vs `CDK_DEFAULT_REGION`), deploy/destroy procedure.
- `express-compute-control-plane/docs/roadmap/security-hardening/tenant-iam-boundary-vpc-scoping.md`
  — the permissions-boundary and tag-enforcement pattern this document's CI-role scoping borrows
  from, applied to a different actor (a CI role instead of a tenant-provisioning Lambda).

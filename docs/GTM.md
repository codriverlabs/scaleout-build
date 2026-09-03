# Fargate Build Matrix Maven Plugin — Plain-English Overview & GTM Notes

This is the non-technical companion to `docs/DESIGN.md`. It explains what the plugin
does without jargon, who it's for, how to talk about it, and how it compares to the
alternatives a prospective user is already weighing. Cost figures cite AWS's own
published pricing rather than third-party estimates; anyone quoting a specific dollar
saving to a customer should run their own numbers through the AWS Pricing Calculator or
this project's `aws-pricing-mcp-server` tooling first — the figure below is AWS's stated
discount range, not a guarantee.

## 1. What this actually is, in one paragraph

If your Java project builds native binaries with GraalVM — for AWS Lambda, for a Docker
image, for a CLI tool you ship to customers — you eventually need a binary for both
Intel/AMD machines (`x86_64`) and ARM machines (`arm64`), plus maybe a plain JVM jar, and
maybe a profile-optimized build for production. GraalVM can't build for an architecture
other than the one it's running on, so today that usually means either a second build
machine, a CI matrix with self-hosted ARM runners, or skipping one architecture
entirely. This plugin lets `mvn package` do it for you: it borrows short-lived,
spot-priced AWS compute for exactly as long as the build takes, runs every combination
you've asked for in parallel, and hands you back all the binaries — with none of that
compute left running afterward.

## 2. The problem, restated without acronyms

- GraalVM's native compiler produces a binary for the machine it's running on. It cannot
  target a different chip architecture. This isn't a bug or a missing flag — it's how
  the underlying compilation works.
- A team that wants to ship both an Intel/AMD binary and an ARM binary — increasingly
  common, since ARM (AWS Graviton, Apple Silicon) is now often the cheaper or faster
  choice — needs to build on both kinds of machine.
- Options today are: buy/rent a second machine of the other architecture, set up a CI
  pipeline with a matrix of runners (which many teams already do for exactly this
  reason, the same way GitHub Actions lets you fan out a build across a matrix of
  operating systems and versions), or just not ship the second architecture and hope
  customers on it don't mind the JVM fallback.
- Native-image builds are also memory-hungry and slow — often the single longest step in
  a Java build — so even for one architecture, offloading them to a bigger, disposable
  machine instead of tying up a laptop or a CI runner is often worth it on its own.

## 3. What the plugin does about it

One Maven command computes a build matrix — which architectures, which build flavors
(plain JVM, plain native, profile-optimized native) — the same idea as a GitHub Actions
matrix, just triggered from your own machine instead of requiring a CI provider. For
every cell that can't be satisfied locally, it launches a short-lived container on AWS
Fargate, using **Spot** capacity (AWS's spare, discounted compute) by default, runs the
build there, and streams the result back. When the build finishes, the containers are
gone and you're not paying for them anymore.

If AWS reclaims the Spot capacity partway through (which does happen — that's the
tradeoff for the discount), the plugin notices and relaunches automatically. You don't
see the interruption unless every retry runs out.

## 4. Why Fargate Spot specifically

AWS's own published pricing states Fargate Spot runs interruption-tolerant container
tasks "at up to a 70% discount off the regular Fargate price" ([AWS Fargate
pricing](https://aws.amazon.com/fargate/pricing/)). A native-image build is exactly the
kind of interruption-tolerant workload that discount is aimed at: if it gets interrupted,
you just build it again — nothing is lost, no user-facing request was in flight, no
state needs recovering. That's a genuinely good match, not a stretch.

The other reason Fargate specifically, not "some EC2 instance": there's no server to
patch, size, or leave idle. The container exists for the duration of one build and then
doesn't. For a build tool used intermittently — a few times a day, not continuously —
paying only for the seconds actually spent building is a better fit than maintaining an
always-on machine of each architecture, whatever the discount on that machine would be.

## 5. Who this is for

- **Java teams shipping native binaries across architectures** — AWS Lambda functions
  that need to run on Graviton (arm64) as well as x86_64, CLI tools distributed to
  users on both Intel/AMD and Apple Silicon/ARM machines, multi-arch Docker images.
- **Teams already doing this manually today** with a CI matrix, self-hosted ARM
  runners, or a second physical/cloud build box — this plugin is a way to get the same
  outcome without owning that infrastructure, and without needing a CI provider at all
  if the team would rather trigger it from a developer machine or a simple `mvn`-based
  pipeline step.
- **Teams doing profile-guided optimization (PGO) builds** — GraalVM's PGO workflow
  needs an instrumented build and, separately, a final build fed a profile collected
  from running that instrumented build against real traffic. This plugin handles both
  build steps (instrument, and build-from-profile); it does not handle the "run the
  instrumented binary against production traffic to collect the profile" part — that
  stays whatever canary/blue-green process a team already uses, deliberately out of
  scope.

## 6. Positioning against the alternatives

**Vs. a GitHub Actions (or other CI) build matrix.** Not a competitor — complementary,
or a substitute depending on the team. A CI matrix job could call this same plugin
instead of hand-rolling `native-image` invocations per runner. A team without a CI
pipeline that wants matrix-style parallel builds, or one that doesn't want to run
self-hosted ARM CI runners, gets the same fan-out from a plain `mvn` invocation instead.
This plugin is the CI-matrix idea implemented as a Maven goal against your own AWS
account, not a hosted CI replacement.

**Vs. cross-compilation.** Doesn't exist for GraalVM native-image — this is not "we
built something faster than cross-compiling," it's "cross-compiling isn't an option, so
here's a way to get both architectures anyway without owning both kinds of hardware."

**Vs. maintaining a dedicated ARM (or x86) build box.** No idle cost between builds, no
box to patch, no capacity planning — you pay Fargate Spot rates for the minutes actually
spent building, and only when a build is running.

**Vs. doing nothing about the second architecture.** The honest alternative many teams
are actually choosing today. This plugin's pitch to that team specifically: the
"other" architecture doesn't have to stay a JVM-only fallback if getting a native build
for it is one Maven flag away instead of a build-infrastructure project.

## 7. Elevator pitches (pick the length that fits)

**One line:** Ship native binaries for every architecture your customers run, from one
`mvn package`, on borrowed AWS Fargate Spot capacity you only pay for while it's
building.

**Two sentences:** GraalVM can't cross-compile, so shipping both x86 and ARM native
binaries usually means owning build machines of both kinds. This plugin builds your
whole matrix — architectures, plain vs. native vs. profile-optimized — in parallel on
short-lived, discounted AWS Fargate Spot containers, and attaches the results to your
build like any other Maven artifact.

**One paragraph (for a README or landing page):** Native-image builds are slow,
memory-hungry, and architecture-locked — you can only build for the chip you're building
on. This Maven plugin removes the architecture lock by computing your build matrix
(architectures, plus plain-JVM, plain-native, and PGO-optimized flavors) and running
each cell you need in parallel on AWS Fargate Spot, AWS's spare compute at up to a 70%
discount versus on-demand pricing. Runs land in your own AWS account. Interrupted Spot
tasks are relaunched automatically. When the build finishes, the compute is gone and the
binaries are in your `target/` directory, classified by architecture and build kind,
ready to attach to your release.

## 8. Key benefits, plain language

- **Build for architectures you don't own hardware for.** No ARM box required to ship
  an ARM binary.
- **Pay only while building, not for idle capacity.** No server, no reserved instance,
  no monthly bill for machines sitting between builds.
- **Runs in your own AWS account.** Nothing about your source code or build artifacts
  goes to a third-party service; the compute is your Fargate, the storage is your S3.
- **Automatic recovery from Spot interruptions.** The discount's tradeoff (capacity can
  be reclaimed) is handled for you — a reclaimed build relaunches without manual
  intervention.
- **Same mental model as a CI build matrix**, so teams that already think in those terms
  (GitHub Actions, GitLab CI matrices) don't have to learn a new concept, just a new way
  to trigger it.
- **Profile-guided optimization support**, without the plugin overreaching into
  production traffic management it has no business doing.

## 9. Plain-English FAQ

**Do I need to change my AWS account setup?** You need an ECS cluster, a VPC, and an S3
bucket the plugin can use — standard AWS building blocks most teams doing any
container work already have, or that a one-time setup step provisions. The plugin
doesn't require a new AWS account or a vendor-managed environment; it runs inside yours.

**What happens if AWS reclaims the Spot capacity mid-build?** The build is relaunched
automatically. You'd only notice if it happened repeatedly enough to exhaust the retry
budget, at which point the plugin falls back to full-price (on-demand) Fargate capacity
rather than failing the build outright.

**Does this replace my CI pipeline?** No. It's a Maven goal you can call from anywhere —
a developer's machine, a CI job, a script. Teams with a CI pipeline can call it from
inside a CI job instead of hand-rolling the matrix fan-out themselves.

**How much could this save me?** AWS states Fargate Spot runs at up to a 70% discount
versus standard Fargate pricing for interruption-tolerant workloads, which a build like
this is. That's AWS's number for the compute itself, not a promise about your specific
bill — actual savings depend on how often you build, how long builds take, and current
Spot pricing in your region, which is why we point people at the AWS Pricing Calculator
for a number specific to them rather than quoting one here.

**Does it work with GitHub Actions / GitLab CI / etc.?** Yes — it's a Maven plugin, so
anything that can run `mvn` can call it, including inside a CI job. It doesn't require
or assume any particular CI provider.

**What about profile-guided optimization (PGO)?** The plugin builds the instrumented
binary you'd deploy to collect a profile, and separately builds the final optimized
binary once you have that profile. It does not run your canary/blue-green rollout or
collect the profile itself — that stays your process, using whatever traffic-shaping
approach you already trust for production.

## 10. What to avoid claiming

- Don't claim cross-cloud portability — this plugin is AWS-specific (Fargate, ECS, S3,
  Step Functions). If a prospect asks about GCP/Azure, be direct: this is an AWS-native
  tool, not a multi-cloud abstraction.
- Don't quote a specific dollar savings figure without running the prospect's actual
  numbers — cite AWS's published "up to 70%" range and offer to work out their number,
  don't invent one.
- Don't imply this replaces a CI provider — it's a build step, not a pipeline
  orchestrator, issue tracker, or artifact registry.
- Don't imply PGO profile collection (the production traffic-replay part) is handled —
  it explicitly isn't, by design, and overclaiming here would set a real expectation
  this tool doesn't meet.

# Consolidating the control-plane HTTP clients

## Status: Planned — not yet implemented

## Problem

The control plane has three consumers — the Maven plugin, the CLI, and the MCP server — and today
there are **two independent, hand-written HTTP client implementations** behind them, discovered
while closing a test-coverage gap on one of the two
([`unit-test-strategy.md`](../testing/unit-test-strategy.md)):

| Class | Module | Consumers |
|---|---|---|
| `ControlPlaneClient` (package-private) | `scaleout-build-maven-plugin` | the Maven plugin only |
| `ControlPlaneHttpClient` (public) | `scaleout-build-cli` | the CLI, **and** the MCP server (which depends on `scaleout-build-cli` solely to reach this class via `ControlPlaneClientProducer`) |

Both are built the same way, independently: `java.net.http.HttpClient`, the shared `SigV4Signer`
from `scaleout-build-control-plane-api`, a Jackson `ObjectMapper` configured identically
(`JavaTimeModule`, lenient deserialization), and the same "non-2xx → throw with status and body"
error shape. That core — connection setup, signing, (de)serialization, error mapping — is
duplicated, not divergent for a good reason; each copy was written for whichever consumer needed it
first, and nobody has gone back to unify them since.

Where they genuinely differ is call surface, not plumbing:

| Capability | `ControlPlaneClient` (plugin) | `ControlPlaneHttpClient` (CLI/MCP) |
|---|---|---|
| `createBuild`, `status`, `cancel` | ✅ typed DTOs in/out | ✅ raw JSON `String` in/out |
| `startBuild`, `heartbeat` | ✅ | ❌ not called from the CLI |
| `streamLogs` (SSE, per-cell watermarks) | ✅ | ❌ |
| `putPresigned` / `getPresigned` (artifact transfer) | ✅ | ❌ — the CLI never uploads/downloads build inputs/outputs itself |
| Admin / MicroVM endpoints (`triggerImageBuild`, `getImageStatus`) | ❌ the plugin never calls these | ✅ |
| Return shape | Typed DTOs (`BuildStatus`, `CreateBuildResponse`, ...) | Raw `String`, caller re-parses with `ControlPlaneHttpClient.MAPPER` as needed |

Every DTO either client needs — `BuildStatus`, `CreateBuildResponse`, `ArtifactListResponse`,
`ErrorResponse`, `LogEvent`, and `AdminApi.TriggerImageBuildRequest` /
`AdminApi.ImageBuildResponse` — is **already public** in `scaleout-build-control-plane-api`, on
every consumer's classpath transitively today. The CLI client returning raw `String` and
re-parsing with a hand-rolled `TriggerBody` private record is working around DTOs that already
exist one module away, not around a real gap in the wire contract.

## Why this isn't a JAX-RS-vs-hand-written question

Both classes' javadocs independently justify *not* using a JAX-RS client:

> Deliberately not a JAX-RS REST client: that would put a JAX-RS implementation and its transitive
> dependencies on the classpath of every Maven build using this plugin, to gain nothing — the
> plugin makes six calls with hand-held bodies.

That reasoning is sound and the consolidation does not revisit it — the new shared client stays on
plain `java.net.http.HttpClient`, no JAX-RS client runtime anywhere. The javadoc explains why there
should not be a *third* implementation choice (JAX-RS); it says nothing about why there needs to be
more than *one* hand-written implementation. Three consumers reusing one lightweight, dependency-free
client module satisfies the original constraint exactly as well as three consumers each reusing
their own copy — the weight concern was about the implementation technology, not about sharing code.

## Decision: extract `scaleout-build-control-plane-client`

A new Maven module, sibling to `scaleout-build-control-plane-api`, holding exactly one class:
`ControlPlaneClient`, public, covering the union of both existing clients' call surface, returning
typed DTOs everywhere a DTO already exists in `-api`.

### Why a new module rather than putting it in `-api` itself

`scaleout-build-control-plane-api`'s own description is explicit about its scope: *"Deliberately
free of server or AWS-orchestration logic so that consuming the contract does not drag in an
implementation."* It is consumed by the **service implementation** too
(`scaleout-build-control-plane`, the Quarkus app), which has no business depending on
`java.net.http.HttpClient` plumbing, retry/timeout tuning, or SSE log-stream parsing — none of
which it needs, since it's the server, not a caller. Putting the client in `-api` would mean the
service implementation transitively carries client-side networking code it never calls, inverting
`-api`'s one deliberate property. A new module keeps `-api` exactly as described and gives
"the control-plane client" a home that is reusable without being load-bearing for the server.

### Why a new module rather than `scaleout-build-cli` as-is

This was the status quo for MCP (`scaleout-build-mcp` depends on `scaleout-build-cli` to reach
`ControlPlaneHttpClient`) and it is backwards: a protocol server pulling in an entire CLI module —
picocli, command classes, `ScaleoutCli`'s argument parsing — for one HTTP client class. The new
module has none of that; it is the client and nothing else, so MCP (and the plugin) depend on
exactly what they use.

## Module shape

```
scaleout-build-control-plane-client/
├── pom.xml
└── src/main/java/ai/codriverlabs/scaleoutbuild/controlplane/api/client/
    └── ControlPlaneClient.java
```

**Package note:** placed under `ai.codriverlabs.scaleoutbuild.controlplane.api.client` —
the same package `SigV4Signer` and `SigV4RequestFilter` already live in, within `-api`. This is
not cosmetic: the CLI's native-image config
(`scaleout-build-cli/src/main/resources/META-INF/native-image/.../native-image.properties`) sets
`--initialize-at-run-time` keyed by that exact package name, not by module or by class. A client in
the same package inherits that native-image treatment automatically; a different package would
need its own reflection/initialize-at-run-time entry discovered the hard way, at a native-image
build failure, the same way the original `--initialize-at-run-time` need was discovered.

### Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>ai.codriverlabs</groupId>
        <artifactId>scaleout-build-control-plane-api</artifactId>
        <version>${project.version}</version>
    </dependency>
    <!-- auth, http-auth-aws, regions, jackson-databind: compile scope here, not optional.
         This module exists specifically to be the client; unlike `-api`, which stays
         implementation-agnostic for a server consumer, every consumer of this module is a client
         by definition and needs these at runtime. -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>auth</artifactId>
    </dependency>
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>http-auth-aws</artifactId>
    </dependency>
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>regions</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>
</dependencies>
```

This removes the `<optional>true</optional>` dance for these four dependencies specifically for
this module's consumers: `-api` keeps them optional (so the service implementation doesn't inherit
them transitively), but the new client module declares them required, because every single
consumer of *this* module needs them. The Maven plugin's existing pom already repeats
`http-auth-aws` explicitly for this exact reason (`-api` not providing it transitively since it's
optional there) — this change makes that repetition unnecessary everywhere.

## Target API surface

Public, not package-private (unlike the plugin's current `ControlPlaneClient`, which is
package-private because it only had one caller). One class, covering the union of both current
clients' calls:

```java
public final class ControlPlaneClient {

    public ControlPlaneClient(String endpoint) { /* region inferred from the Lambda Function URL,
                                                      same as today's plugin client */ }

    // ── Build lifecycle — typed, matches BuildApi's wire contract exactly ──────────────────────
    public CreateBuildResponse createBuild(CreateBuildRequest request) throws ControlPlaneClientException;
    public BuildStatus startBuild(String buildId) throws ControlPlaneClientException;
    public BuildStatus status(String buildId) throws ControlPlaneClientException;
    public ArtifactListResponse artifacts(String buildId) throws ControlPlaneClientException;
    public BuildStatus heartbeat(String buildId) throws ControlPlaneClientException;
    public BuildStatus cancel(String buildId) throws ControlPlaneClientException;
    // List<BuildStatus>, confirmed against BuildService.listMine()'s own return type and
    // BuildResource.listBuilds() wrapping it directly -- the wire body is a plain JSON array of
    // BuildStatus, no envelope -- rather than assumed.
    public List<BuildStatus> listBuilds(int limit) throws ControlPlaneClientException;

    // ── Log streaming ───────────────────────────────────────────────────────────────────────────
    public LogEvent streamLogs(String buildId, Long since, Consumer<LogEvent> sink)
            throws ControlPlaneClientException;

    // ── Artifact transfer (presigned, deliberately unsigned by this client — see below) ────────
    public void putPresigned(String url, Path file) throws ControlPlaneClientException;
    public void getPresigned(String url, Path destination) throws ControlPlaneClientException;

    // ── Admin / MicroVM — typed, using AdminApi's existing records ─────────────────────────────
    public AdminApi.ImageBuildResponse triggerImageBuild(String artifactS3Uri)
            throws ControlPlaneClientException;
    public AdminApi.ImageBuildResponse getImageStatus(String imageIdentifier)
            throws ControlPlaneClientException;
}
```

### Exception type: one new type, not `IOException`

The plugin's current client throws `IOException` for everything, including HTTP-level failures
(wrong status code) — overloading a checked exception that also means "the socket actually broke"
to also mean "the server said no." The CLI's `CliException` is strictly better here (carries
`statusCode()` so callers can branch on 403 vs 500 without string-parsing a message), but it lives
in `scaleout-build-cli` today, in the wrong module to be the shared type.

**Decision:** introduce `ControlPlaneClientException` in the new module — same shape as the CLI's
existing `CliException` (`message`, `statusCode()`, `0` for non-HTTP failures) — and have
`scaleout-build-cli`'s `CliException` either become a thin alias/wrapper or be retired entirely in
favor of the new type once the CLI migrates (see Migration below). This is a Pattern that matches
`-api`'s own `ErrorResponse` design: the machine-readable `error` code and `missingItems` list are
already parsed by the plugin's `describeFailure` today and should move into the shared exception's
construction logic verbatim — that parsing is itself one more piece of duplicated logic worth
consolidating, not just the signing/transport code.

### Why `putPresigned`/`getPresigned` stay unsigned, explicitly carried forward

The plugin client's javadoc note on this is correct and must be preserved verbatim in the new
module, not just assumed to be obvious:

> Presigned S3 transfers go out on a **separate, unsigned** request path. A presigned URL
> authenticates by query string and already carries a signature; adding an `Authorization` header
> would invalidate it.

This is why the signer is attached per-call inside `send()`, not globally to the shared
`HttpClient` instance — a mistake here silently breaks every artifact upload/download with a
signature-mismatch error that has nothing to do with the control plane itself.

## Migration path

Three independent migrations, each a small, reviewable PR per `CONTRIBUTING.md`'s own guidance —
not one big-bang change:

### 1. Create the module, no consumers yet

Stand up `scaleout-build-control-plane-client` with the full target surface above, migrating the
plugin's existing `ControlPlaneClient` body wholesale (it's the superset) plus the CLI client's
admin/MicroVM methods and `listBuilds`. Port `ControlPlaneClientTest`
(the loopback-`HttpServer` pattern from `unit-test-strategy.md`) as the new module's own test,
covering the union of what `ControlPlaneHttpClientTest` and any prior plugin-side client tests
covered. Add this module to the aggregator `pom.xml`'s `<modules>` list and to
`scaleout-build-parent`'s dependency management the same way `-api` is declared.

### 2. Migrate the Maven plugin

Change `scaleout-build-maven-plugin`'s dependency from building its own `ControlPlaneClient` to
depending on `scaleout-build-control-plane-client`. Delete the plugin's package-private copy.
Update the plugin's call sites — they already use the typed-DTO shape, so this should be close to a
no-op aside from the exception type changing from `IOException` to `ControlPlaneClientException`
(a `RuntimeException` or a checked type — decide at implementation time based on how the plugin's
Mojo error handling already propagates failures; `BuildMojoLocalRemoteSplitTest` and sibling tests
should reveal the right shape without guessing).

### 3. Migrate the CLI and MCP together

These must move together because MCP's dependency is *on the CLI module specifically to reach this
class* — once the CLI stops owning `ControlPlaneHttpClient`, MCP's `pom.xml` dependency changes from
`scaleout-build-cli` to `scaleout-build-control-plane-client` directly, and
`ControlPlaneClientProducer` constructs the new shared `ControlPlaneClient` instead. The CLI's
command classes (`BuildsCommand`, `ImagesCommand`) currently parse raw JSON `String` responses
themselves (`ControlPlaneHttpClient.MAPPER.readTree(json)`) for pretty-printing — once the shared
client returns typed DTOs, these commands either serialize the DTO back to pretty-printed JSON via
the same `ObjectMapper` (trivial, same output) or print a formatted view of the typed object
directly, which is arguably the better long-term CLI UX but is a separate decision from this
consolidation and should not be bundled into the same PR.

Delete `ControlPlaneHttpClient`, `ControlPlaneHttpClientTest`, and `CliException` from
`scaleout-build-cli` once nothing in that module references them.

### Native-image verification, specifically

Because the CLI ships as a GraalVM native binary via the `remote-native` profile, step 3 is the one
migration that needs a native rebuild-and-run verification, not just `mvn test` green — the
in-module `--initialize-at-run-time` native-image config depends on package name, confirmed to
transfer automatically to the new module (same package, see above), but "should transfer
automatically" is a claim to verify once, not assume twice, per this project's own standing
instruction (`.kiro/steering/tech.md`'s "verify, don't remember" rule, which exists for exactly this
kind of claim). Rebuild both CLI architectures via the existing `remote-native` profile after step 3
and run a real `listBuilds` call against the live endpoint before merging, the same verification
already done once this session for the SigV4 fix.

## What does not change

- `SigV4Signer`, `SigV4RequestFilter`, and all DTOs stay exactly where they are, in
  `scaleout-build-control-plane-api`. This consolidation moves client *orchestration* code only.
- The "not JAX-RS" constraint is preserved for all three consumers, unchanged.
- `scaleout-build-control-plane` (the service) is untouched — it never depended on either client and
  does not start depending on the new module.
- Presigned-transfer signing behavior (unsigned, separate path) is preserved exactly.

## Open items (not yet decided)

- Whether `ControlPlaneClientException` is checked or unchecked. The plugin's Mojo layer currently
  catches `IOException` and the CLI's command layer currently catches `CliException` (checked) —
  reconciling these without changing both callers' control flow needs a look at both at
  implementation time, not a guess here.
- Final package choice for `ControlPlaneClientException` — alongside `ControlPlaneClient` in the
  new module, or in `-api` next to `ErrorResponse` since it's conceptually part of the wire
  contract's error shape. Leaning toward the new module (it is a *client-side* exception type, not
  part of the wire contract itself), but not settled.

## Related documents

- [`unit-test-strategy.md`](../testing/unit-test-strategy.md) — Pattern C (loopback `HttpServer`),
  the testing approach this consolidation's new module should reuse directly.
- [`sigv4-client-signing.md`](sigv4-client-signing.md) — why the signer is attached per-client-call
  rather than globally, directly relevant to the presigned-transfer signing behavior above.
- [`storage-layout-and-isolation.md`](storage-layout-and-isolation.md) — the presigned-URL contract
  this client's `putPresigned`/`getPresigned` methods are the client side of.

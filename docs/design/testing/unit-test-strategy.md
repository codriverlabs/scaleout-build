# Unit test strategy

## Status: Active

This document describes the testing patterns this codebase actually uses, module by module, and
the rule for choosing between them. It is a description of existing practice plus one gap being
closed (`ControlPlaneClient` / `ControlPlaneHttpClient`), not a wishlist.

---

## The two patterns in this codebase

There are exactly two unit-test patterns here. Everything else is a variation on one of them.

### Pattern A — plain JUnit 5 + Mockito + AssertJ

Used everywhere except `scaleout-build-control-plane`. The shape:

```java
@ExtendWith(MockitoExtension.class)
class EcsTaskLauncherTest {

    @Mock
    private EcsClient ecsClient;

    private EcsTaskLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher = new EcsTaskLauncher(ecsClient);
    }

    @Test
    void launchesOneTaskPerCell() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(/* ... */);
        // exercise, assert with AssertJ, verify with Mockito
    }
}
```

Why this pattern and not a hand-rolled fake: the AWS SDK clients (`EcsClient`,
`CloudWatchLogsClient`, `DynamoDbClient`, ...) are interfaces with dozens of methods. A hand-written
fake would need to reimplement AWS's request/response shapes to be worth anything, and would drift
from the real SDK silently. Mockito stubbing one or two methods per test, against the real SDK
request/response types, catches a method-signature change at compile time and costs nothing to
write per test.

**Mock the SDK client, not your own wrapper around it, wherever a direct SDK client exists on the
constructor.** `EcsTaskLauncherTest`, `CloudWatchLogTailerTest`, `TaskDefinitionRegistrarTest`,
`MicroVmImageManagerTest` all take this shape — construct the class under test with a `@Mock` SDK
client, stub the SDK call, assert on the class's own return value or on what it passed to the SDK
client via `ArgumentCaptor`.

Modules using this pattern exclusively: `scaleout-build-shared`, `scaleout-build-ecs`,
`scaleout-build-control-plane-api`, `scaleout-build-maven-plugin`, `scaleout-build-cli`,
`scaleout-build-agent`, `scaleout-build-control-plane-reaper`.

### Pattern B — `@QuarkusTest` + `@InjectMock`

Used only in `scaleout-build-control-plane`, because that module is the one Quarkus application in
the repo and the thing worth testing is CDI wiring plus config mapping, not just a method body in
isolation.

```java
@QuarkusTest
class BuildServiceTest {

    @InjectMock
    EcsClient ecs;

    @InjectMock
    BuildRepository repository;

    @InjectMock
    StagingService staging;

    @Inject
    BuildService service;   // the real bean, real CDI, real @ConfigMapping values

    @BeforeEach
    void stubDefaults() { /* ... */ }

    @Test
    void start_launches_one_ecs_task_per_cell() { /* ... */ }
}
```

`@QuarkusTest` boots the actual application (shortened startup, same CDI container) and
`@InjectMock` swaps a CDI bean for a Mockito mock inside that container. The thing under test
(`BuildService`) is injected for real — its `ControlPlaneConfig` values come from the real
`%test.*` entries in `application.properties`, not from a hand-constructed config object. This is
deliberate: `BuildService` reads config values (`maxConcurrentBuildsPerOwner`,
`overallTimeoutMinutes`, ECS cluster settings) across several methods, and a test that constructs
`BuildService` manually with fabricated config risks testing a configuration shape that can never
occur in the deployed app. Booting through Quarkus's real `@ConfigMapping` means a typo in
`application.properties` or a mismatched config interface fails tests immediately, not in
production.

**Known limitation, written down so it is not rediscovered by trial and error:** `@QuarkusTest`
boots the application through Quarkus's own augmented `QuarkusClassLoader`, not the classloader
`surefire` forks the JVM under. A plain `jacoco-maven-plugin` javaagent attached via `argLine`
instruments classes before that augmentation happens, so its coverage probes do not line up with
what `@QuarkusTest` actually executes — every class exercised only by `@QuarkusTest` reports 0%
instrumented coverage despite the tests genuinely passing and genuinely calling that code. Quarkus
has a dedicated `quarkus-jacoco` extension for this gap
([quarkus.io/guides/tests-with-coverage](https://quarkus.io/guides/tests-with-coverage)); wiring it
in during this investigation produced conflicting output (0% everywhere, aggregating unrelated
classes) rather than a clean fix, and getting that integration right is its own task, not yet done.
**Consequence: do not trust a JaCoCo percentage for `scaleout-build-control-plane` without first
confirming `quarkus-jacoco` (not `jacoco-maven-plugin` alone) produced it.** For every other module,
plain `jacoco-maven-plugin` numbers are accurate, because none of their tests go through
`@QuarkusTest`.

---

## Which pattern to use

One rule: **if the class is constructed and used inside a Quarkus-managed CDI container in
production (anything under `scaleout-build-control-plane`), test it with `@QuarkusTest`. Otherwise,
construct it directly and mock its dependencies with Mockito.** There is no third option and no
judgment call — the module a class lives in decides this.

A corollary that has come up in practice: do not reach for `@QuarkusTest` just because a class
happens to live in that module if the class itself has no CDI dependencies and can be constructed
directly (`CallerIdentityResolver`, `BuildRecord`) — those are plain Pattern A tests inside the
Quarkus module, and that is correct. The rule is about what the class *needs*, not what module it
sits in.

---

## What is not unit-tested, by design, and why that is correct

- **DTOs/records with no logic** (`CreateBuildRequest`, `BuildStatus`, `ArtifactDownload`, ...) —
  covered once, collectively, by `WireContractSerializationTest`, which round-trips every record
  through Jackson. A dedicated test per record would only reassert that a record has the fields it
  declares.
- **Enums with no behavior beyond `values()`** — not tested unless they carry logic (`BuildKind`'s
  matrix-expansion helpers do get tested; a bare `enum CellState { PENDING, RUNNING, ... }` would
  not).
- **CDI producers whose body is `new FooClient.builder().region(...).build()`**
  (`AwsClientProducer`) — there is no branching to exercise; a test would mock the builder and
  assert the mock was called, which tests Mockito, not the producer.
- **`ControlPlaneConfig`** (the `@ConfigMapping` interface itself) — validated implicitly every time
  `ApplicationBootTest` or any `@QuarkusTest` boots, because a malformed mapping fails Quarkus
  startup. A dedicated test would duplicate what already fails loudly on every other test run.

---

## Gap closed by this document: the two hand-written HTTP clients

`scaleout-build-cli`'s `ControlPlaneHttpClient` and `scaleout-build-maven-plugin`'s
`ControlPlaneClient` are two independent, hand-written clients over
`java.net.http.HttpClient`— **not** generated from the `BuildApi` JAX-RS interface. Both javadocs
say so explicitly and for the same reason: pulling a JAX-RS client runtime onto the classpath of
every Maven build or every CLI invocation buys nothing when the client only makes six calls with
hand-built JSON bodies.

Before this change, `ScaleoutCliTest` mocked `ControlPlaneHttpClient` itself, which tests the CLI's
command-parsing and delegation logic but never executes `ControlPlaneHttpClient`'s own body — the
path-string construction (`"/builds?limit=" + limit`), the SigV4 signing call, the Jackson
(de)serialization, and the HTTP status-to-exception mapping were all unexercised. Confirmed by
instrumented coverage before this change: `ControlPlaneHttpClient` at 6.1% of 231 instructions.

### Pattern C — loopback `HttpServer`, for the two hand-written HTTP clients only

Neither client has an SDK interface to mock (there is no `interface ControlPlaneSdkClient`,
by design — see above), so Pattern A's "mock the SDK client" rule has nothing to attach to. The
thing that actually varies at the boundary is an HTTP response, so the test boundary is an HTTP
server, not a mocked Java interface. JDK's built-in `com.sun.net.httpserver.HttpServer` needs no
new dependency (it ships with the JDK these clients already run on) and lets a test assert on the
literal bytes the client sent — the real request path, the real headers, the real body — rather
than on a mocked call.

```java
class ControlPlaneHttpClientTest {

    private HttpServer server;
    private ControlPlaneHttpClient client;
    private final List<HttpExchange> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            captured.add(exchange);
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.start();
        client = new ControlPlaneHttpClient(
                "http://localhost:" + server.getAddress().getPort(), "eu-west-1");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void listBuilds_sendsLimitAsQueryParameter() throws CliException {
        client.listBuilds(20);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getRequestURI().getQuery()).isEqualTo("limit=20");
    }
}
```

This is the same category of decision as `@QuarkusTest` for the control plane: exercise the real
wire path instead of mocking around it, because the wire path (query-string construction, SigV4
signing, status-code mapping) is exactly what has broken in this codebase before — the
doubled-query-string SigV4 bug (see `SigV4SignerTest`) was in this same call path and was invisible
to a test that mocked the signer.

# SigV4 request signing for control-plane clients

## Status: Reference design — implemented in `scaleout-build-control-plane-api`

This is the canonical design for signing requests to the builder control plane. It is written to be
usable outside this repository: any client that talks to a Lambda Function URL or API Gateway with
IAM auth — a Maven plugin, a CLI, an MCP server, a web backend — has the same problem and should
solve it the same way.

Everything stated here about the AWS SDK was verified against the artifacts, not recalled. See
[Verification](#verification) for exactly how.

## The one invariant

> **The bytes hashed at signing time must be byte-identical to the bytes the server receives.**

SigV4's canonical request includes a SHA-256 hash of the payload. If they differ by a single byte,
the server computes a different signature and rejects the request with `403`
`SignatureDoesNotMatch`. Every real signing bug is a violation of this invariant, and the error
message never says so — it looks like an IAM or configuration problem.

The corollary that catches people: you cannot sign a request before you know its serialized body.

## Why JAX-RS makes this easy to get wrong

A `ClientRequestFilter` runs **before** the `MessageBodyWriter` serializes the entity. At filter
time, `requestContext.getEntity()` returns the *object*, not its JSON. So the natural-looking
implementation is wrong:

```java
// WRONG -- signs zero bytes for any POJO, while JSON is actually sent.
private byte[] extractBody(ClientRequestContext ctx) {
    if (!ctx.hasEntity()) return new byte[0];
    Object entity = ctx.getEntity();
    if (entity instanceof String s) return s.getBytes(UTF_8);
    if (entity instanceof byte[] b) return b;
    return new byte[0];                 // <-- every POJO lands here
}
```

This fails for every request whose body is a record or POJO, which is most POST bodies. It passes for
clients that happen to hand the framework a pre-serialized `String`, which is why it can sit
undetected for a long time.

### The fix: serialize in the filter, then write the bytes back

Serialize the entity yourself, sign those bytes, and replace the entity with them. Then the
framework has nothing left to serialize, and divergence is structurally impossible.

```java
private byte[] materializeEntity(ClientRequestContext ctx) {
    if (!ctx.hasEntity()) {
        return new byte[0];
    }
    Object entity = ctx.getEntity();
    if (entity instanceof byte[] bytes) {
        return bytes;
    }
    if (entity instanceof String string) {
        return string.getBytes(StandardCharsets.UTF_8);
    }
    byte[] serialized = objectMapper.writeValueAsBytes(entity);
    ctx.setEntity(serialized);          // sent bytes == signed bytes, by construction
    return serialized;
}
```

**The `ObjectMapper` used here must be configured identically to the one the JAX-RS provider would
have used.** If they disagree about null inclusion, property naming, or date format, you are back to
signing different bytes than you send — the same bug, harder to see. Injecting the application's
mapper is safer than constructing a fresh one.

Alternatives, and why they were rejected:

- **`WriterInterceptor`** — sees the serialized bytes, but runs after headers are committed, so it
  requires buffering the body and rewriting headers. More machinery, same outcome.
- **`PAYLOAD_SIGNING_ENABLED=false`** — not accepted by Lambda Function URLs with
  `AuthType: AWS_IAM`.
- **Sign in the HTTP client layer instead** — valid, and what a plain `java.net.http` client does
  naturally, because the caller already holds the body as a `String`. If you are not using JAX-RS,
  prefer this: there is no ordering problem to work around.

## Credential lifecycle

**Store the provider. Resolve per request. Never cache the resolved credentials.**

```java
// Correct: the provider caches internally and refreshes ahead of expiry.
private final AwsCredentialsProvider credentialsProvider;
...
b.identity(credentialsProvider.resolveCredentials());
```

```java
// Wrong for anything long-lived: resolved once, then frozen.
private final AwsCredentials credentials = provider.resolveCredentials();
```

SSO sessions, assumed-role sessions, and IMDS credentials expire — typically within an hour. A
process that resolves once and caches works perfectly in a short-lived CLI and then fails with
`403` after an hour of uptime in a daemon or MCP server, with nothing in the error pointing at
expiry.

`resolveCredentials()` per request is cheap: `DefaultCredentialsProvider` caches the resolved
credentials and refreshes them before expiry, so the common path is a field read, not an STS call.
Build it with `reuseLastProviderEnabled(true)` so the chain is not re-probed on every call.

There is no case where caching resolved credentials is the better default. If a short-lived CLI wants
it, make it opt-in and document the expiry hazard.

## Fail fast, do not guess

Two configuration values must be present, and guessing either produces a `403` that misdirects the
reader:

- **Region.** Resolve from an explicit property, then `AWS_REGION`, then `AWS_DEFAULT_REGION`. If
  none is set, **throw** with a message naming what to set. Defaulting to `us-east-1` means a
  correctly-configured `eu-west-1` endpoint returns a signature error, and the reader has no reason
  to suspect the region.
- **Credentials.** If the chain cannot resolve, **throw**. Do not return `null` and let callers skip
  signing — an unsigned request produces an opaque `403` that reads like a permissions problem, when
  the real cause is "no credentials on this machine." `No AWS credentials found; run 'aws sso login'
  or set AWS_PROFILE` is actionable; `403 Forbidden` is not.

## What goes into the signature

| Element | Handling |
|---|---|
| Method | As-is. |
| URI path | As-is; the signer normalizes it (see defaults below). |
| Query string | Use the **raw** (already percent-encoded) form. This is what goes on the wire, so it is what must be hashed. Split on `&`, then on the first `=` only. |
| Headers | Copy the request's headers in, **excluding `Host` and `Authorization`**. |
| Payload | The exact bytes, per the invariant above. |

### Headers: exclude `Host` and `Authorization`

`Host` is set by the HTTP client at connection time; a stale or duplicated value breaks the very
signature it is part of. Copy the signed headers back to the outgoing request, again skipping `Host`.

`Authorization` must be excluded from the headers being signed, or a request that already carries one
— a retry passing through the same filter, or a caller that set one — folds the previous signature
into the new canonical request.

### Query strings matter more than they look

Our own API puts a cell identifier and a resume watermark in the query string:

```
GET /builds/01J8/logs?cell=NATIVE%2FARM64&since=1789867601234
```

The `/` in `NATIVE/ARM64` is percent-encoded. If the query string is dropped from the canonical
request, or re-encoded differently from what is sent, resuming a log stream fails while a fresh
stream succeeds — a maddening asymmetry to debug. The reference implementation has a test asserting
that two different `cell` values produce two different signatures, which fails if the query is
ignored.

## Signer properties: set two, leave the rest alone

Use `AwsV4HttpSigner` from `software.amazon.awssdk:http-auth-aws`. `Aws4Signer` from
`software.amazon.awssdk:auth` is **deprecated**.

```java
private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();

SignedRequest signed = signer.sign(b -> {
    b.identity(credentialsProvider.resolveCredentials())   // no cast: AwsCredentials IS an
                                                           // AwsCredentialsIdentity
     .request(unsignedSdkHttpRequest)
     .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
     .putProperty(AwsV4HttpSigner.REGION_NAME, region.id());
    if (payload.length > 0) {
        b.payload(() -> new ByteArrayInputStream(payload));
    }
});

signed.request().headers().forEach(/* copy back, skipping Host */);
```

Note the two required properties live on **different interfaces** — `SERVICE_SIGNING_NAME` on
`AwsV4FamilyHttpSigner`, `REGION_NAME` on `AwsV4HttpSigner`. Easy to mis-import.

Defaults, verified from the 2.54.16 sources. **All are already correct for Lambda Function URLs and
API Gateway.** Do not set them:

| Property | Default | When to change |
|---|---|---|
| `DOUBLE_URL_ENCODE` | `true` | `false` **for S3 only** |
| `NORMALIZE_PATH` | `true` | `false` **for S3 only** |
| `PAYLOAD_SIGNING_ENABLED` | `true` | Leave alone; Function URLs require signed payloads |
| `CHUNK_ENCODING_ENABLED` | `false` | Service-specific (`aws-chunked`); not applicable here |
| `AUTH_LOCATION` | `HEADER` | `QUERY` only when presigning |
| `EXPIRATION_DURATION` | `null` | Presigning only; max 7 days, requires `AUTH_LOCATION=QUERY` |

The S3 exception is the one to remember: S3 needs `DOUBLE_URL_ENCODE=false` and
`NORMALIZE_PATH=false` or certain keys produce signature mismatches. Everything else wants the
defaults.

## Signing name: `lambda`, not `execute-api`

| Exposure | `SERVICE_SIGNING_NAME` | Caller IAM |
|---|---|---|
| Lambda Function URL, `AuthType: AWS_IAM` | `lambda` | `lambda:InvokeFunctionUrl` **and** `lambda:InvokeFunction` |
| API Gateway with IAM auth | `execute-api` | `execute-api:Invoke` |

The builder control plane uses a Function URL, so `lambda`. Getting this wrong is another `403` with
no hint: the signature is well-formed, just computed for the wrong service. Make it a constructor
parameter with a named constant per option rather than a bare string literal at call sites.

## Presigned URLs are a different mechanism — do not conflate them

The control plane hands clients **presigned S3 URLs** for staging uploads and artifact downloads.
Those carry their own signature in the query string, generated server-side by the S3 presigner, and
the client must send them **unsigned and unmodified**.

Practical consequence: a client that registers a SigV4 filter globally on its HTTP client will also
sign the presigned S3 `PUT`, adding an `Authorization` header to a request that already authenticates
by query string. Register the filter **per API client instance**, not globally, and make raw S3
transfers over a separate unfiltered client.

Also: do not add headers to a presigned request beyond what was signed into it. If the server
presigned with a `ContentType`, the client must send exactly that; if it did not, adding one breaks
the signature.

## Streaming and long-lived connections

The control plane's log endpoint is SSE and can stay open for minutes. Two things follow:

- **The signature covers the request, not the connection.** Credentials expiring mid-stream does not
  terminate an established stream; the signature was already validated at request time.
- **Reconnects are new requests and need fresh signatures.** Because a build can outlive the
  streaming function's 15-minute ceiling, clients reconnect — sometimes hours into a session. Each
  reconnect resolves credentials again, which is precisely why the provider must be live rather than
  a cached snapshot. A client that caches resolved credentials works for the first stream and fails
  on a later reconnect.
- SigV4 has a clock-skew tolerance of about 5 minutes on the request timestamp. Long *connections*
  are fine; long *delays between signing and sending* are not. Sign immediately before sending.

## Testing

Signature correctness is awkward to assert directly — you cannot recompute the expected signature
without reimplementing the algorithm. Assert observable properties instead:

1. **A body and no body must produce different signatures.** This single test catches the
   empty-payload bug, which is otherwise invisible until a real service rejects the request. Under
   the broken implementation both sign zero bytes, so the signatures are equal and the test fails.
2. **The entity was replaced with exactly `objectMapper.writeValueAsBytes(entity)`.** Pins the
   sent-equals-signed invariant.
3. **`String`/`byte[]` entities pass through untouched.** Guards against double-serializing an
   already-serialized body.
4. **Two different query strings produce different signatures.** Guards the resume path.
5. **A missing region throws, with the setting named in the message.** Guards against the
   `us-east-1` default.
6. **`Authorization` is present and well-formed**, and `Host` was not copied back.
7. **A UTF-8 body is signed by byte length, not character count.** Cheap guard against a
   `String.length()` slip in any future buffering.

Use static test credentials, never the default chain, so tests do not depend on the machine.

Integration-test against the real endpoint at least once: unit tests confirm internal consistency,
not that AWS agrees with you.

## Anti-patterns observed in the wild

Found in `express-compute-control-plane` at commit `f33b1f3`, and documented here because they are
the natural mistakes rather than unusual ones. Full report:
`/tmp/ecp-control-plane/sigv4-signing-findings.md`.

| Anti-pattern | Consequence |
|---|---|
| `extractBody` returning `new byte[0]` for POJO entities | Every POST with a body fails; latent while all callers pass pre-serialized strings |
| Caching resolved `AwsCredentials` in a field | Works in a CLI, `403`s after ~1h in a long-lived process |
| `create()` returning `null` on credential failure, callers guarding `if (signer != null)` | Requests sent unsigned; opaque `403` that reads as an IAM problem |
| Defaulting region to `us-east-1` | Signature error against a correctly configured endpoint in another region |
| Deprecated `Aws4Signer` | Works today; on a removal path |
| Excluding only `Host` from signed headers | A pre-existing `Authorization` header gets folded into the canonical request |

## Reference implementation

- `scaleout-build-control-plane-api/src/main/java/ai/codriverlabs/scaleoutbuild/controlplane/api/client/SigV4RequestFilter.java`
- `scaleout-build-control-plane-api/src/test/java/ai/codriverlabs/scaleoutbuild/controlplane/api/client/SigV4RequestFilterTest.java`

Dependency scoping, so consumers are not forced into a JAX-RS or HTTP implementation:

```xml
<!-- provided: the consumer supplies the JAX-RS and Jackson implementations -->
<dependency>
  <groupId>jakarta.ws.rs</groupId>
  <artifactId>jakarta.ws.rs-api</artifactId>
  <scope>provided</scope>
</dependency>

<!-- optional: only clients sign; a server implementation should not inherit these -->
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>http-auth-aws</artifactId>
  <optional>true</optional>
</dependency>
```

## Verification

Every SDK claim above was checked against artifacts on 2026-09-20, not recalled from memory —
`.kiro/steering/tech.md` requires this, and two of these facts contradict what is commonly written
in blog posts and older docs.

| Claim | How verified |
|---|---|
| `Aws4Signer` is deprecated | `unzip -p auth-2.54.16.jar .../Aws4Signer.class \| strings \| grep Deprecated` → present |
| `http-auth-aws` is available at 2.54.16 | `mvn -U dependency:get -Dartifact=software.amazon.awssdk:http-auth-aws:2.54.16` → resolves |
| `AwsCredentials` is an `AwsCredentialsIdentity`, so no cast is needed | `javap software.amazon.awssdk.auth.credentials.AwsCredentials` → `extends ...identity.spi.AwsCredentialsIdentity` |
| `SERVICE_SIGNING_NAME` and `REGION_NAME` live on different interfaces | `javap AwsV4FamilyHttpSigner` and `javap AwsV4HttpSigner` |
| Builder methods are `request`/`payload`/`identity`/`putProperty` | `javap BaseSignRequest$Builder` |
| `DOUBLE_URL_ENCODE`, `NORMALIZE_PATH`, `PAYLOAD_SIGNING_ENABLED` default `true`; `CHUNK_ENCODING_ENABLED` defaults `false`; `AUTH_LOCATION` defaults `HEADER` | javadoc in `http-auth-aws-2.54.16-sources.jar`, `AwsV4FamilyHttpSigner.java` |
| S3 requires `DOUBLE_URL_ENCODE=false` / `NORMALIZE_PATH=false` | same javadoc, explicit note on both properties |
| `jakarta.ws.rs-api` 4.0.0 is the latest published | `dependency:get` for 4.0.0 resolves; 4.0.1 and 4.1.0 do not exist |

Pinned versions at time of writing: AWS SDK `2.54.16`, `jakarta.ws.rs-api` `4.0.0`. Re-verify before
reuse — the steering note exists because pins drift.

## Open items

- Whether the `ObjectMapper` used for signing should be injected from the application's JAX-RS
  provider configuration rather than constructed. Correct in principle; currently a default mapper is
  constructed, which is safe only while all DTOs are plain records with default naming.
- Whether to offer a non-JAX-RS variant for `java.net.http` callers. The signing logic is identical
  and the ordering problem disappears, so a small shared core with two thin adapters would remove
  the duplication an MCP server would otherwise create.

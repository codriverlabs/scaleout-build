# Tech steering: dependency versions

## Rule

Always use the **latest stable (LTS-equivalent) release** of a library, not whatever version
happens to be sitting in local cache or was pinned when a section of the design doc was written.

"Latest stable" means:

- The newest version published to Maven Central that is **not** a beta, RC, milestone, or
  otherwise pre-release build (e.g. reject `9.0.0-beta.0`, prefer the newest plain `x.y.z`).
- Verified by actually resolving it (`mvn dependency:get -Dartifact=<ga>:<version>`,
  `dependency:tree`, or an equivalent fetch), not by trusting memory, a skill's example snippet,
  or a version number quoted in an older doc/blog post/search result. Those can lag reality in
  both directions — sometimes stale, sometimes describing a version that was never actually
  published (see 8.9.0 below).

## Why this exists

Twice in this project so far, a dependency version was pinned from memory/design-doc-era research
and turned out to be wrong once actually checked against Maven Central:

1. `org.jobrunr:jobrunr` — the design doc pinned `8.8.2`. A later check found `9.0.0-beta.0`
   published, but it's beta, so out. `8.9.0` appeared in local `.m2` metadata as a directory but
   had a cached resolution failure (`.lastUpdated` marker) — turns out `8.9.0` was **never
   actually published** to Central at all. `8.8.2` was correct, but only after checking, not
   because it was the first number found.
2. `software.amazon.dsql:aurora-dsql-jdbc-connector` — pinned at `1.1.0` in the parent POM,
   carried over from earlier design-doc research. The official `aurora-dsql` skill's
   `language.md` names `1.4.0` as current. Actually resolving `1.4.0` via
   `dependency:tree` against Maven Central in this project **succeeded** — it exists and pulls
   cleanly. The stale `1.1.0` pin would have shipped without the fixes/features between 1.1.0 and
   1.4.0 for no reason other than nobody re-checked it.
3. Spot-checking two more while writing this note: AWS SDK for Java v2 is at `2.54.x` upstream
   (this repo's `awssdk.version` was `2.36.0`), and HikariCP is at `7.1.x` (this repo had `6.3.0`
   pinned; HikariCP 7.x requires JDK 17+, which this project already targets, so there's no
   reason to stay on 6.x).

The pattern: a version gets written down once during design/research, and every subsequent task
just reuses it without re-verifying, so the pin quietly drifts further from "latest stable" as
the project goes on. Re-verify at the point of use, not just at design time.

## Practical workflow before pinning or bumping a version

1. Check what's actually on Maven Central for the artifact — search, or better, attempt
   resolution directly (`mvn dependency:get`, `dependency:tree`, or the `get_pricing`-style
   MCP/skill tools if one exists for the ecosystem).
2. If a fetch fails, distinguish "genuinely not published" (real 404 even with network — pin to
   the last version that *does* resolve) from "network unavailable in this sandbox" (say so
   explicitly, use the newest previously-confirmed-resolvable version, and flag that it should be
   re-verified once network access is available). Don't silently treat one as the other.
3. Skip pre-release identifiers (`-beta`, `-rc`, `-M1`, `-alpha`, etc.) unless the user explicitly
   asks for a preview/beta release.
4. When a dedicated skill (e.g. `aurora-dsql`) names a specific version in its reference docs,
   treat that as a strong signal of current-at-authoring-time, but still verify it resolves —
   skills can also lag a release or two.
5. Prefer exact/pinned versions in POM/lockfiles (already required by this project's broader
   dependency policy) — "latest stable" is about picking the right pin, not about using open
   ranges.
6. When bumping a version already in use, briefly check the target version's changelog/release
   notes for breaking changes before bumping, especially for libraries this project wires
   directly into runtime-critical paths (JobRunr's `StorageProvider`/`BackgroundJobServer`
   constructors, the DSQL connector's JDBC URL/property contract, etc.).

## Environment note: don't assume "no network" from one failed resolution

While writing the first version of this note, a `dependency:get` for a version stayed cached as
failed (`.lastUpdated` marker) from an earlier session and was initially read as "this sandbox has
no network access." A later attempt, prompted by the user pointing out the assumption was
untested, resolved `aurora-dsql-jdbc-connector:1.4.0`, `HikariCP:7.1.0`, and
`software.amazon.awssdk:bom:2.54.10` cleanly from Maven Central. **Force a fresh resolution
(`mvn -U dependency:get ...` or delete the stale `.lastUpdated` file) before concluding an
environment lacks network access** — a cached failure from a genuinely offline moment, or from an
artifact that truly doesn't exist, looks identical to "no network" until you retry it explicitly.

## Current pins (re-verify before reusing, don't treat as permanently correct)

| Dependency | Pinned (applied in this repo) | Verified latest stable (as of this note) |
|---|---|---|
| `org.jobrunr:jobrunr` | 8.8.2 | 8.8.2 (9.0.0-beta.0 is beta; 8.8.3 and 8.9.0 do not exist on Central) |
| `software.amazon.dsql:aurora-dsql-jdbc-connector` | 1.4.0 | 1.4.0 (confirmed resolvable) |
| `software.amazon.awssdk:bom` | 2.54.10 | 2.54.10 (confirmed resolvable; AWS SDK v2 ships near-daily patch releases — re-check before reuse) |
| `com.zaxxer:HikariCP` | 7.1.0 | 7.1.0 (confirmed resolvable; requires JDK 17+, already satisfied) |
| `org.postgresql:postgresql` | 42.7.7 | 42.7.7 (pgJDBC's own site lists 42.7.x as current) |

Treat the "verified latest stable" column as stale the moment this note ages — re-check before
trusting it on a future task.


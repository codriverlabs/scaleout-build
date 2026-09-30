# Publishing to Maven Central

Status: **partially prepared.** The POM metadata and sources/javadoc jars are done. Namespace verification,
GPG signing and the publisher-tier question are not, and the last of those may be a blocker.

## Read this first: it probably needs a paid tier

Sonatype published [Clarifying Maven Central Publisher Tiers and Commercial
Use](https://central.sonatype.org/news/20260908_publisher_tiers_commercial_use/) on 8 September 2026:

> From October 1, 2026, Maven Central Publisher Pro is required if either of the following applies: […]
> **Any artifact you publish has a commercial nature, regardless of your organization's publishing volume.**
> An artifact has a commercial nature if it supports the adoption, integration, or operation of your
> organization's commercial product or service. This can include […] **build tooling, developer tools**, and
> other software distributed as part of a commercial offering.

"Build tooling, developer tools" describes this plugin exactly. It is licensed ELv2, which reserves
hosted-service rights, and it sits beside a `-pro` tier in the same portfolio. They also close the obvious
escape: *"Remaining within the free volume thresholds does not create a volume-based exception for
commercial-nature artifacts."*

There is room to argue, and they say so:

> Being maintained by a company does not automatically make an open-source project commercial in nature. The
> relevant question is whether the artifact is part of a commercial product, service, or go-to-market motion.

And publishers *"may request review of community artifacts that are not part of any commercial go-to-market
activity on a per-artifact or per-group-ID basis."* That request is the route to free publishing, and it is a
conversation with Sonatype rather than a configuration change.

**Rate limiting began 1 October 2026.** Resolve the tier question before doing the remaining work, not after.

## Why bother at all

GitHub Packages **requires a token even for public packages** — measured, not assumed: an anonymous `GET` of
our own published `.pom` returns 401, the same URL with a token returns 302. Central needs no token. That is
the entire benefit, and it is a real adoption barrier for a plugin: every consumer must configure
`settings.xml` before their build resolves.

## Done, on this branch

Both items are worth having regardless of Central, which is why they landed first.

**Required POM metadata.** Central rejects a deployment missing `url`, `licenses`, `developers` or `scm`;
`name` and `description` were already present on every module. Declared once on the parent so all four
published modules inherit them. Verified in the effective POM, which is what validation reads — the installed
child POMs do not repeat them, and do not need to.

Also added `organization` and `issueManagement`, which are not required but are free and show up in the
metadata a consumer's IDE already downloaded.

**Sources and javadoc jars.** Required for any packaging other than `pom`. Bound in the parent's `build` so
every module gets them without opting in; the deployables `publish.yml` excludes build a few unused jars,
which is cheaper than four copies of the configuration drifting apart. Verified non-empty: 22 and 73 entries
for `shared`, 17 and 63 for the plugin. Build time went to ~29 s.

`doclint` is off and `failOnError` is false. This codebase documents reasoning at length, and failing a
release on a malformed `@link` inside a comment explaining why a timeout is 30 minutes is the wrong trade.

## Not done, and what each needs

**Namespace verification.** The cheap part, because the coordinates already fit: `groupId` is
`ai.codriverlabs`, which reverse-maps to `codriverlabs.ai`. Claim the namespace in the Central Portal and
prove ownership with a DNS `TXT` record. No coordinate change, no consumer-visible rename — normally the
expensive part of a Central migration and here it is free.

**GPG signing.** Every deployed file needs a `.asc`. Needs a key generated, its public half pushed to a
keyserver, and the private key plus passphrase as repository secrets. `maven-gpg-plugin` 3.2.8 is the current
release (verified resolvable).

Deliberately **not** added yet. Unexercised release configuration that looks ready is worse than none: it
invites someone to assume signing works. Add it in the same change that first signs something.

**Checksums** need nothing — Maven generates `.md5` and `.sha1` on deploy.

**A `central-publishing-maven-plugin` deployment**, replacing or sitting beside the GitHub Packages deploy in
`publish.yml`. Note Central is **immutable**: a published version cannot be deleted or replaced, so the first
real deployment wants a throwaway patch version, the same way `v0.0.1-rc1` exercised `publish.yml`.

## One thing worth checking if this proceeds

Central's requirements page says: *"We discourage the usage of `<repositories>` and `<pluginRepositories>`."*
Our parent declares neither today, but the agent module and the example app reference repositories in places —
worth re-checking against the effective POM of each published module before a first deployment, since a
consumer resolving from Central should not be redirected to GitHub Packages for a transitive dependency.

## Version pinning note

`maven-source-plugin`'s newest published version is `4.0.0-beta-1`. This pins **3.4.0**, the newest plain
`x.y.z`, because [`.kiro/steering/tech.md`](../../.kiro/steering/tech.md) rejects pre-release identifiers.
All three plugin versions were resolved against Central rather than taken from memory, per the same steering.

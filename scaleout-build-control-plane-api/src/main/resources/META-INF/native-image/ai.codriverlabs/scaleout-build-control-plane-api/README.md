# GraalVM native-image reflection configuration for scaleout-build-control-plane-api.

Placed in META-INF/native-image/ so it is discovered automatically from the classpath by
native-image, the same way scaleout-build-cli's own native-image.properties is.

## Why this file exists

Jackson deserializes every DTO in this module via its canonical constructor and accessor
methods, which native-image's static analysis cannot discover on its own -- it has no way to
know at build time that `mapper.readValue(json, BuildStatus.class)` will reflectively invoke
`BuildStatus`'s constructor and every accessor. Without this file, any native binary that
deserializes one of these records (scaleout-build-cli, the MCP server) fails at run time with
"Cannot construct instance ... this appears to be a native image, in which case you may need to
configure reflection for the class that is to be deserialized" -- the exact failure this file
fixes, found by actually running the compiled scaleout-build-cli native binary against a
loopback server, not assumed.

## How this was generated

Via GraalVM's `-agentlib:native-image-agent`, attached to a real JVM run of
`WireContractSerializationTest` (which round-trips every DTO in this module through Jackson) and
merged with a run of the CLI's real commands against a loopback server. Hand-writing per-field
reflect-config is error-prone and silently drifts the moment a DTO gains a field; the agent
captures exactly what Jackson's record deserialization actually touches. See
`docs/design/control-plane/control-plane-client-consolidation.md` for the full story of how this
gap was found.

Two real pre-existing gaps in `WireContractSerializationTest` were found and fixed as part of
generating this file, not papered over: `AdminApi`'s two records and `ArtifactListResponse` (with
its nested `CellArtifacts` and `ArtifactDownload`) had never been round-tripped by that test, so
neither the test nor this generated config had ever covered them until now.

## Regenerating this file

If a new DTO is added to this module, or an existing one's fields change, regenerate rather than
hand-edit:

```bash
mvn -B test -pl scaleout-build-control-plane-api -am -Dtest=WireContractSerializationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine="-agentlib:native-image-agent=config-output-dir=/tmp/agent-output"
```

Then extract the entries whose `type` starts with `ai.codriverlabs.scaleoutbuild.controlplane.api`
(excluding the test class itself) from `/tmp/agent-output/reachability-metadata.json`, and replace
this file's contents with them. If the new/changed DTO is not exercised by
`WireContractSerializationTest`, add a round-trip test for it there first -- that test existing
and passing is what guarantees this file stays correct, not the other way around.

# User Guide: Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `scaleout-build.endpoint is required` | Not set, or the properties file was not read | A shared POM needs `properties-maven-plugin` bound to `validate` |
| `403` on the first call | Credentials lack `lambda:InvokeFunctionUrl` **and** `lambda:InvokeFunction` | Both are needed; the second is easy to miss |
| `contains no *.args file` | `write-args-file` was not run, or ran elsewhere | Run it, and point `argsFileDirectory` at its output directory |
| `contains N *.args files` | Stale argfiles from earlier builds | Delete the old ones; the name is randomized so they accumulate |
| `Found native-sources but no native-image.args` | An ordinary Quarkus native build left the directory behind | Re-run with the `sources-only` properties |
| `Classpath entry … does not exist` | The argfile outlived a `mvn clean` | Re-run `native:write-args-file` after a full build |
| `Cannot determine the main class` | Plain GraalVM project with no `Main-Class` manifest entry | Set `mainClass` |
| `Runtime classpath entry … is a directory` | A reactor dependency is not packaged | `mvn package` or `mvn install` it first |
| Binary builds, then fails at run time | Framework step skipped, so arguments were derived from the classpath | Follow the framework step above |
| Build slower than local | A host-matching cell was offloaded | Remove `forceRemote` |
| Cell fails with no obvious reason | The remote log is the record | Read the streamed output; the agent logs the full `native-image` invocation |

## Limits worth knowing

- **An architecture matching your host is better built locally.** The plugin does this by default; only
  `forceRemote` overrides it.
- **`jvm` build kind does not need remote workers** — it produces an architecture-neutral jar. It is in the
  matrix for completeness.
- **PGO does not parallelise within an architecture.** Instrument → run workload → optimize is inherently
  ordered, so those cells are sequential per architecture and only concurrent across them.
- **Do not compare binaries by digest.** `native-image` output is not bit-reproducible: two builds of
  identical inputs with the same toolchain produced binaries of *exactly* the same length differing in
  135,362,134 bytes, starting at the GNU build-id. Compare architecture, length, and behaviour instead.
- **8 GiB of worker memory is a floor, not a preference.** Peak RSS is dominated by native memory and the
  image heap rather than the Java heap, so capping the builder's heap does not reduce it; 4 GiB OOMs on a
  233-dependency project.
- **A Spot interruption fails the build.** There is no server-side retry — see
  [`design/control-plane/HANDOVER.md`](../design/control-plane/HANDOVER.md). Set
  `-c fargateCapacityStrategy=on-demand-preferred` at deploy time where a lost build is expensive.
- **Helidon is untested.** It shares the Spring Boot code path, which is verified, but no Helidon project has
  been run through it.

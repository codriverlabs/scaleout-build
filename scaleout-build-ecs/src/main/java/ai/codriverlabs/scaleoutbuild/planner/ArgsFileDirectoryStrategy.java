/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Spring Boot AOT, Helidon, and plain GraalVM builds driven by {@code native-maven-plugin}'s
 * {@code write-args-file} goal.
 *
 * <p><b>The directory must be configured explicitly; there is no default.</b> That is a deliberate refusal to
 * guess. {@code write-args-file} takes its location from the {@code graalvm.native-image.args-file} property,
 * and a hardcoded wrong path would not fail loudly — it would fail {@link #appliesTo}, fall through to
 * {@link DerivedClasspathStrategy}, and produce a binary that builds successfully and then misbehaves at run
 * time, because an AOT-processed application needs generated configuration a derived argfile omits.
 *
 * <h2>Why the argfile is rewritten rather than relayed</h2>
 *
 * <p>An earlier version of this strategy staged the directory and reused the argfile verbatim, on the
 * assumption that its arguments were relative — which is how Quarkus's {@code native-sources} directory
 * works, because {@code -Dquarkus.native.sources-only=true} exists precisely to make the compile relocatable.
 *
 * <p>{@code write-args-file} is different, and correctly so: it records the command for the machine that
 * produced it, where absolute paths are right. Measured against Spring Boot 4.1.0 with
 * {@code native-maven-plugin} 1.1.1, one small application emitted 107 absolute path references, a
 * {@code -cp} of 106 entries pointing into {@code ~/.m2/repository}, and an absolute {@code -o}. Relaying
 * that to a container cannot work. Nothing about it is a defect in Spring or GraalVM — relocating it is this
 * plugin's job, and it is mechanical.
 *
 * <h2>What GraalVM already does for us</h2>
 *
 * <p>The builder auto-discovers configuration from {@code META-INF/native-image/} and its subdirectories
 * anywhere on the classpath. Spring's AOT step populates {@code target/classes/META-INF/native-image/} —
 * measured at 58 {@code reachability-metadata.json} files plus a {@code native-image.properties} that
 * supplies the main class, which is why the argfile carries no main-class argument at all.
 *
 * <p>Two consequences, and both matter:
 *
 * <ul>
 *   <li>Directory entries on the classpath are staged as <b>trees</b>, not skipped and not repackaged, so
 *       that {@code META-INF/native-image/} travels intact. Dropping the classes directory would lose the
 *       main class and every reachability entry.</li>
 *   <li>{@code -H:ConfigurationFileDirectories} is dropped. Its 57 entries addressed the downloaded
 *       repository cache from which those 58 files were already selected, so the configuration arrives by
 *       classpath discovery instead. Keeping absolute paths that do not exist in the container would fail;
 *       rewriting them would stage 37 MB to duplicate 1.9 MB already present.</li>
 * </ul>
 */
public final class ArgsFileDirectoryStrategy implements InputPlanStrategy {

    /** {@code write-args-file} emits a randomized name; this matches it and the historical fixed name. */
    private static final String ARGS_GLOB = "*.args";

    private static final String CLASSES_DIR_PREFIX = "classes";

    @Override
    public String name() {
        return "native-maven-plugin argfile (Spring Boot AOT, Helidon, plain GraalVM)";
    }

    @Override
    public boolean appliesTo(ProjectInputs inputs) {
        Path directory = inputs.argsFileDirectory();
        return directory != null && Files.isDirectory(directory);
    }

    @Override
    public NativeImageInputPlan plan(ProjectInputs inputs) throws InputPlanningException {
        Path directory = inputs.argsFileDirectory();
        Path argsFile = locateArgsFile(directory);

        List<String> original;
        try {
            original = tokenize(Files.readString(argsFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new InputPlanningException("Failed to read " + argsFile, e);
        }

        List<StagedFile> files = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        List<String> rewritten = new ArrayList<>();
        List<String> expectedArtifacts = new ArrayList<>();
        int classesDirCount = 0;

        for (int i = 0; i < original.size(); i++) {
            String line = original.get(i);
            if (isClasspathFlag(line)) {
                String value = requireValue(original, i, line, argsFile);
                i++;
                List<String> entries = new ArrayList<>();
                for (String raw : value.split(java.io.File.pathSeparator, -1)) {
                    if (raw.isBlank()) {
                        continue;
                    }
                    Path entry = Path.of(raw.trim());
                    if (Files.isDirectory(entry)) {
                        String name = uniqueName(CLASSES_DIR_PREFIX + (++classesDirCount == 1
                                ? "" : "-" + classesDirCount), usedNames);
                        stageTree(entry, name, files);
                        entries.add(name);
                    } else if (Files.isRegularFile(entry)) {
                        String libName = uniqueName(entry.getFileName().toString(), usedNames);
                        String relativePath = StagingLayout.LIB_DIR_NAME + "/" + libName;
                        files.add(new StagedFile(relativePath, entry));
                        entries.add(relativePath);
                    } else {
                        throw new InputPlanningException(
                                "Classpath entry " + entry + " from " + argsFile.getFileName()
                                        + " does not exist. Re-run 'mvn native:write-args-file' after a "
                                        + "full build so the argfile matches what is on disk.");
                    }
                }
                rewritten.add(line);
                rewritten.add(String.join(":", entries));
                continue;
            }

            if (line.equals("-o")) {
                String value = requireValue(original, i, line, argsFile);
                i++;
                String imageName = Path.of(value.trim()).getFileName().toString();
                expectedArtifacts.add(imageName);
                rewritten.add("-o");
                rewritten.add(StagingLayout.OUTPUT_DIR_NAME + "/" + imageName);
                continue;
            }

            if (line.startsWith("-H:Name=")) {
                String imageName = line.substring("-H:Name=".length()).trim();
                if (!imageName.isEmpty()) {
                    expectedArtifacts.add(imageName);
                }
                rewritten.add(line);
                continue;
            }

            // Output directory: the staging root's output/ is the only valid destination.
            if (line.startsWith("-H:Path=")) {
                rewritten.add("-H:Path=" + StagingLayout.OUTPUT_DIR_NAME);
                continue;
            }

            // See the class javadoc: redundant with META-INF/native-image/ on the staged classpath,
            // and its absolute paths do not exist in the container.
            if (line.startsWith("-H:ConfigurationFileDirectories=")) {
                continue;
            }

            rewritten.add(line);
        }

        if (expectedArtifacts.isEmpty()) {
            throw new InputPlanningException(
                    argsFile.getFileName() + " names no output image: it has neither '-o' nor '-H:Name='. "
                            + "Without one the remote build cannot know which file to return.");
        }

        String content = rewritten.stream().map(ArgsFileDirectoryStrategy::quoteIfNeeded)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()))
                + System.lineSeparator();
        return NativeImageInputPlan.generated(files, content, List.copyOf(expectedArtifacts));
    }

    /**
     * Finds the single argfile in the directory.
     *
     * <p>{@code write-args-file} names its output {@code native-image-<random long>.args}, so the name cannot
     * be predicted and a stale one from a previous build would sit alongside the current one. Refusing to
     * choose is better than picking the wrong one silently.
     */
    private Path locateArgsFile(Path directory) throws InputPlanningException {
        List<Path> candidates;
        try (Stream<Path> entries = Files.list(directory)) {
            candidates = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileSystem().getPathMatcher("glob:" + ARGS_GLOB)
                            .matches(path.getFileName()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new InputPlanningException("Failed to list " + directory, e);
        }
        if (candidates.isEmpty()) {
            throw new InputPlanningException(
                    "Configured argsFileDirectory " + directory + " contains no *.args file. Generate one "
                            + "with 'mvn native:write-args-file', or leave argsFileDirectory unset to derive "
                            + "the arguments from the runtime classpath instead.");
        }
        if (candidates.size() > 1) {
            throw new InputPlanningException(
                    "Configured argsFileDirectory " + directory + " contains " + candidates.size()
                            + " *.args files " + candidates.stream().map(p -> p.getFileName().toString())
                            .sorted().toList() + ". 'write-args-file' uses a randomized name, so stale files "
                            + "accumulate; delete the old ones or point argsFileDirectory at a directory "
                            + "holding only the current argfile.");
        }
        return candidates.get(0);
    }

    /**
     * Splits an argument file into arguments.
     *
     * <p>GraalVM follows Java's {@code @argfile} convention: arguments are separated by <em>whitespace</em>,
     * not merely by newlines, and may be single- or double-quoted to contain spaces. Parsing line by line
     * happens to work for {@code write-args-file} and for Quarkus, both of which emit one argument per line,
     * but it silently mis-reads a hand-written {@code -o app -jar app.jar}. Tokenizing removes that class of
     * bug rather than documenting around it.
     */
    static List<String> tokenize(String content) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }
            current.append(c);
            inToken = true;
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** Re-quotes on output, since a staged jar name may legitimately contain a space. */
    private static String quoteIfNeeded(String token) {
        boolean needsQuoting = token.isEmpty()
                || token.chars().anyMatch(Character::isWhitespace)
                || token.indexOf('"') >= 0;
        if (!needsQuoting) {
            return token;
        }
        return "\"" + token.replace("\"", "\\\"") + "\"";
    }

    /** Stages a classpath directory as a tree, so {@code META-INF/native-image/} survives the move. */
    private void stageTree(Path root, String destinationName, List<StagedFile> files)
            throws InputPlanningException {
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.filter(Files::isRegularFile).toList()) {
                files.add(new StagedFile(destinationName + "/" + toPosix(root.relativize(path)), path));
            }
        } catch (IOException e) {
            throw new InputPlanningException("Failed to scan classpath directory " + root, e);
        }
    }

    private boolean isClasspathFlag(String line) {
        return line.equals("-cp") || line.equals("-classpath") || line.equals("--class-path");
    }

    private String requireValue(List<String> lines, int index, String flag, Path argsFile)
            throws InputPlanningException {
        if (index + 1 >= lines.size()) {
            throw new InputPlanningException(
                    argsFile.getFileName() + " ends with '" + flag + "' and no value. The argfile is "
                            + "malformed; regenerate it with 'mvn native:write-args-file'.");
        }
        return lines.get(index + 1);
    }

    /** Keeps staged names unique when two classpath entries share a file name. */
    private String uniqueName(String preferredName, Set<String> usedNames) {
        if (usedNames.add(preferredName)) {
            return preferredName;
        }
        int suffix = 2;
        while (true) {
            int dot = preferredName.lastIndexOf('.');
            String candidate = dot < 0
                    ? preferredName + "-" + suffix
                    : preferredName.substring(0, dot) + "-" + suffix + preferredName.substring(dot);
            if (usedNames.add(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    private static String toPosix(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }
}

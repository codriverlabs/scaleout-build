package ai.codriverlabs.example;

import java.time.Instant;
import java.util.Locale;

/**
 * Deliberately minimal: no reflection, no JNI, no dynamic class loading. The point of this example
 * project is to exercise scaleout-build-maven-plugin's aws-ecs:build goal end to end against real AWS
 * (native-image compilation offloaded to Fargate for both x86_64 and arm64) — not to exercise
 * GraalVM reachability-metadata tuning, which is a separate, unrelated concern.
 */
public final class HelloNative {

    public static void main(String[] args) {
        String arch = System.getProperty("os.arch", "unknown");
        String greeting = args.length > 0 ? args[0] : "world";
        System.out.printf(Locale.ROOT, "Hello, %s! Compiled natively for %s at %s.%n",
                greeting, arch, Instant.now());
    }
}

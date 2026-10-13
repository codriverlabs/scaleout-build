/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.mcp.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import software.amazon.awssdk.checksums.SdkChecksum;
import software.amazon.awssdk.checksums.spi.ChecksumAlgorithm;

/**
 * Native-image substitutions for {@code software.amazon.awssdk.checksums.internal.ChecksumProvider}.
 *
 * <p>Three of {@code ChecksumProvider}'s methods reference classes from the optional
 * {@code software.amazon.awssdk.crt:aws-crt} module, which this project does not depend on:
 * SigV4 signing here only ever uses CRC32 or SHA-256, never CRT's CRC32C/CRC64NVME/xxHash
 * variants, and {@code aws-crt} is a ~20 MB uber-jar carrying native binaries for every
 * platform — not something to pull in just to satisfy a linker.
 *
 * <ul>
 *   <li>{@code createCrtCrc32C()} does {@code new CRC32C()}
 *       ({@code software.amazon.awssdk.crt.checksums.CRC32C}).
 *   <li>{@code crc64NvmeCrtImplementation()} does {@code new CRC64NVME()}
 *       ({@code software.amazon.awssdk.crt.checksums.CRC64NVME}).
 *   <li>{@code crtXxHash(ChecksumAlgorithm)} does {@code new XxHashChecksum(algorithm)} —
 *       {@code internal.XxHashChecksum} itself holds a
 *       {@code software.amazon.awssdk.crt.checksums.XXHash} field and calls its
 *       {@code newXXHash64()}/{@code newXXHash3_64()}/{@code newXXHash3_128()} factories from
 *       its own constructor. That field makes {@code XxHashChecksum} itself fail to
 *       <em>load</em> under GraalVM once {@code aws-crt} is absent — substituting its own
 *       methods one-by-one does not work, because the substitution matcher needs the original
 *       class to load first in order to find the methods it is substituting (circular). The fix
 *       is upstream of that: substitute {@code crtXxHash} itself so it never constructs
 *       {@code XxHashChecksum} at all, and the type is then never referenced from any reachable
 *       bytecode, so GraalVM never needs to load it.
 * </ul>
 *
 * <p>The SDK's own design intends all three to be safe on the JVM: each is reached only through
 * a {@code Lazy<Boolean>} availability check that catches {@link ClassNotFoundException} and
 * falls back cleanly — see {@code ChecksumProvider.checkCrtAvailability}. That graceful fallback
 * works because a missing class is only resolved when the method actually runs. GraalVM's
 * native-image builder is less forgiving: with Quarkus's {@code --link-at-build-time} default,
 * every method of a build-time-linked class is bytecode-parsed up front, which requires the CRT
 * types to exist even though none of these methods is ever actually called by this project.
 * {@code --initialize-at-run-time} and {@code --allow-incomplete-classpath} do not help — this
 * is a constant-pool resolution problem at parse time, not a class-initialization-order problem
 * (confirmed by testing both directly before writing this substitution).
 *
 * <p>This mirrors a known, still-open issue in the SDK itself —
 * <a href="https://github.com/aws/aws-sdk-java-v2/issues/5682">aws-sdk-java-v2#5682</a>,
 * "Optional CRC64NVME should be properly hidden in Crc64NvmeChecksum" — and the documented
 * workaround used by Quarkus's own AWS extension community for the equivalent CRC32C case
 * (<a href="https://github.com/quarkiverse/quarkus-amazon-services/issues/1247">quarkus-amazon-services#1247</a>).
 * Substituting here, in this project's own source, avoids pulling in an entire Quarkus AWS
 * extension family (this project talks to the AWS SDK directly, not through
 * {@code quarkiverse.amazonservices}) just to work around a linker-time quirk in methods this
 * project never calls.
 *
 * <p>If any substituted method is ever actually invoked at runtime — meaning a code path
 * started requesting CRT CRC32C, CRC64NVME, or xxHash — it throws
 * {@link UnsupportedOperationException} with a message pointing at this class, rather than
 * silently returning something wrong.
 */
final class AwsCrtChecksumSubstitutions {

    private AwsCrtChecksumSubstitutions() {
    }

    @TargetClass(className = "software.amazon.awssdk.checksums.internal.ChecksumProvider")
    static final class Target_ChecksumProvider {

        @Substitute
        static SdkChecksum createCrtCrc32C() {
            throw crtUnsupported("CRC32C");
        }

        @Substitute
        static SdkChecksum crc64NvmeCrtImplementation() {
            throw crtUnsupported("CRC64NVME");
        }

        @Substitute
        static SdkChecksum crtXxHash(ChecksumAlgorithm algorithm) {
            throw crtUnsupported("xxHash (" + algorithm.algorithmId() + ")");
        }
    }

    // Deliberately not a member of Target_ChecksumProvider: every method of an @TargetClass
    // inner class must itself carry @Delete, @Substitute, @AnnotateOriginal, or @Alias, since
    // GraalVM tries to match it against a same-named method on the real class. A plain shared
    // helper has no such counterpart, so it lives here on the outer, non-substitution class
    // instead.
    private static UnsupportedOperationException crtUnsupported(String algorithm) {
        return new UnsupportedOperationException(
                "CRT-based " + algorithm + " is substituted out of this native image (see "
                        + AwsCrtChecksumSubstitutions.class.getName()
                        + "); this project never requests it, and the real "
                        + "software.amazon.awssdk.crt:aws-crt dependency is deliberately not "
                        + "on the classpath. If this is reached, something started requesting "
                        + "CRT " + algorithm + " and the substitution needs to be removed in "
                        + "favor of the real aws-crt dependency.");
    }
}

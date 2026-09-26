/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.planner;

/**
 * How one framework's build output is turned into a remote {@link NativeImageInputPlan}.
 *
 * <p>Exists because "run {@code native-image} remotely" means something different per framework, and the
 * differences are not cosmetic. Two shapes cover the ecosystem:
 *
 * <ol>
 *   <li><b>The framework emits its own argument file.</b> Quarkus augmentation, and
 *       {@code native-maven-plugin}'s {@code write-args-file} goal (which Spring Boot AOT and Helidon
 *       build through), both produce a complete {@code native-image} invocation. Those arguments cannot be
 *       reconstructed from a classpath — they carry generated feature registrations, reflection and
 *       resource configuration, {@code --exclude-config} regexes and dozens of {@code -J-D} properties.
 *       Reusing the file is the only correct option.</li>
 *   <li><b>Nothing emits one.</b> A plain GraalVM project. The argument file is derived from the packaged
 *       artifact and the resolved runtime classpath.</li>
 * </ol>
 *
 * <p>Implementations are consulted in order by {@link NativeImageInputPlanner}, most specific first, so a
 * new framework is added by contributing a strategy rather than by editing a chain of conditionals.
 *
 * <p><b>On claiming support.</b> Only the Quarkus and derived strategies are verified end to end against
 * real projects. The generic argfile strategy is written against {@code native-maven-plugin}'s documented
 * goal but has not been run through a Spring Boot or Helidon build, and it therefore requires the argfile
 * path to be given explicitly rather than guessing at a default. That is deliberate: a wrong hardcoded
 * path would fail by silently falling through to the derived strategy, which for an AOT-processed
 * application produces a binary that builds and then misbehaves at run time.
 */
public interface InputPlanStrategy {

    /** Framework or mechanism this strategy handles, for logging and error messages. */
    String name();

    /**
     * Whether this strategy recognises the project's build output.
     *
     * <p>Must not throw for an unrecognised project; return {@code false} and let the next strategy try.
     * Throwing is reserved for "recognised this framework but its output is unusable", which
     * {@link #plan} reports.
     */
    boolean appliesTo(ProjectInputs inputs);

    /** Builds the plan. Only called when {@link #appliesTo} returned {@code true}. */
    NativeImageInputPlan plan(ProjectInputs inputs) throws InputPlanningException;
}

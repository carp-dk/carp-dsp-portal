rootProject.name = "carp-dsp-portal"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Lets Gradle fetch the JDK 21 toolchain itself, so the build works on a
// machine whose default JDK is something else.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

// ---- Composite builds ------------------------------------------------------
// The portal runs workflows for real, so it depends on carp-dsp and, through it,
// on carp.core-kotlin. Both are sibling checkouts; override either with
// -PdspRepo / -PcarpCoreRepo if they live elsewhere.
//
// carp.dsp.core declares its carp-core dependencies as `implementation`, so they
// do not reach this build transitively - the substitutions below are what put
// ExecutionReport and friends on the compile classpath.

val carpCorePath = file(providers.gradleProperty("carpCoreRepo").orNull ?: "../carp.core-kotlin")
val dspPath = file(providers.gradleProperty("dspRepo").orNull ?: "../carp-dsp")

listOf("carp.core-kotlin" to carpCorePath, "carp-dsp" to dspPath).forEach { (name, path) ->
    if (!path.isDirectory) error(
        """
        $name not found at ${path.absolutePath}

        The portal runs workflows for real, so it builds against sibling checkouts
        of carp-dsp and carp.core-kotlin. Clone them beside this repo, or point at
        them with -PdspRepo=<path> / -PcarpCoreRepo=<path>.

        carp-dsp needs a sibling health-workflow-interfaces of its own.

        The Docker build takes its context from the directory holding all of them,
        which is why `docker compose build` has to be run from this repo rather
        than with a hand-written `docker build .`.
        """.trimIndent()
    )
}

// Every carp-core module carp-dsp uses has to be substituted here, or its
// versionless dependency cannot resolve. Keep this list in step with the one in
// carp-dsp/settings.gradle.kts.
includeBuild(carpCorePath) {
    dependencySubstitution {
        substitute(module("dk.cachet.carp:carp-core-common")).using(project(":carp.common"))
        substitute(module("dk.cachet.carp:carp-core-data")).using(project(":carp.data.core"))
        substitute(module("dk.cachet.carp:carp-core-analytics")).using(project(":carp.analytics.core"))
        substitute(module("dk.cachet.carp:carp-core-protocols")).using(project(":carp.protocols.core"))
        substitute(module("dk.cachet.carp:carp-core-studies")).using(project(":carp.studies.core"))
        substitute(module("dk.cachet.carp:carp-core-deployments")).using(project(":carp.deployments.core"))
    }
}

includeBuild(dspPath) {
    dependencySubstitution {
        substitute(module("carp.dsp.core:carp.dsp.core")).using(project(":carp.dsp.core"))
        substitute(module("carp.dsp.steps:carp.dsp.steps")).using(project(":carp.dsp.steps"))
    }
}

include(":server")

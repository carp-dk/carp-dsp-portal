package dk.cachet.carp.dsp.portal.tools

import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowPackage
import carp.dsp.core.infrastructure.execution.DefaultEnvironmentOrchestrator
import carp.dsp.core.infrastructure.execution.DefaultEnvironmentRegistry
import carp.dsp.core.infrastructure.execution.handlers.EnvironmentStore
import dk.cachet.carp.dsp.portal.run.PortalStepLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * Builds the environments the given workflow bundles need, where runs will
 * look for them, so a run on a machine without internet starts without
 * solving anything.
 *
 * Usage: `PrepareEnvironments <bundle directory>...`. Each directory holds a
 * `workflow.yaml`. Exits non-zero if any bundle fails to plan or any
 * environment fails to build.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: PrepareEnvironments <bundle directory>..." }

    val scratch = Files.createTempDirectory("prepare-environments")
    // The same planning a run does, so the environments are the ones a run asks
    // for: the workflow's own and those of the library steps it uses.
    val executor = WorkflowExecutor.filesystem(stepLibrary = PortalStepLibrary, workspaceRoot = scratch)
    val orchestrator = DefaultEnvironmentOrchestrator(DefaultEnvironmentRegistry(scratch.resolve("registry.json")))

    val refs = args.map(Path::of).flatMap { bundle ->
        require(bundle.resolve("workflow.yaml").exists()) { "No workflow.yaml in $bundle" }
        val plan = executor.prepare(WorkflowPackage.of(bundle, "workflow.yaml")).plan
        check(plan.isRunnable()) { "$bundle does not plan: " + plan.issues.joinToString("; ") { it.message } }
        plan.requiredEnvironmentRefs.values
    }.distinct()

    val failed = refs.filterNot { ref ->
        // EXACT means it is already there; BUILT, that it is built now.
        val match = EnvironmentStore.resolve(ref)?.match
        println("${ref.id}: $match -> ${EnvironmentStore.resolve(ref)?.directory}")
        runCatching { orchestrator.setup(ref) }
            .onFailure { println("  failed: ${it.message}") }
            .getOrDefault(false)
    }

    println("Built ${refs.size - failed.size} of ${refs.size} environments.")
    if (failed.isNotEmpty()) exitProcess(1)
}

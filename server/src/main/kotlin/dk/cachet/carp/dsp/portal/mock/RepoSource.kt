package dk.cachet.carp.dsp.portal.mock

import java.io.File

/**
 * Where step and workflow definitions are read from.
 *
 * Two modes:
 *
 * - **repo** - `DSP_REPO` points at a carp-dsp checkout, read live from disk.
 *   Anything added there shows up here on restart, with no rebuild.
 * - **bundled** - the copies packaged into the jar. Keeps the image working on
 *   its own, so a demo does not depend on a checkout being present.
 *
 * The bundled copies are indexed by `index.txt`, because a jar cannot list a
 * directory. The repo mode walks the filesystem instead, which is what lets it
 * pick up files nobody has told it about.
 */
object RepoSource {

    private const val STEPS_PATH = "carp.dsp.steps/src/jvmMain/resources/steps"
    private const val WORKFLOWS_PATH = "carp.dsp.demo/src/jvmMain/resources/workflows"

    private val loader = RepoSource::class.java.classLoader

    /** The checkout, when it is set and actually looks like carp-dsp. */
    private val repoRoot: File? = System.getenv("DSP_REPO")
        ?.let(::File)
        ?.takeIf { it.isDirectory && File(it, STEPS_PATH).isDirectory }

    /** Shown in /health so it is obvious which mode a demo is running in. */
    val mode: String = repoRoot?.let { "repo:${it.absolutePath}" } ?: "bundled"

    private const val SCRIPTS_PATH = "carp.dsp.demo/src/jvmMain/resources/scripts"
    private const val PROTOCOLS_PATH = "carp.dsp.demo/src/jvmMain/resources/protocols"
    private const val DATA_PATH = "carp.dsp.demo/src/jvmMain/resources/data"

    /**
     * Build artefacts and cached environments. `.pixi` in particular holds a
     * whole interpreter, DLLs included.
     */
    private val NOISE = listOf("__pycache__", ".pytest_cache", ".pixi", ".git")

    private fun File.isNoise(): Boolean =
        invariantSeparatorsPath.split('/').any { it in NOISE } || extension == "pyc"

    data class StepSource(
        /** Directory relative to the steps root, e.g. `core/stats/summarise`. */
        val dir: String,
        val stepYaml: String,
        val certificationYaml: String?,
        val readme: String?,
        /** Paths relative to [dir], e.g. `impl/python/summarise.py`. */
        val files: List<String> = emptyList(),
    )

    data class WorkflowSource(
        /** Path relative to the workflows directory, e.g. `injections/inj-cycle.yaml`. */
        val path: String,
        val yaml: String,
    )

    fun steps(): List<StepSource> = repoRoot?.let { root ->
        File(root, STEPS_PATH)
            .walkTopDown()
            .filter { it.isFile && it.name == "step.yaml" }
            .map { file ->
                val dir = file.parentFile
                StepSource(
                    dir = dir.relativeTo(File(root, STEPS_PATH)).invariantSeparatorsPath,
                    stepYaml = file.readText(),
                    certificationYaml = File(dir, "certification.yaml")
                        .takeIf { it.isFile }?.readText(),
                    readme = File(dir, "README.md").takeIf { it.isFile }?.readText(),
                    files = dir.walkTopDown()
                        .filter { it.isFile && !it.isNoise() }
                        .map { it.relativeTo(dir).invariantSeparatorsPath }
                        .sorted()
                        .toList(),
                )
            }
            .toList()
    } ?: bundledSteps()

    /** Study protocol snapshots shipped with the demo, as raw JSON. */
    fun protocols(): List<String> = repoRoot?.let { root ->
        File(root, PROTOCOLS_PATH)
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "json" && !it.isNoise() }
            ?.map { it.readText() }
            ?.toList()
    } ?: readIndex("protocols/index.txt")
        .mapNotNull { loader.getResource("protocols/$it")?.readText() }

    /**
     * Sample data files the demo workflows read through a `file` source.
     *
     * Two locations, because the demo repo uses two: the `data/` directory, and
     * files sitting beside the workflows themselves. `raw_heart_rate.csv` is the
     * second kind, and it is what `hr-clean-library` reads - miss it and that
     * demo warns about data it actually has. The build sync flattens both into
     * `data/`, so this mirrors that.
     */
    fun dataFiles(): List<String> = repoRoot?.let { root ->
        val fromDataDir = File(root, DATA_PATH)
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && !it.isNoise() && it.name != "index.txt" }
            ?.map { it.relativeTo(File(root, DATA_PATH)).invariantSeparatorsPath }
            ?.toList()
            .orEmpty()

        val besideWorkflows = File(root, WORKFLOWS_PATH)
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter {
                it.isFile && !it.isNoise() &&
                    it.extension !in setOf("yaml", "yml", "lock", "txt")
            }
            ?.map { it.name }
            .orEmpty()

        (fromDataDir + besideWorkflows).distinct().sorted()
    } ?: readIndex("data/index.txt").filterNot { it == "index.txt" }

    fun dataFileText(path: String): String? = dataFileBytes(path)?.decodeToString()

    fun dataFileBytes(path: String): ByteArray? {
        val safe = path.trim('/')
        if (safe.split('/').contains("..")) return null

        repoRoot?.let { root ->
            File(File(root, DATA_PATH), safe).takeIf { it.isFile }?.let { return it.readBytes() }
            File(File(root, WORKFLOWS_PATH), safe).takeIf { it.isFile }?.let { return it.readBytes() }
            return null
        }

        return loader.getResource("data/$safe")?.readBytes()
    }

    /** Scripts the demo workflows reference, e.g. `mobgap/import_data.py`. */
    fun scripts(): List<String> = repoRoot?.let { root ->
        val dir = File(root, SCRIPTS_PATH)
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "py" && !it.isNoise() }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .sorted()
            .toList()
    } ?: readIndex("scripts/index.txt")

    fun scriptText(path: String): String? = scriptBytes(path)?.decodeToString()

    fun scriptBytes(path: String): ByteArray? {
        val safe = path.trim('/')
        if (safe.split('/').contains("..")) return null

        return repoRoot
            ?.let { File(File(it, SCRIPTS_PATH), safe).takeIf { f -> f.isFile }?.readBytes() }
            ?: loader.getResource("scripts/$safe")?.readBytes()
    }

    /**
     * The scripts and data files a run may need, keyed by the path a workflow
     * would use to reach them.
     *
     * A command step names its script as a plain argument -
     * `scripts/mobgap/gsd.py` - so nothing in the workflow declares it and the
     * engine has no way to stage it. Handing a run the whole tree is a few
     * hundred kilobytes and needs no guess at which parts a given workflow
     * reads.
     */
    fun packageFiles(): Map<String, ByteArray> = buildMap {
        scripts().forEach { rel -> scriptBytes(rel)?.let { put("scripts/$rel", it) } }
        dataFiles().forEach { rel -> dataFileBytes(rel)?.let { put("data/$rel", it) } }
    }

    /** One file inside a step's directory, given the step's `step.yaml` path. */
    fun stepFileText(stepDir: String, path: String): String? {
        val safe = path.trim('/')
        if (safe.split('/').contains("..")) return null

        return repoRoot
            ?.let {
                File(File(File(it, STEPS_PATH), stepDir), safe)
                    .takeIf { f -> f.isFile }
                    ?.readText()
            }
            ?: loader.getResource("steplib/$stepDir/$safe")?.readText()
    }

    /**
     * Workflow directories holding fixtures rather than demos.
     *
     * Must match WORKFLOW_FIXTURE_DIRS in server/build.gradle.kts, which applies
     * the same exclusion when syncing. If the two disagree, repo mode and
     * bundled mode show different catalogues, which is worse than either
     * choice on its own.
     */
    private val FIXTURE_DIRS = setOf("injections")

    fun workflows(): List<WorkflowSource> = repoRoot?.let { root ->
        val base = File(root, WORKFLOWS_PATH)
        base.walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .map { it to it.relativeTo(base).invariantSeparatorsPath }
            .filterNot { (_, rel) ->
                rel.substringBeforeLast('/', "").split('/').any { it in FIXTURE_DIRS }
            }
            .map { (file, rel) -> WorkflowSource(path = rel, yaml = file.readText()) }
            .toList()
    } ?: bundledWorkflows()

    /**
     * `files.txt` lists every packaged file, because a jar cannot list a
     * directory. Grouping by the directory holding a `step.yaml` reconstructs
     * the same shape the repo walk produces.
     */
    private fun bundledSteps(): List<StepSource> {
        val all = readIndex("steplib/files.txt")
        val stepDirs = all.filter { it.endsWith("/step.yaml") }
            .map { it.removeSuffix("/step.yaml") }

        return stepDirs.mapNotNull { dir ->
            val text = loader.getResource("steplib/$dir/step.yaml")?.readText()
                ?: return@mapNotNull null

            StepSource(
                dir = dir,
                stepYaml = text,
                certificationYaml = loader.getResource("steplib/$dir/certification.yaml")?.readText(),
                readme = loader.getResource("steplib/$dir/README.md")?.readText(),
                files = all.filter { it.startsWith("$dir/") }
                    .map { it.removePrefix("$dir/") }
                    .sorted(),
            )
        }
    }

    private fun bundledWorkflows(): List<WorkflowSource> =
        readIndex("workflows/index.txt").mapNotNull { path ->
            val text = loader.getResource("workflows/$path")?.readText() ?: return@mapNotNull null
            WorkflowSource(path = path, yaml = text)
        }

    private fun readIndex(resource: String): List<String> =
        loader.getResource(resource)
            ?.readText()
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()
}

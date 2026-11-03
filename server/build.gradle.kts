// Reads editions.yaml. See the Edition section below.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.yaml:snakeyaml:2.2") }
}

/** Where the step library lives inside a carp-dsp checkout. */
val STEPS_IN_REPO = "carp.dsp.steps/src/jvmMain/resources/steps"

/** Where the demo workflows, protocols and sample data live. */
val WORKFLOWS_IN_REPO = "carp.dsp.demo/src/jvmMain/resources/workflows"
val PROTOCOLS_IN_REPO = "carp.dsp.demo/src/jvmMain/resources/protocols"
val DATA_IN_REPO = "carp.dsp.demo/src/jvmMain/resources/data"
val SCRIPTS_IN_REPO = "carp.dsp.demo/src/jvmMain/resources/scripts"

/**
 * Workflow directories that are fixtures rather than demos.
 *
 * `injections/` holds deliberately-broken clones of the mobgap pipeline, one
 * per validation fault. They exist to prove the validator fires and are not
 * pipelines anyone would copy.
 *
 * Nothing else is excluded. The `-v2` files are not test variants - they are
 * the same pipelines recomposed from library steps, which is the reuse story
 * worth showing. `-ha` is the same mobgap pipeline over the healthy cohort.
 * `protocol-coupling-mixed` is tagged `eval`, but it is the only workflow that
 * exercises protocol and external data sources, so the Data page depends on it.
 */
val WORKFLOW_FIXTURE_DIRS = setOf("injections")

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dk.cachet.carp.dsp.portal.MainKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The real engine. carp.dsp.core keeps its carp-core dependencies as
    // `implementation`, so carp-core-analytics is declared here too - the run
    // report and its types cross this boundary.
    implementation(libs.carp.dsp.core)
    implementation(libs.carp.dsp.steps)
    implementation(libs.carp.core.common)
    implementation(libs.carp.core.analytics)
    // Study protocols are read as core's StudyProtocolSnapshot.
    implementation(libs.carp.core.protocols)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.logback)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    // Specific state file for tests.
    environment("DSP_STATE", layout.buildDirectory.file("test-state/portal-state.json").get().asFile.absolutePath)
}

// ---- SPA build ----------------------------------------------------------
// The React app in ../web is built by pnpm and its output copied into this
// module's resources, so the server ships the SPA inside the jar.
//
// Skip it with -PskipWebBuild=true when working on the Kotlin side only.

val webDir = rootProject.layout.projectDirectory.dir("web")
val webBuildDir = webDir.dir("build")
val skipWebBuild = (project.findProperty("skipWebBuild") as String?)?.toBoolean() ?: false

// pnpm is `pnpm.cmd` on Windows.
val pnpmCommand = if (System.getProperty("os.name").startsWith("Windows", true)) "pnpm.cmd" else "pnpm"

val pnpmInstall by tasks.registering(Exec::class) {
    group = "web"
    description = "Install web dependencies with pnpm."
    workingDir = webDir.asFile
    commandLine(pnpmCommand, "install", "--frozen-lockfile")

    inputs.file(webDir.file("package.json"))
    webDir.file("pnpm-lock.yaml").asFile.takeIf { it.exists() }?.let { inputs.file(it) }
    outputs.dir(webDir.dir("node_modules"))
}

val pnpmBuild by tasks.registering(Exec::class) {
    group = "web"
    description = "Build the React SPA with Vite."
    dependsOn(pnpmInstall)
    workingDir = webDir.asFile
    commandLine(pnpmCommand, "build")

    inputs.dir(webDir.dir("src"))
    inputs.file(webDir.file("package.json"))
    inputs.file(webDir.file("vite.config.ts"))
    outputs.dir(webBuildDir)
}

val copyWebBuild by tasks.registering(Copy::class) {
    group = "web"
    description = "Copy the built SPA into the server's static resources."
    dependsOn(pnpmBuild)
    from(webBuildDir)
    into(layout.buildDirectory.dir("generated-resources/static"))
}

// ---- Edition ------------------------------------------------------------
// editions.yaml at the repo root says what each edition ships with. Pick one
// with -Pedition=<name>; without it, the file's `default` is built.
//
// The chosen edition is written to edition.json in its own resource directory,
// which the server reads at startup (Edition.kt). An edition without content
// clears the synced carp-dsp copies instead of syncing them.

/** Route names the web app has a tab for. Keep in step with web/src/api/edition.ts. */
val KNOWN_TABS = setOf(
    "ubilife", "workflows", "runs", "results", "data",
    "compose", "library", "environments", "upload", "settings",
)

val editionsFile = rootProject.layout.projectDirectory.file("editions.yaml").asFile

val editionsYaml: Map<*, *> = editionsFile
    .takeIf { it.isFile }
    ?.let { org.yaml.snakeyaml.Yaml().load<Any?>(it.readText()) as? Map<*, *> }
    ?: throw GradleException("${editionsFile.absolutePath} is missing or not a map.")

val editionDefs = editionsYaml["editions"] as? Map<*, *>
    ?: throw GradleException("editions.yaml has no `editions` map.")

val editionName: String = (project.findProperty("edition") as String?)
    ?: editionsYaml["default"] as? String
    ?: throw GradleException("No -Pedition given and editions.yaml has no `default`.")

val editionDef = editionDefs[editionName] as? Map<*, *>
    ?: throw GradleException(
        "Unknown edition '$editionName'. editions.yaml defines: ${editionDefs.keys.joinToString()}.",
    )

val editionContent: Boolean = editionDef["content"] as? Boolean
    ?: throw GradleException("Edition '$editionName': `content` must be true or false.")

val editionTabs: List<String> = (editionDef["tabs"] as? List<*>)
    ?.map { it as? String ?: throw GradleException("Edition '$editionName': `tabs` must be names, got '$it'.") }
    ?: throw GradleException("Edition '$editionName': `tabs` must be a list.")

(editionTabs - KNOWN_TABS).takeIf { it.isNotEmpty() }?.let {
    throw GradleException("Edition '$editionName': unknown tabs $it. Known: ${KNOWN_TABS.joinToString()}.")
}
if ("workflows" !in editionTabs) {
    // Every hidden route redirects to /workflows, so it must always be there.
    throw GradleException("Edition '$editionName': `tabs` must include `workflows`.")
}

val editionResources = layout.buildDirectory.dir("generated-edition")

val writeEdition by tasks.registering {
    group = "content"
    description = "Write the chosen edition (-Pedition) into the server's resources."

    val name = editionName
    val content = editionContent
    val tabs = editionTabs
    val out = editionResources.map { it.file("edition.json") }

    inputs.property("edition", name)
    inputs.property("content", content)
    inputs.property("tabs", tabs)
    outputs.file(out)

    doLast {
        val json = """{"name":"$name","content":$content,"tabs":[${tabs.joinToString(",") { "\"$it\"" }}]}"""
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(json + "\n")
        logger.lifecycle("Edition: $name (content: $content, tabs: ${tabs.joinToString()})")
    }
}

// ---- Step library -------------------------------------------------------
// The step library is owned by carp-dsp and is not copied into this repo.
// It is pulled from a checkout at build time, mirroring how carp-dsp itself
// finds a sibling carp.core-kotlin.
//
// Resolution order: -PdspRepo=<path>, then the DSP_REPO environment variable,
// then a sibling ../carp-dsp.
//
// Skip with -PskipStepSync=true. The Docker build does this, because the
// checkout is not in its context - the container reads the library from the
// read-only mount instead. See docker-compose.yml.

val skipStepSync = (project.findProperty("skipStepSync") as String?)?.toBoolean() ?: false

val dspRepo: File? = listOfNotNull(
    project.findProperty("dspRepo") as String?,
    System.getenv("DSP_REPO"),
    rootProject.layout.projectDirectory.dir("../carp-dsp").asFile.path,
).map(::File).firstOrNull { File(it, STEPS_IN_REPO).isDirectory }

/** Build artefacts and cached environments. `.pixi` holds a whole interpreter. */
val NOISE_DIRS = setOf("__pycache__", ".pytest_cache", ".pixi", ".git")

fun isNoise(relative: String, name: String): Boolean =
    relative.split('/').any { it in NOISE_DIRS } ||
        name.endsWith(".pyc") ||
        name == ".gitignore" ||
        name == "CACHEDIR.TAG"

val syncDspContent by tasks.registering {
    group = "content"
    description = "Copy steps, demo workflows, protocols and sample data out of a carp-dsp checkout."

    val target = layout.buildDirectory.dir("generated-resources")
    outputs.dir(target)
    dspRepo?.let { repo ->
        listOf(STEPS_IN_REPO, WORKFLOWS_IN_REPO, PROTOCOLS_IN_REPO, DATA_IN_REPO, SCRIPTS_IN_REPO)
            .map { File(repo, it) }
            .filter { it.isDirectory }
            .forEach { inputs.dir(it) }
    }

    doLast {
        val repo = dspRepo ?: throw GradleException(
            """
            carp-dsp checkout not found.

            Looked for '$STEPS_IN_REPO' under, in order:
              -PdspRepo=<path>
              DSP_REPO environment variable
              ../carp-dsp

            Clone carp-dsp as a sibling of this repo, or pass -PdspRepo=<path>.
            To build without it, pass -PskipStepSync=true - the portal then has no
            steps or demo workflows unless a checkout is mounted at runtime.
            """.trimIndent(),
        )

        val root = target.get().asFile

        /**
         * Copies one directory and writes its index, since a jar cannot list a
         * directory. [keep] decides which entries survive.
         */
        fun sync(
            sourcePath: String,
            into: String,
            indexName: String,
            keep: (String) -> Boolean = { true },
        ): List<String> {
            val source = File(repo, sourcePath)
            val out = File(root, into)
            out.deleteRecursively()
            out.mkdirs()

            if (!source.isDirectory) {
                logger.warn("carp-dsp has no '$sourcePath' - '$into' will be empty.")
                File(out, indexName).writeText("")
                return emptyList()
            }

            val copied = source.walkTopDown()
                .filter { it.isFile }
                .map { it to it.relativeTo(source).invariantSeparatorsPath }
                .filterNot { (file, rel) -> isNoise(rel, file.name) }
                .filter { (_, rel) -> keep(rel) }
                .map { (file, rel) ->
                    File(out, rel).apply { parentFile.mkdirs() }.writeBytes(file.readBytes())
                    rel
                }
                .sorted()
                .toList()

            File(out, indexName).writeText(copied.joinToString("\n", postfix = "\n"))
            return copied
        }

        val steps = sync(STEPS_IN_REPO, "steplib", "files.txt")

        // Demo workflows, minus the deliberately-broken fixtures. Those exist to
        // prove the validator fires, not as pipelines to copy.
        //
        // YAML only: the directory also holds a steps.lock and a sample CSV,
        // and every entry in this index is read back as a workflow.
        val workflows = sync(WORKFLOWS_IN_REPO, "workflows", "index.txt") { rel ->
            (rel.endsWith(".yaml") || rel.endsWith(".yml")) &&
                rel.substringBeforeLast('/', "").split('/').none { it in WORKFLOW_FIXTURE_DIRS }
        }

        val protocols = sync(PROTOCOLS_IN_REPO, "protocols", "index.txt")
        val data = sync(DATA_IN_REPO, "data", "index.txt")

        // Sample data sitting beside the workflows rather than under data/ -
        // raw_heart_rate.csv is the file hr-clean-library reads. Copying it in
        // is what lets that demo resolve its input instead of warning about it.
        val strays = File(repo, WORKFLOWS_IN_REPO)
            .takeIf { it.isDirectory }
            ?.listFiles { f: File ->
                f.isFile &&
                    f.extension !in setOf("yaml", "yml", "lock") &&
                    !isNoise(f.name, f.name)
            }
            .orEmpty()
            .map { file ->
                File(File(root, "data"), file.name).writeBytes(file.readBytes())
                file.name
            }

        if (strays.isNotEmpty()) {
            val index = File(File(root, "data"), "index.txt")
            index.writeText((data + strays).sorted().joinToString("\n", postfix = "\n"))
        }
        val scripts = sync(SCRIPTS_IN_REPO, "scripts", "index.txt")

        logger.lifecycle(
            "From ${repo.absolutePath}: " +
                "${steps.count { it.endsWith("/step.yaml") }} steps, " +
                "${workflows.size} demo workflows, " +
                "${protocols.size} protocols, " +
                "${data.size + strays.size} data files, " +
                "${scripts.size} scripts",
        )
    }
}

/** Removes synced carp-dsp copies, so an edition without content never ships stale ones. */
val clearDspContent by tasks.registering(Delete::class) {
    group = "content"
    description = "Remove the carp-dsp content a previous build synced."
    val root = layout.buildDirectory.dir("generated-resources")
    delete(listOf("steplib", "workflows", "protocols", "data", "scripts").map { sub -> root.map { it.dir(sub) } })
}

sourceSets {
    named("main") {
        resources.srcDir(layout.buildDirectory.dir("generated-resources"))
        resources.srcDir(editionResources)
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(writeEdition)

    // A Copy task keeps files it no longer copies, so switching edition would
    // leave the last edition's content in the jar. Start from empty each run.
    val dest = destinationDir
    doFirst { dest.deleteRecursively() }
}

if (!skipWebBuild) {
    tasks.named("processResources") { dependsOn(copyWebBuild) }
}

when {
    !editionContent -> tasks.named("processResources") { dependsOn(clearDspContent) }
    !skipStepSync -> tasks.named("processResources") { dependsOn(syncDspContent) }
}

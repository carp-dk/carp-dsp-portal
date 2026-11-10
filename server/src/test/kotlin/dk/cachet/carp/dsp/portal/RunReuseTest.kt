package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.api.ExecutionStatus
import dk.cachet.carp.dsp.portal.api.ExecutorState
import dk.cachet.carp.dsp.portal.api.RunContext
import dk.cachet.carp.dsp.portal.api.RunTrigger
import dk.cachet.carp.dsp.portal.run.RunReuse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class RunReuseTest {

    private fun run(id: String, minute: Int, schedule: String? = "s1", status: ExecutionStatus = ExecutionStatus.SUCCEEDED) =
        ExecutorState(
            executionId = id,
            status = status,
            startedAt = "2026-10-12T10:%02d:00Z".format(minute),
            workflowId = "ubilife-compare-live",
            studyId = "study-1",
            context = schedule?.let { RunContext(trigger = RunTrigger.SCHEDULE, scheduleId = it) },
        )

    @Test
    fun `a schedule keeps its newest runs`() {
        val runs = (1..5).map { run("r$it", it) }

        assertEquals(listOf("r2", "r1"), RunReuse.surplus("s1", runs, keep = 3).map { it.executionId })
    }

    @Test
    fun `runs by hand and of other schedules are left alone`() {
        val runs = listOf(run("hand", 1, schedule = null), run("other", 2, schedule = "s2"), run("mine", 3))

        assertEquals(emptyList(), RunReuse.surplus("s1", runs, keep = 1).map { it.executionId })
    }

    @Test
    fun `a run still going is never deleted`() {
        val runs = listOf(run("old", 1, status = ExecutionStatus.RUNNING), run("new", 2))

        assertEquals(emptyList(), RunReuse.surplus("s1", runs, keep = 1).map { it.executionId })
    }

    @Test
    fun `deleting removes the run's directory`() {
        val dir = Files.createTempDirectory("run").toFile().apply { resolve("steps").mkdirs() }

        RunReuse.delete(listOf(run("gone", 1))) { dir }

        assertFalse(dir.exists())
    }

    @Test
    fun `the database a run reads is part of every fingerprint, its paths and heap are not`() {
        val base = mapOf("CARP_DSP_SQL_URL" to "jdbc:postgresql://a/carp", "CARP_DSP_SQL_USER" to "carp")

        assertNotEquals(RunReuse.saltOf(base), RunReuse.saltOf(base + ("CARP_DSP_SQL_URL" to "jdbc:postgresql://b/carp")))
        assertEquals(
            RunReuse.saltOf(base),
            RunReuse.saltOf(base + mapOf("CARP_DSP_TASK_RUNTIME" to "/x", "JAVA_TOOL_OPTIONS" to "-Xmx4g")),
        )
    }
}

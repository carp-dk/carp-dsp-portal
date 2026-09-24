package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.api.Schedule
import dk.cachet.carp.dsp.portal.run.Scheduler
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Where a schedule stands, before any run - the rest is read off its runs. */
class SchedulerTest {

    private fun schedule(cadence: String, startAt: String?) = Schedule(
        scheduleId = "schedule-test",
        studyId = "study",
        workflowId = "wf-without-bindings",
        cadence = cadence,
        startAt = startAt,
        createdAt = "2026-10-12T09:00:00Z",
    )

    @Test
    fun `before its start a schedule next runs at its start`() {
        val view = Scheduler.view(schedule("HOURLY", "2026-10-12T10:00:00Z"), Instant.parse("2026-10-12T09:30:00Z"))

        assertEquals("2026-10-12T10:00:00Z", view.nextRunAt)
        assertFalse(view.completed)
    }

    @Test
    fun `a due schedule that has not fired runs now`() {
        val now = Instant.parse("2026-10-12T10:20:00Z")

        val view = Scheduler.view(schedule("EVERY_15_MINUTES", "2026-10-12T10:00:00Z"), now)

        assertEquals(now.toString(), view.nextRunAt)
    }

    @Test
    fun `a workflow without a window has none to report`() {
        assertNull(Scheduler.view(schedule("DAILY", null), Instant.parse("2026-10-12T10:00:00Z")).windowFrom)
    }

    @Test
    fun `a cadence that is not one never fires`() {
        assertNull(Scheduler.view(schedule("ON_NEW_DATA", null), Instant.parse("2026-10-12T10:00:00Z")).nextRunAt)
    }

    @Test
    fun `a paused schedule has no next run`() {
        val paused = schedule("HOURLY", "2026-10-12T10:00:00Z").copy(enabled = false)

        assertNull(Scheduler.view(paused, Instant.parse("2026-10-12T10:30:00Z")).nextRunAt)
    }
}

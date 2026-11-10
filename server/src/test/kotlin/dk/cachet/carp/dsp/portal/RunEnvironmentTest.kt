package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.api.VariableSource
import dk.cachet.carp.dsp.portal.run.RunEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Variables saved in the portal and handed to steps. */
class RunEnvironmentTest {

    private val name = "PORTAL_TEST_VARIABLE"
    private val secret = "PORTAL_TEST_PASSWORD"

    @AfterTest
    fun clear() {
        RunEnvironment.set(name, null)
        RunEnvironment.set(secret, null)
    }

    @Test
    fun a_saved_value_is_listed_and_handed_to_runs() {
        RunEnvironment.set(name, "value")

        val variable = RunEnvironment.view().single { it.name == name }
        assertEquals("value", variable.value)
        assertEquals(VariableSource.SAVED, variable.source)
        assertEquals("value", RunEnvironment.overrides()[name])
    }

    @Test
    fun an_empty_value_drops_the_saved_one() {
        RunEnvironment.set(name, "value")
        RunEnvironment.set(name, "")

        assertNull(RunEnvironment.overrides()[name])
        assertFalse(RunEnvironment.view().any { it.name == name })
    }

    @Test
    fun a_secret_is_never_listed_with_its_value() {
        RunEnvironment.set(secret, "hunter2")

        val variable = RunEnvironment.view().single { it.name == secret }
        assertTrue(variable.secret)
        assertTrue(variable.set)
        assertNull(variable.value)
    }

    @Test
    fun the_sql_variables_are_always_listed() {
        val names = RunEnvironment.view().map { it.name }

        assertTrue(names.containsAll(listOf(RunEnvironment.SQL_URL, RunEnvironment.SQL_USER, RunEnvironment.SQL_PASSWORD)))
    }

    @Test
    fun a_name_that_is_not_a_variable_is_refused() {
        assertFailsWith<IllegalArgumentException> { RunEnvironment.set("not a name", "x") }
    }
}

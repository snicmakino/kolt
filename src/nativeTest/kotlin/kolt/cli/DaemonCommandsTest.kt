package kolt.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidateDaemonSubcommandTest {

    @Test
    fun stopIsValid() {
        assertTrue(validateDaemonSubcommand(listOf("stop")))
    }

    @Test
    fun stopWithAllFlagIsValid() {
        assertTrue(validateDaemonSubcommand(listOf("stop", "--all")))
    }

    @Test
    fun emptyArgsIsInvalid() {
        assertFalse(validateDaemonSubcommand(emptyList()))
    }

    @Test
    fun unknownSubcommandIsInvalid() {
        assertFalse(validateDaemonSubcommand(listOf("restart")))
    }
}

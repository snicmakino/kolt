package kolt.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidateDepsSubcommandTest {

    @Test
    fun validTreeSubcommand() {
        assertTrue(validateDepsSubcommand(listOf("tree")))
    }

    @Test
    fun emptyArgsReturnsInvalid() {
        assertFalse(validateDepsSubcommand(emptyList()))
    }

    @Test
    fun unknownSubcommandReturnsInvalid() {
        assertFalse(validateDepsSubcommand(listOf("list")))
    }

    @Test
    fun treeWithExtraArgsIsValid() {
        assertTrue(validateDepsSubcommand(listOf("tree", "--verbose")))
    }
}

package kolt.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class ExitCodeTest {
    @Test
    fun exitCodesHaveDistinctValues() {
        val codes = listOf(EXIT_SUCCESS, EXIT_BUILD_ERROR, EXIT_CONFIG_ERROR, EXIT_DEPENDENCY_ERROR, EXIT_COMMAND_NOT_FOUND)
        assertEquals(codes.size, codes.toSet().size, "Exit codes must be unique")
    }

    @Test
    fun exitSuccessIsZero() {
        assertEquals(0, EXIT_SUCCESS)
    }

    @Test
    fun exitCodesMatchSpec() {
        assertEquals(1, EXIT_BUILD_ERROR)
        assertEquals(2, EXIT_CONFIG_ERROR)
        assertEquals(3, EXIT_DEPENDENCY_ERROR)
        assertEquals(127, EXIT_COMMAND_NOT_FOUND)
    }
}

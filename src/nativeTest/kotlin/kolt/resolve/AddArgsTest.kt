package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddArgsTest {

    @Test
    fun parseFullCoordinate() {
        val result = parseAddArgs(listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0"))
        val args = assertNotNull(result.get())
        assertEquals("org.jetbrains.kotlinx", args.group)
        assertEquals("kotlinx-coroutines-core", args.artifact)
        assertEquals("1.9.0", args.version)
        assertFalse(args.isTest)
    }

    @Test
    fun parseWithoutVersion() {
        val result = parseAddArgs(listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core"))
        val args = assertNotNull(result.get())
        assertEquals("org.jetbrains.kotlinx", args.group)
        assertEquals("kotlinx-coroutines-core", args.artifact)
        assertNull(args.version)
        assertFalse(args.isTest)
    }

    @Test
    fun parseWithTestFlag() {
        val result = parseAddArgs(listOf("--test", "io.kotest:kotest-runner-junit5:5.8.0"))
        val args = assertNotNull(result.get())
        assertEquals("io.kotest", args.group)
        assertEquals("kotest-runner-junit5", args.artifact)
        assertEquals("5.8.0", args.version)
        assertTrue(args.isTest)
    }

    @Test
    fun parseTestFlagAfterCoordinate() {
        val result = parseAddArgs(listOf("io.kotest:kotest-runner-junit5:5.8.0", "--test"))
        val args = assertNotNull(result.get())
        assertTrue(args.isTest)
    }

    @Test
    fun parseEmptyArgsReturnsErr() {
        val result = parseAddArgs(emptyList())
        assertIs<AddArgsError.MissingCoordinate>(result.getError())
    }

    @Test
    fun parseInvalidCoordinateReturnsErr() {
        val result = parseAddArgs(listOf("invalid"))
        assertIs<AddArgsError.InvalidFormat>(result.getError())
    }

    @Test
    fun parseTooManyColonsReturnsErr() {
        val result = parseAddArgs(listOf("a:b:c:d"))
        assertIs<AddArgsError.InvalidFormat>(result.getError())
    }

    @Test
    fun parseTestFlagOnlyReturnsErr() {
        val result = parseAddArgs(listOf("--test"))
        assertIs<AddArgsError.MissingCoordinate>(result.getError())
    }

    @Test
    fun parseEmptyGroupReturnsErr() {
        val result = parseAddArgs(listOf(":artifact"))
        assertIs<AddArgsError.InvalidFormat>(result.getError())
    }

    @Test
    fun parseEmptyArtifactReturnsErr() {
        val result = parseAddArgs(listOf("group:"))
        assertIs<AddArgsError.InvalidFormat>(result.getError())
    }
}

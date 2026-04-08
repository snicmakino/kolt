package keel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitTest {
    @Test
    fun generateTomlUsesProjectName() {
        val toml = generateKeelToml("my-app")
        assertTrue(toml.contains("name = \"my-app\""))
    }

    @Test
    fun generateTomlContainsRequiredFields() {
        val toml = generateKeelToml("hello")
        assertTrue(toml.contains("version = "))
        assertTrue(toml.contains("kotlin = "))
        assertTrue(toml.contains("target = \"jvm\""))
        assertTrue(toml.contains("main = "))
        assertTrue(toml.contains("sources = "))
    }

    @Test
    fun generateTomlMainMatchesProjectName() {
        val toml = generateKeelToml("my-app")
        assertTrue(toml.contains("main = \"MainKt\""))
    }

    @Test
    fun generateMainKtContainsMainFunction() {
        val source = generateMainKt()
        assertTrue(source.contains("fun main()"))
    }

    @Test
    fun projectNameFromDirName() {
        assertEquals("my-app", inferProjectName("/home/user/projects/my-app"))
        assertEquals("hello", inferProjectName("/tmp/hello"))
    }
}

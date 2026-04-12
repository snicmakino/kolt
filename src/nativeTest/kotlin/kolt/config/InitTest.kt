package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitTest {
    @Test
    fun generateTomlUsesProjectName() {
        val toml = generateKoltToml("my-app")
        assertTrue(toml.contains("name = \"my-app\""))
    }

    @Test
    fun generateTomlContainsRequiredFields() {
        val toml = generateKoltToml("hello")
        assertTrue(toml.contains("version = "))
        assertTrue(toml.contains("kotlin = "))
        assertTrue(toml.contains("target = \"jvm\""))
        assertTrue(toml.contains("main = "))
        assertTrue(toml.contains("sources = "))
    }

    @Test
    fun generateTomlMainMatchesProjectName() {
        val toml = generateKoltToml("my-app")
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

    @Test
    fun projectNameFromRootFallsBackToDefault() {
        assertEquals("project", inferProjectName("/"))
    }

    @Test
    fun projectNameFromEmptyStringFallsBackToDefault() {
        assertEquals("project", inferProjectName(""))
    }

    @Test
    fun validProjectNames() {
        assertTrue(isValidProjectName("my-app"))
        assertTrue(isValidProjectName("hello"))
        assertTrue(isValidProjectName("app_v2"))
        assertTrue(isValidProjectName("My.Project"))
    }

    @Test
    fun generateTomlDoesNotContainTestDependencies() {
        val toml = generateKoltToml("my-app")
        // kotlin-test-junit5 is auto-injected by kolt, not declared in kolt.toml
        assertFalse(toml.contains("[test-dependencies]"))
        assertFalse(toml.contains("kotlin-test-junit5"))
    }

    @Test
    fun generateTestKtContainsTestAnnotation() {
        val source = generateTestKt()
        assertTrue(source.contains("@Test"))
        assertTrue(source.contains("import kotlin.test.Test"))
    }

    @Test
    fun invalidProjectNames() {
        assertFalse(isValidProjectName(""))
        assertFalse(isValidProjectName("my\"app"))
        assertFalse(isValidProjectName("my app"))
        assertFalse(isValidProjectName("-start-with-dash"))
        assertFalse(isValidProjectName(".hidden"))
    }
}

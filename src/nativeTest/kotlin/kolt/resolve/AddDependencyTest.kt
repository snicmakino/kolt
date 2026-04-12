package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AddDependencyTest {

    @Test
    fun addDependencyToExistingSection() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "MainKt"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
        """.trimIndent()

        val result = addDependencyToToml(toml, "com.squareup.okhttp3:okhttp", "4.12.0", false)
        val updated = assertNotNull(result.get())
        assertTrue(updated.contains(""""com.squareup.okhttp3:okhttp" = "4.12.0""""))
        assertTrue(updated.contains(""""org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0""""))
    }

    @Test
    fun addDependencyCreatesSection() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "MainKt"
            sources = ["src"]
        """.trimIndent()

        val result = addDependencyToToml(toml, "com.squareup.okhttp3:okhttp", "4.12.0", false)
        val updated = assertNotNull(result.get())
        assertTrue(updated.contains("[dependencies]"))
        assertTrue(updated.contains(""""com.squareup.okhttp3:okhttp" = "4.12.0""""))
    }

    @Test
    fun addTestDependency() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "MainKt"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
        """.trimIndent()

        val result = addDependencyToToml(toml, "io.kotest:kotest-runner-junit5", "5.8.0", true)
        val updated = assertNotNull(result.get())
        assertTrue(updated.contains("[test-dependencies]"))
        assertTrue(updated.contains(""""io.kotest:kotest-runner-junit5" = "5.8.0""""))
    }

    @Test
    fun addDuplicateDependencyReturnsErr() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "MainKt"
            sources = ["src"]

            [dependencies]
            "com.squareup.okhttp3:okhttp" = "4.12.0"
        """.trimIndent()

        val result = addDependencyToToml(toml, "com.squareup.okhttp3:okhttp", "4.13.0", false)
        assertIs<AlreadyExists>(result.getError())
    }

    @Test
    fun addTestDependencyToExistingTestSection() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "MainKt"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

            [test-dependencies]
            "io.kotest:kotest-runner-junit5" = "5.8.0"
        """.trimIndent()

        val result = addDependencyToToml(toml, "io.mockk:mockk", "1.13.0", true)
        val updated = assertNotNull(result.get())
        assertTrue(updated.contains(""""io.mockk:mockk" = "1.13.0""""))
        assertTrue(updated.contains(""""io.kotest:kotest-runner-junit5" = "5.8.0""""))
    }

    @Test
    fun addDependencyPreservesBlankLineBetweenSections() {
        val toml = listOf(
            "[dependencies]",
            "\"org.jetbrains.kotlinx:kotlinx-coroutines-core\" = \"1.9.0\"",
            "",
            "[test-dependencies]",
            "\"io.kotest:kotest-runner-junit5\" = \"5.8.0\""
        ).joinToString("\n")

        val result = addDependencyToToml(toml, "com.squareup.okhttp3:okhttp", "4.12.0", false)
        val updated = assertNotNull(result.get())
        val lines = updated.lines()
        // The blank line between sections should still be present
        val testSectionIndex = lines.indexOfFirst { it == "[test-dependencies]" }
        assertTrue(testSectionIndex > 0)
        assertEquals("", lines[testSectionIndex - 1])
    }

    @Test
    fun addDependencyToFileWithTrailingNewline() {
        val toml = "[dependencies]\n\"org.jetbrains.kotlinx:kotlinx-coroutines-core\" = \"1.9.0\"\n"

        val result = addDependencyToToml(toml, "com.squareup.okhttp3:okhttp", "4.12.0", false)
        val updated = assertNotNull(result.get())
        assertTrue(updated.endsWith("\n"))
    }

    @Test
    fun addNewSectionAppendsTrailingNewline() {
        val toml = "name = \"my-app\"\nversion = \"0.1.0\"\n"

        val result = addDependencyToToml(toml, "com.squareup.okhttp3:okhttp", "4.12.0", false)
        val updated = assertNotNull(result.get())
        assertTrue(updated.endsWith("\n"))
    }

    @Test
    fun addToTestSectionAllowsSameDepInMainSection() {
        val toml = listOf(
            "[dependencies]",
            "\"com.squareup.okhttp3:okhttp\" = \"4.12.0\"",
        ).joinToString("\n")

        val result = addDependencyToToml(toml, "com.squareup.okhttp3:okhttp", "4.12.0", true)
        val updated = assertNotNull(result.get())
        assertTrue(updated.contains("[test-dependencies]"))
        assertTrue(updated.contains("\"com.squareup.okhttp3:okhttp\" = \"4.12.0\""))
    }
}

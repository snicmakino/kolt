package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R1 matrix: `kind` × `[build] main` validation.
 *
 * Replaces the former `kindLibIsRejectedAsNotYetImplemented` case.
 * Canonical error strings per ADR 0023 §1 / design.md (§Components and
 * Interfaces → Config parser kind+main rule):
 *   - LIB_WITH_MAIN_ERROR    = "main has no meaning for a library; remove it"
 *   - APP_WITHOUT_MAIN_ERROR = "[build] main is required for kind = \"app\""
 *
 * Substring matching keeps these tests stable against incidental wording
 * changes allowed by design.md.
 */
class ConfigKindTest {

    // R1.1: kind = "lib" without [build] main → parses.
    @Test
    fun kindLibWithoutMainParses() {
        val toml = """
            name = "my-lib"
            version = "0.1.0"
            kind = "lib"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("lib", config.kind)
        assertNull(config.build.main)
    }

    // R1.2: kind = "lib" with [build] main → rejects with canonical text.
    @Test
    fun kindLibWithMainRejectsWithCanonicalText() {
        val toml = """
            name = "my-lib"
            version = "0.1.0"
            kind = "lib"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val result = parseConfig(toml)

        assertNull(result.get())
        val error = assertIs<ConfigError.ParseFailed>(result.getError())
        assertContains(error.message, "main has no meaning for a library; remove it")
    }

    // R1.3: kind = "app" without [build] main → rejects with canonical text.
    @Test
    fun kindAppWithoutMainRejectsWithCanonicalText() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kind = "app"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            sources = ["src"]
        """.trimIndent()

        val result = parseConfig(toml)

        assertNull(result.get())
        val error = assertIs<ConfigError.ParseFailed>(result.getError())
        assertContains(error.message, "[build] main is required for kind = \"app\"")
    }

    // R1.4: kind = "app" with [build] main → parses.
    @Test
    fun kindAppWithMainParses() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kind = "app"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("app", config.kind)
        assertEquals("com.example.main", config.build.main)
    }

    // R1.5: omitted kind → defaults to "app"; with [build] main it parses.
    @Test
    fun missingKindDefaultsToApp() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("app", config.kind)
        assertEquals("com.example.main", config.build.main)
    }
}

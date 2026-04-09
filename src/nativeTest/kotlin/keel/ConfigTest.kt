package keel

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigTest {

    private val minimalToml = """
        name = "my-app"
        version = "0.1.0"
        kotlin = "2.1.0"
        target = "jvm"
        main = "com.example.MainKt"
        sources = ["src"]
    """.trimIndent()

    @Test
    fun parseMinimalConfig() {
        val result = parseConfig(minimalToml)

        val config = assertNotNull(result.get())
        assertEquals("my-app", config.name)
        assertEquals("0.1.0", config.version)
        assertEquals("2.1.0", config.kotlin)
        assertEquals("jvm", config.target)
        assertEquals("com.example.MainKt", config.main)
        assertEquals(listOf("src"), config.sources)
        assertEquals("17", config.jvmTarget)
        assertEquals(emptyMap(), config.dependencies)
    }

    @Test
    fun parseConfigWithExplicitJvmTarget() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            jvm_target = "21"
            main = "com.example.MainKt"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("21", config.jvmTarget)
    }

    @Test
    fun parseConfigWithDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
            "com.squareup.okhttp3:okhttp" = "4.12.0"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(2, config.dependencies.size)
        assertEquals("1.9.0", config.dependencies["org.jetbrains.kotlinx:kotlinx-coroutines-core"])
        assertEquals("4.12.0", config.dependencies["com.squareup.okhttp3:okhttp"])
    }

    @Test
    fun parseConfigWithMultipleSources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src", "generated"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("src", "generated"), config.sources)
    }

    @Test
    fun missingRequiredFieldReturnsErr() {
        val toml = """
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
        """.trimIndent()

        val result = parseConfig(toml)

        assertNull(result.get())
        assertIs<ConfigError.ParseFailed>(result.getError())
    }

    @Test
    fun invalidTomlReturnsErr() {
        val result = parseConfig("not valid toml [[[")

        assertNull(result.get())
        assertIs<ConfigError.ParseFailed>(result.getError())
    }

    @Test
    fun emptySourcesArray() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = []
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(emptyList(), config.sources)
    }

    @Test
    fun wrongFieldTypeReturnsErr() {
        val toml = """
            name = 123
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
        """.trimIndent()

        val result = parseConfig(toml)

        assertNull(result.get())
        assertIs<ConfigError.ParseFailed>(result.getError())
    }

    @Test
    fun wrongDependencyValueTypeReturnsErr() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [dependencies]
            "org.example:lib" = 123
        """.trimIndent()

        val result = parseConfig(toml)

        assertNull(result.get())
        assertIs<ConfigError.ParseFailed>(result.getError())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
            unknown_field = "value"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("my-app", config.name)
    }

    @Test
    fun parseMinimalConfigHasDefaultTestSources() {
        val config = assertNotNull(parseConfig(minimalToml).get())
        assertEquals(listOf("test"), config.testSources)
        assertEquals(emptyMap(), config.testDependencies)
    }

    @Test
    fun parseConfigWithTestSources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
            test_sources = ["test", "integration-test"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("test", "integration-test"), config.testSources)
    }

    @Test
    fun parseConfigWithTestDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [test-dependencies]
            "org.jetbrains.kotlin:kotlin-test-junit5" = "2.1.0"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(1, config.testDependencies.size)
        assertEquals("2.1.0", config.testDependencies["org.jetbrains.kotlin:kotlin-test-junit5"])
    }

    @Test
    fun parseConfigWithBothDependenciesAndTestDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

            [test-dependencies]
            "org.jetbrains.kotlin:kotlin-test-junit5" = "2.1.0"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(1, config.dependencies.size)
        assertEquals(1, config.testDependencies.size)
    }

    @Test
    fun commentsAreIgnored() {
        val toml = """
            # Project configuration
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"] # main source directory
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("my-app", config.name)
    }
}

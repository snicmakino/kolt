package kolt.config

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

        [kotlin]
        version = "2.1.0"

        [build]
        target = "jvm"
        main = "com.example.main"
        sources = ["src"]
    """.trimIndent()

    @Test
    fun parseMinimalConfig() {
        val result = parseConfig(minimalToml)

        val config = assertNotNull(result.get())
        assertEquals("my-app", config.name)
        assertEquals("0.1.0", config.version)
        assertEquals("2.1.0", config.kotlin.version)
        assertEquals("jvm", config.build.target)
        assertEquals("com.example.main", config.build.main)
        assertEquals(listOf("src"), config.build.sources)
        assertEquals("17", config.build.jvmTarget)
        assertEquals(emptyMap(), config.dependencies)
    }

    @Test
    fun parseConfigWithExplicitJvmTarget() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            jvm_target = "21"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("21", config.build.jvmTarget)
    }

    @Test
    fun parseConfigWithDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
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

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src", "generated"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("src", "generated"), config.build.sources)
    }

    @Test
    fun missingRequiredFieldReturnsErr() {
        val toml = """
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
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

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = []
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(emptyList(), config.build.sources)
    }

    @Test
    fun wrongFieldTypeReturnsErr() {
        val toml = """
            name = 123
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
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

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]

            [dependencies]
            "org.example:lib" = 123
        """.trimIndent()

        val result = parseConfig(toml)

        assertNull(result.get())
        assertIs<ConfigError.ParseFailed>(result.getError())
    }

    @Test
    fun parseConfigWithNativeTarget() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "native"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("native", config.build.target)
    }

    @Test
    fun unknownTargetReturnsErr() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "wasm"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val result = parseConfig(toml)

        assertNull(result.get())
        val error = assertIs<ConfigError.ParseFailed>(result.getError())
        kotlin.test.assertTrue(error.message.contains("target"))
        kotlin.test.assertTrue(error.message.contains("jvm"))
        kotlin.test.assertTrue(error.message.contains("native"))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            unknown_field = "value"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("my-app", config.name)
    }

    @Test
    fun parseMinimalConfigHasDefaultTestSources() {
        val config = assertNotNull(parseConfig(minimalToml).get())
        assertEquals(listOf("test"), config.build.testSources)
        assertEquals(emptyMap(), config.testDependencies)
    }

    @Test
    fun parseConfigWithTestSources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
            test_sources = ["test", "integration-test"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("test", "integration-test"), config.build.testSources)
    }

    @Test
    fun parseConfigWithTestDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
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

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
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
    fun parseMinimalConfigHasDefaultFmtStyle() {
        val config = assertNotNull(parseConfig(minimalToml).get())
        assertEquals("google", config.fmt.style)
    }

    @Test
    fun parseConfigWithFmtStyle() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]

            [fmt]
            style = "kotlinlang"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("kotlinlang", config.fmt.style)
    }

    @Test
    fun parseMinimalConfigHasNoPlugins() {
        val config = assertNotNull(parseConfig(minimalToml).get())
        assertEquals(emptyMap(), config.kotlin.plugins)
    }

    @Test
    fun parseConfigWithPlugins() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [kotlin.plugins]
            serialization = true

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(1, config.kotlin.plugins.size)
        assertEquals(true, config.kotlin.plugins["serialization"])
    }

    @Test
    fun parseConfigWithMultiplePlugins() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [kotlin.plugins]
            serialization = true
            allopen = true
            noarg = false

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(3, config.kotlin.plugins.size)
        assertEquals(true, config.kotlin.plugins["serialization"])
        assertEquals(true, config.kotlin.plugins["allopen"])
        assertEquals(false, config.kotlin.plugins["noarg"])
    }

    @Test
    fun parseConfigWithPluginsAndDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [kotlin.plugins]
            serialization = true

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-serialization-json" = "1.7.0"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(1, config.kotlin.plugins.size)
        assertEquals(1, config.dependencies.size)
    }

    @Test
    fun parseMinimalConfigHasDefaultRepositories() {
        val config = assertNotNull(parseConfig(minimalToml).get())

        assertEquals(1, config.repositories.size)
        assertEquals(MAVEN_CENTRAL_BASE, config.repositories["central"])
    }

    @Test
    fun parseConfigWithRepositories() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]

            [repositories]
            myrepo = "https://nexus.example.com/repository/maven-public"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(1, config.repositories.size)
        assertEquals("https://nexus.example.com/repository/maven-public", config.repositories["myrepo"])
    }

    @Test
    fun parseConfigWithMultipleRepositoriesPreservesOrder() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]

            [repositories]
            internal = "https://nexus.example.com/repository/internal"
            central = "https://repo1.maven.org/maven2"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(2, config.repositories.size)
        val entries = config.repositories.entries.toList()
        assertEquals("internal", entries[0].key)
        assertEquals("https://nexus.example.com/repository/internal", entries[0].value)
        assertEquals("central", entries[1].key)
        assertEquals("https://repo1.maven.org/maven2", entries[1].value)
    }

    @Test
    fun parseConfigWithRepositoriesAndDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

            [repositories]
            central = "https://repo1.maven.org/maven2"
            internal = "https://nexus.example.com/repository/maven-public"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(1, config.dependencies.size)
        assertEquals(2, config.repositories.size)
    }

    @Test
    fun repositoryTrailingSlashIsNormalized() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]

            [repositories]
            jitpack = "https://jitpack.io/"
            central = "https://repo1.maven.org/maven2/"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("https://jitpack.io", config.repositories["jitpack"])
        assertEquals(MAVEN_CENTRAL_BASE, config.repositories["central"])
    }

    @Test
    fun parseMinimalConfigHasDefaultResources() {
        val config = assertNotNull(parseConfig(minimalToml).get())

        assertEquals(emptyList(), config.build.resources)
        assertEquals(emptyList(), config.build.testResources)
    }

    @Test
    fun parseConfigWithResources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
            resources = ["resources"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("resources"), config.build.resources)
    }

    @Test
    fun parseConfigWithMultipleResources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
            resources = ["resources", "assets"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("resources", "assets"), config.build.resources)
    }

    @Test
    fun parseConfigWithTestResources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
            test_resources = ["test-resources"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("test-resources"), config.build.testResources)
    }

    @Test
    fun parseConfigWithBothResourceFields() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
            resources = ["resources"]
            test_resources = ["test-resources"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("resources"), config.build.resources)
        assertEquals(listOf("test-resources"), config.build.testResources)
    }

    @Test
    fun parseConfigWithEmptyResources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
            resources = []
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(emptyList(), config.build.resources)
    }

    @Test
    fun commentsAreIgnored() {
        val toml = """
            # Project configuration
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"] # main source directory
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("my-app", config.name)
    }

    @Test
    fun mavenCentralBaseDefinedInConfigPackage() {
        assertEquals("https://repo1.maven.org/maven2", MAVEN_CENTRAL_BASE)
        val config = assertNotNull(parseConfig(minimalToml).get())
        assertEquals(MAVEN_CENTRAL_BASE, config.repositories["central"])
    }

    @Test
    fun parseMinimalConfigHasNullJdk() {
        val config = assertNotNull(parseConfig(minimalToml).get())

        assertNull(config.build.jdk)
    }

    @Test
    fun parseConfigWithJdkVersion() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            jdk = "21"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("21", config.build.jdk)
    }

    @Test
    fun parseConfigWithJdkAndJvmTargetAreIndependent() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            jdk = "21"
            jvm_target = "17"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("21", config.build.jdk)
        assertEquals("17", config.build.jvmTarget)
    }

    @Test
    fun parseMinimalConfigHasEmptyCinteropList() {
        val config = assertNotNull(parseConfig(minimalToml).get())

        assertEquals(emptyList(), config.cinterop)
    }

    @Test
    fun parseConfigWithSingleCinteropEntry() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "native"
            main = "com.example.main"
            sources = ["src"]

            [[cinterop]]
            name = "libcurl"
            def = "src/nativeInterop/cinterop/libcurl.def"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())

        assertEquals(1, config.cinterop.size)
        val entry = config.cinterop[0]
        assertEquals("libcurl", entry.name)
        assertEquals("src/nativeInterop/cinterop/libcurl.def", entry.def)
        assertNull(entry.packageName)
    }

    @Test
    fun parseConfigWithCinteropEntryAllFields() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "native"
            main = "com.example.main"
            sources = ["src"]

            [[cinterop]]
            name = "libcurl"
            def = "src/nativeInterop/cinterop/libcurl.def"
            package = "libcurl"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())

        assertEquals(1, config.cinterop.size)
        val entry = config.cinterop[0]
        assertEquals("libcurl", entry.name)
        assertEquals("src/nativeInterop/cinterop/libcurl.def", entry.def)
        assertEquals("libcurl", entry.packageName)
    }

    @Test
    fun parseConfigWithMultipleCinteropEntries() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "native"
            main = "com.example.main"
            sources = ["src"]

            [[cinterop]]
            name = "libcurl"
            def = "src/nativeInterop/cinterop/libcurl.def"

            [[cinterop]]
            name = "openssl"
            def = "src/nativeInterop/cinterop/openssl.def"
            package = "openssl"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())

        assertEquals(2, config.cinterop.size)
        assertEquals("libcurl", config.cinterop[0].name)
        assertEquals("openssl", config.cinterop[1].name)
        assertNull(config.cinterop[0].packageName)
        assertEquals("openssl", config.cinterop[1].packageName)
    }

    @Test
    fun parseConfigCinteropWithDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "native"
            main = "com.example.main"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

            [[cinterop]]
            name = "libcurl"
            def = "libcurl.def"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())

        assertEquals(1, config.dependencies.size)
        assertEquals(1, config.cinterop.size)
    }
}

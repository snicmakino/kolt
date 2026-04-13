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
    fun parseConfigWithNativeTarget() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "native"
            main = "com.example.MainKt"
            sources = ["src"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("native", config.target)
    }

    @Test
    fun unknownTargetReturnsErr() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "wasm"
            main = "com.example.MainKt"
            sources = ["src"]
        """.trimIndent()

        val result = parseConfig(toml)

        assertNull(result.get())
        val error = assertIs<ConfigError.ParseFailed>(result.getError())
        // message should mention valid targets
        kotlin.test.assertTrue(error.message.contains("target"))
        kotlin.test.assertTrue(error.message.contains("jvm"))
        kotlin.test.assertTrue(error.message.contains("native"))
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
    fun parseMinimalConfigHasDefaultFmtStyle() {
        val config = assertNotNull(parseConfig(minimalToml).get())
        assertEquals("google", config.fmtStyle)
    }

    @Test
    fun parseConfigWithFmtStyle() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
            fmt_style = "kotlinlang"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals("kotlinlang", config.fmtStyle)
    }

    @Test
    fun parseMinimalConfigHasNoPlugins() {
        val config = assertNotNull(parseConfig(minimalToml).get())
        assertEquals(emptyMap(), config.plugins)
    }

    @Test
    fun parseConfigWithPlugins() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [plugins]
            serialization = true
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(1, config.plugins.size)
        assertEquals(true, config.plugins["serialization"])
    }

    @Test
    fun parseConfigWithMultiplePlugins() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [plugins]
            serialization = true
            allopen = true
            noarg = false
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(3, config.plugins.size)
        assertEquals(true, config.plugins["serialization"])
        assertEquals(true, config.plugins["allopen"])
        assertEquals(false, config.plugins["noarg"])
    }

    @Test
    fun parseConfigWithPluginsAndDependencies() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-serialization-json" = "1.7.0"

            [plugins]
            serialization = true
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(1, config.plugins.size)
        assertEquals(1, config.dependencies.size)
    }

    @Test
    fun parseMinimalConfigHasDefaultRepositories() {
        // Given: no [repositories] section in TOML
        // When: config is parsed
        val config = assertNotNull(parseConfig(minimalToml).get())

        // Then: default is Maven Central
        assertEquals(1, config.repositories.size)
        assertEquals(MAVEN_CENTRAL_BASE, config.repositories["central"])
    }

    @Test
    fun parseConfigWithRepositories() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
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
        // Given: two repositories declared in a specific order
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [repositories]
            internal = "https://nexus.example.com/repository/internal"
            central = "https://repo1.maven.org/maven2"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(2, config.repositories.size)
        // Map preserves insertion order
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
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
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
        // Given: a repository URL with a trailing slash
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]

            [repositories]
            jitpack = "https://jitpack.io/"
            central = "https://repo1.maven.org/maven2/"
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        // Trailing slashes must be stripped to prevent double-slash in constructed URLs
        assertEquals("https://jitpack.io", config.repositories["jitpack"])
        assertEquals(MAVEN_CENTRAL_BASE, config.repositories["central"])
    }

    @Test
    fun parseMinimalConfigHasDefaultResources() {
        // Given: no resources or test_resources fields in TOML
        // When: config is parsed
        val config = assertNotNull(parseConfig(minimalToml).get())

        // Then: both default to empty list
        assertEquals(emptyList(), config.resources)
        assertEquals(emptyList(), config.testResources)
    }

    @Test
    fun parseConfigWithResources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
            resources = ["resources"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("resources"), config.resources)
    }

    @Test
    fun parseConfigWithMultipleResources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
            resources = ["resources", "assets"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("resources", "assets"), config.resources)
    }

    @Test
    fun parseConfigWithTestResources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
            test_resources = ["test-resources"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("test-resources"), config.testResources)
    }

    @Test
    fun parseConfigWithBothResourceFields() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
            resources = ["resources"]
            test_resources = ["test-resources"]
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(listOf("resources"), config.resources)
        assertEquals(listOf("test-resources"), config.testResources)
    }

    @Test
    fun parseConfigWithEmptyResources() {
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            main = "com.example.MainKt"
            sources = ["src"]
            resources = []
        """.trimIndent()

        val config = assertNotNull(parseConfig(toml).get())
        assertEquals(emptyList(), config.resources)
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

    @Test
    fun mavenCentralBaseDefinedInConfigPackage() {
        // Verifies MAVEN_CENTRAL_BASE is defined in kolt.config (not scattered to resolve/tool),
        // and matches the canonical Maven Central URL used as the default repository.
        assertEquals("https://repo1.maven.org/maven2", MAVEN_CENTRAL_BASE)
        val config = assertNotNull(parseConfig(minimalToml).get())
        assertEquals(MAVEN_CENTRAL_BASE, config.repositories["central"])
    }

    // --- jdk field ---

    @Test
    fun parseMinimalConfigHasNullJdk() {
        // Given: minimal config with no jdk field
        // When: parsing
        val config = assertNotNull(parseConfig(minimalToml).get())

        // Then: jdk defaults to null (use system java)
        assertNull(config.jdk)
    }

    @Test
    fun parseConfigWithJdkVersion() {
        // Given: config with jdk = "21"
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            jdk = "21"
            main = "com.example.MainKt"
            sources = ["src"]
        """.trimIndent()

        // When: parsing
        val config = assertNotNull(parseConfig(toml).get())

        // Then: jdk field contains the specified version
        assertEquals("21", config.jdk)
    }

    @Test
    fun parseConfigWithJdkAndJvmTargetAreIndependent() {
        // Given: config with both jdk and jvm_target set to different values
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "jvm"
            jdk = "21"
            jvm_target = "17"
            main = "com.example.MainKt"
            sources = ["src"]
        """.trimIndent()

        // When: parsing
        val config = assertNotNull(parseConfig(toml).get())

        // Then: jdk and jvm_target are independent fields
        assertEquals("21", config.jdk)
        assertEquals("17", config.jvmTarget)
    }

    // --- [[cinterop]] ---

    @Test
    fun parseMinimalConfigHasEmptyCinteropList() {
        // Given: no [[cinterop]] section in TOML
        // When: config is parsed
        val config = assertNotNull(parseConfig(minimalToml).get())

        // Then: cinterop defaults to empty list
        assertEquals(emptyList(), config.cinterop)
    }

    @Test
    fun parseConfigWithSingleCinteropEntry() {
        // Given: one [[cinterop]] entry with only required fields
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "native"
            main = "com.example.MainKt"
            sources = ["src"]

            [[cinterop]]
            name = "libcurl"
            def = "src/nativeInterop/cinterop/libcurl.def"
        """.trimIndent()

        // When: config is parsed
        val config = assertNotNull(parseConfig(toml).get())

        // Then: one entry is parsed with correct fields
        assertEquals(1, config.cinterop.size)
        val entry = config.cinterop[0]
        assertEquals("libcurl", entry.name)
        assertEquals("src/nativeInterop/cinterop/libcurl.def", entry.def)
        assertNull(entry.packageName)
    }

    @Test
    fun parseConfigWithCinteropEntryAllFields() {
        // Given: one [[cinterop]] entry with all optional fields
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "native"
            main = "com.example.MainKt"
            sources = ["src"]

            [[cinterop]]
            name = "libcurl"
            def = "src/nativeInterop/cinterop/libcurl.def"
            package = "libcurl"
        """.trimIndent()

        // When: config is parsed
        val config = assertNotNull(parseConfig(toml).get())

        // Then: all fields are parsed correctly
        assertEquals(1, config.cinterop.size)
        val entry = config.cinterop[0]
        assertEquals("libcurl", entry.name)
        assertEquals("src/nativeInterop/cinterop/libcurl.def", entry.def)
        assertEquals("libcurl", entry.packageName)
    }

    @Test
    fun parseConfigWithMultipleCinteropEntries() {
        // Given: two [[cinterop]] entries
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "native"
            main = "com.example.MainKt"
            sources = ["src"]

            [[cinterop]]
            name = "libcurl"
            def = "src/nativeInterop/cinterop/libcurl.def"

            [[cinterop]]
            name = "openssl"
            def = "src/nativeInterop/cinterop/openssl.def"
            package = "openssl"
        """.trimIndent()

        // When: config is parsed
        val config = assertNotNull(parseConfig(toml).get())

        // Then: both entries are parsed in order
        assertEquals(2, config.cinterop.size)
        assertEquals("libcurl", config.cinterop[0].name)
        assertEquals("openssl", config.cinterop[1].name)
        assertNull(config.cinterop[0].packageName)
        assertEquals("openssl", config.cinterop[1].packageName)
    }

    @Test
    fun parseConfigCinteropWithDependencies() {
        // Given: [[cinterop]] alongside [dependencies]
        val toml = """
            name = "my-app"
            version = "0.1.0"
            kotlin = "2.1.0"
            target = "native"
            main = "com.example.MainKt"
            sources = ["src"]

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

            [[cinterop]]
            name = "libcurl"
            def = "libcurl.def"
        """.trimIndent()

        // When: config is parsed
        val config = assertNotNull(parseConfig(toml).get())

        // Then: both sections are parsed independently
        assertEquals(1, config.dependencies.size)
        assertEquals(1, config.cinterop.size)
    }
}

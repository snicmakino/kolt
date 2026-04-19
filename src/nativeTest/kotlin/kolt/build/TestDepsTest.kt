package kolt.build

import kolt.config.BuildSection
import kolt.config.KoltConfig
import kolt.config.KotlinSection
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class TestDepsTest {

    @Test
    fun jvmTargetInjectsKotlinTestJunit5() {
        val config = testConfig()
        val injected = autoInjectedTestDeps(config)

        assertEquals(
            mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to "2.1.0"),
            injected
        )
    }

    @Test
    fun nativeTargetInjectsNothing() {
        val config = KoltConfig(
            name = "my-app", version = "0.1.0",
            kotlin = KotlinSection(version = "2.1.0"),
            build = BuildSection(target = "linuxX64", main = "main", sources = listOf("src")),
        )
        val injected = autoInjectedTestDeps(config)

        assertEquals(emptyMap(), injected)
    }

    @Test
    fun kotlinVersionMatchesConfig() {
        val config = KoltConfig(
            name = "my-app", version = "0.1.0",
            kotlin = KotlinSection(version = "2.2.0"),
            build = BuildSection(target = "jvm", main = "main", sources = listOf("src")),
        )
        val injected = autoInjectedTestDeps(config)

        assertEquals("2.2.0", injected["org.jetbrains.kotlin:kotlin-test-junit5"])
    }

    @Test
    fun userTestDepOverridesAutoInjected() {
        val config = testConfig(testDependencies = mapOf(
            "org.jetbrains.kotlin:kotlin-test-junit5" to "2.0.0"
        ))
        val allDeps = mergeAllDeps(config)

        assertEquals("2.0.0", allDeps["org.jetbrains.kotlin:kotlin-test-junit5"])
    }

    @Test
    fun mainDepOverridesAutoInjected() {
        val config = testConfig(dependencies = mapOf(
            "org.jetbrains.kotlin:kotlin-test-junit5" to "2.0.0"
        ))
        val allDeps = mergeAllDeps(config)

        assertEquals("2.0.0", allDeps["org.jetbrains.kotlin:kotlin-test-junit5"])
    }

    @Test
    fun testDepOverridesMainDep() {
        val config = testConfig(
            dependencies = mapOf(
                "org.jetbrains.kotlin:kotlin-test-junit5" to "2.0.0"
            ),
            testDependencies = mapOf(
                "org.jetbrains.kotlin:kotlin-test-junit5" to "1.9.0"
            )
        )
        val allDeps = mergeAllDeps(config)

        assertEquals("1.9.0", allDeps["org.jetbrains.kotlin:kotlin-test-junit5"])
    }

    @Test
    fun mergeAllDepsIncludesAutoInjectedAndUserDeps() {
        val config = testConfig(
            dependencies = mapOf("com.example:lib" to "1.0.0"),
            testDependencies = mapOf("io.kotest:kotest-runner-junit5" to "5.8.0")
        )
        val allDeps = mergeAllDeps(config)

        assertEquals(3, allDeps.size)
        assertEquals("2.1.0", allDeps["org.jetbrains.kotlin:kotlin-test-junit5"])
        assertEquals("1.0.0", allDeps["com.example:lib"])
        assertEquals("5.8.0", allDeps["io.kotest:kotest-runner-junit5"])
    }

    @Test
    fun mergeAllDepsNativeTargetHasNoAutoInjected() {
        val config = KoltConfig(
            name = "my-app", version = "0.1.0",
            kotlin = KotlinSection(version = "2.1.0"),
            build = BuildSection(target = "linuxX64", main = "main", sources = listOf("src")),
            dependencies = mapOf("com.example:lib" to "1.0.0"),
        )
        val allDeps = mergeAllDeps(config)

        assertEquals(1, allDeps.size)
        assertEquals("1.0.0", allDeps["com.example:lib"])
    }
}

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

    assertEquals(mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to "2.1.0"), injected)
  }

  @Test
  fun nativeTargetInjectsNothing() {
    val config =
      KoltConfig(
        name = "my-app",
        version = "0.1.0",
        kotlin = KotlinSection(version = "2.1.0"),
        build = BuildSection(target = "linuxX64", main = "main", sources = listOf("src")),
      )
    val injected = autoInjectedTestDeps(config)

    assertEquals(emptyMap(), injected)
  }

  @Test
  fun kotlinVersionMatchesConfig() {
    val config =
      KoltConfig(
        name = "my-app",
        version = "0.1.0",
        kotlin = KotlinSection(version = "2.2.0"),
        build = BuildSection(target = "jvm", main = "main", sources = listOf("src")),
      )
    val injected = autoInjectedTestDeps(config)

    assertEquals("2.2.0", injected["org.jetbrains.kotlin:kotlin-test-junit5"])
  }

  @Test
  fun userTestDepOverridesAutoInjected() {
    // Test seeds are `autoInjectedTestDeps(config) + config.testDependencies`;
    // explicit user test deps win over auto-inject for the same GA.
    val config =
      testConfig(testDependencies = mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to "2.0.0"))
    val testSeeds = autoInjectedTestDeps(config) + config.testDependencies

    assertEquals("2.0.0", testSeeds["org.jetbrains.kotlin:kotlin-test-junit5"])
  }

  @Test
  fun jvmTargetSkipsInjectionWhenTestSourcesAndTestDepsEmpty() {
    val config = testConfig(testSources = emptyList(), testDependencies = emptyMap())
    val injected = autoInjectedTestDeps(config)

    assertEquals(emptyMap(), injected)
  }

  @Test
  fun jvmTargetInjectsWhenOnlyTestDependenciesDeclared() {
    val config =
      testConfig(
        testSources = emptyList(),
        testDependencies = mapOf("io.kotest:kotest-runner-junit5" to "5.8.0"),
      )
    val injected = autoInjectedTestDeps(config)

    assertEquals(mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to "2.1.0"), injected)
  }

  @Test
  fun jvmTargetInjectsWhenOnlyTestSourcesPresent() {
    val config = testConfig(testSources = listOf("test"), testDependencies = emptyMap())
    val injected = autoInjectedTestDeps(config)

    assertEquals(mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to "2.1.0"), injected)
  }

  @Test
  fun nativeTargetSkipsAutoInjectedTestDeps() {
    val config =
      KoltConfig(
        name = "my-app",
        version = "0.1.0",
        kotlin = KotlinSection(version = "2.1.0"),
        build = BuildSection(target = "linuxX64", main = "main", sources = listOf("src")),
        dependencies = mapOf("com.example:lib" to "1.0.0"),
      )
    val injected = autoInjectedTestDeps(config)

    assertEquals(emptyMap(), injected)
  }
}

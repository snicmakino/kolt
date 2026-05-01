package kolt.build

import kolt.config.KoltConfig

internal const val TEST_CLASSES_DIR = "$BUILD_DIR/test-classes"

data class TestBuildCommand(val args: List<String>, val outputPath: String)

fun testBuildCommand(
  config: KoltConfig,
  classesDir: String,
  classpath: String? = null,
  pluginArgs: List<String> = emptyList(),
  kotlincPath: String? = null,
  @Suppress("UNUSED_PARAMETER") profile: Profile = Profile.Debug,
): TestBuildCommand {
  val cp =
    buildList {
        add(classesDir)
        if (!classpath.isNullOrEmpty()) add(classpath)
      }
      .joinToString(":")
  val args = buildList {
    add(kotlincPath ?: "kotlinc")
    add("-cp")
    add(cp)
    addAll(config.build.testSources)
    add("-jvm-target")
    add(config.build.jvmTarget)
    addAll(languageVersionArgs(config))
    addAll(pluginArgs)
    // Match Gradle Kotlin plugin: test source set shares the main module's
    // identity so `internal` symbols are visible across the boundary.
    add("-module-name")
    add(config.name)
    add("-Xfriend-paths=$classesDir")
    add("-d")
    add(TEST_CLASSES_DIR)
  }
  return TestBuildCommand(args = args, outputPath = TEST_CLASSES_DIR)
}

package kolt.build

import kolt.config.KoltConfig
import kolt.config.jvmMainClass

data class RunCommand(val args: List<String>)

// `main` is passed explicitly (not read from `config.build.main`) because
// `BuildSection.main` became nullable in the lib-build-pipeline spec; the
// kind gate and null-check live at the caller per ADR 0001.
fun runCommand(
  config: KoltConfig,
  main: String,
  classpath: String? = null,
  appArgs: List<String> = emptyList(),
  javaPath: String? = null,
  @Suppress("UNUSED_PARAMETER") profile: Profile = Profile.Debug,
  sysProps: List<Pair<String, String>> = emptyList(),
): RunCommand {
  val cp = if (!classpath.isNullOrEmpty()) "$CLASSES_DIR:$classpath" else CLASSES_DIR
  val args = buildList {
    add(javaPath ?: "java")
    for ((k, v) in sysProps) add("-D$k=$v")
    add("-cp")
    add(cp)
    add(jvmMainClass(main))
    addAll(appArgs)
  }
  return RunCommand(args = args)
}

fun nativeRunCommand(
  config: KoltConfig,
  appArgs: List<String> = emptyList(),
  profile: Profile = Profile.Debug,
): RunCommand = RunCommand(args = listOf(outputKexePath(config, profile)) + appArgs)

fun nativeTestRunCommand(
  config: KoltConfig,
  testArgs: List<String> = emptyList(),
  profile: Profile = Profile.Debug,
): RunCommand = RunCommand(args = listOf(outputNativeTestKexePath(config, profile)) + testArgs)

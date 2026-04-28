package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.CinteropConfig
import kolt.config.KoltConfig
import kolt.config.konanTargetGradleName
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.writeFileAsString

internal const val BUILD_DIR = "build"
internal const val CLASSES_DIR = "$BUILD_DIR/classes"

// Native IC cache. Wiped by `kolt clean` (it lives under BUILD_DIR).
// Spike #160: touch/abi-neutral wall-time -30–39% at 25–50 files; cold
// builds also speed up from konanc's IC strategy. Issue #168.
internal fun nativeIcCacheDir(profile: Profile): String = "$BUILD_DIR/${profile.dirName}/.ic-cache"

private fun profileDir(profile: Profile): String = "$BUILD_DIR/${profile.dirName}"

internal fun outputJarPath(config: KoltConfig, profile: Profile = Profile.Debug): String =
  "${profileDir(profile)}/${config.name}.jar"

internal fun outputRuntimeClasspathPath(
  config: KoltConfig,
  profile: Profile = Profile.Debug,
): String = "${profileDir(profile)}/${config.name}-runtime.classpath"

// A resolved JVM dependency jar, kept as a pair of (a) absolute cache path and
// (b) full GAV coordinate for the tiebreak rule in ADR 0027 §1 ("alphabetical
// by file name, tiebreak by group:artifact:version").
internal data class ResolvedJar(val cachePath: String, val groupArtifactVersion: String)

internal sealed class ManifestWriteError {
  data class WriteFailed(val path: String, val reason: String) : ManifestWriteError()
}

// Emits the JVM `kind = "app"` runtime classpath manifest (ADR 0027 §1):
// UTF-8, LF, no trailing newline; alphabetical by file name, tiebreak by
// `group:artifact:version`; self jar excluded as a defence-in-depth guard
// (the resolver's return value already omits it).
internal fun writeRuntimeClasspathManifest(
  config: KoltConfig,
  resolvedJars: List<ResolvedJar>,
  profile: Profile = Profile.Debug,
): Result<Unit, ManifestWriteError> {
  val selfJarPath = outputJarPath(config, profile)
  val sorted =
    resolvedJars
      .filter { it.cachePath != selfJarPath }
      .sortedWith(compareBy({ fileName(it.cachePath) }, { it.groupArtifactVersion }))
  val content = sorted.joinToString("\n") { it.cachePath }
  val path = outputRuntimeClasspathPath(config, profile)
  ensureDirectoryRecursive(profileDir(profile)).getOrElse { error ->
    return Err(ManifestWriteError.WriteFailed(path = error.path, reason = "mkdir failed"))
  }
  writeFileAsString(path, content).getOrElse { error ->
    return Err(ManifestWriteError.WriteFailed(path = error.path, reason = "write failed"))
  }
  return Ok(Unit)
}

private fun fileName(path: String): String {
  val slash = path.lastIndexOf('/')
  return if (slash < 0) path else path.substring(slash + 1)
}

internal fun outputKexePath(config: KoltConfig, profile: Profile = Profile.Debug): String =
  "${profileDir(profile)}/${config.name}.kexe"

internal fun outputNativeTestKexePath(
  config: KoltConfig,
  profile: Profile = Profile.Debug,
): String = "${profileDir(profile)}/${config.name}-test.kexe"

internal fun outputNativeKlibPath(config: KoltConfig, profile: Profile = Profile.Debug): String =
  "${profileDir(profile)}/${config.name}-klib"

internal fun outputNativeTestKlibPath(
  config: KoltConfig,
  profile: Profile = Profile.Debug,
): String = "${profileDir(profile)}/${config.name}-test-klib"

data class BuildCommand(val args: List<String>, val outputPath: String)

// #162: when `[kotlin] compiler` outruns `version`, pin the language/API surface
// to `version` so a 2.3.x daemon can still compile 2.1-language projects.
// Returns an empty list on equal (or unset-compiler) to keep the common case
// flag-free — warning-free on every kotlinc release.
//
// `-language-version` / `-api-version` accept only `major.minor` (`2.1`), not
// `major.minor.patch` (`2.1.0`). kotlinc rejects the patch form with
// `Unknown -api-version value: 2.1.0`, so we truncate.
internal fun languageVersionArgs(config: KoltConfig): List<String> {
  val lang = config.kotlin.version
  if (config.kotlin.effectiveCompiler == lang) return emptyList()
  val surface = majorMinor(lang)
  return listOf("-language-version", surface, "-api-version", surface)
}

internal fun majorMinor(version: String): String = version.split('.').take(2).joinToString(".")

fun checkCommand(
  config: KoltConfig,
  classpath: String? = null,
  pluginArgs: List<String> = emptyList(),
  kotlincPath: String? = null,
): List<String> = buildList {
  add(kotlincPath ?: "kotlinc")
  if (!classpath.isNullOrEmpty()) {
    add("-cp")
    add(classpath)
  }
  addAll(config.build.sources)
  add("-jvm-target")
  add(config.build.jvmTarget)
  addAll(languageVersionArgs(config))
  addAll(pluginArgs)
}

// Two-stage build: konanc silently no-ops compiler plugins on single-step
// `-p program`, so Stage 1 compiles into a klib (plugin applied), Stage 2 links.
internal fun nativeLibraryCommand(
  config: KoltConfig,
  pluginArgs: List<String> = emptyList(),
  konancPath: String? = null,
  klibs: List<String> = emptyList(),
  profile: Profile = Profile.Debug,
): BuildCommand {
  val outputBase = outputNativeKlibPath(config, profile)
  val nativeTarget = konanTargetGradleName(config.build.target)
  val args = buildList {
    add(konancPath ?: "konanc")
    add("-target")
    add(nativeTarget)
    addAll(config.build.sources)
    add("-p")
    add("library")
    add("-nopack")
    for (klib in klibs) {
      add("-l")
      add(klib)
    }
    add("-o")
    add(outputBase)
    addAll(languageVersionArgs(config))
    addAll(pluginArgs)
  }
  return BuildCommand(args = args, outputPath = outputBase)
}

// `main` is passed explicitly (not read from `config.build.main`) because
// `BuildSection.main` became nullable in the lib-build-pipeline spec; the
// kind gate and null-check live at the caller per ADR 0001.
internal fun nativeLinkCommand(
  config: KoltConfig,
  main: String,
  konancPath: String? = null,
  klibs: List<String> = emptyList(),
  profile: Profile = Profile.Debug,
): BuildCommand {
  val outputPath = outputKexePath(config, profile)
  val outputBase = "${profileDir(profile)}/${config.name}"
  val klibPath = outputNativeKlibPath(config, profile)
  val nativeTarget = konanTargetGradleName(config.build.target)
  val args = buildList {
    add(konancPath ?: "konanc")
    add("-target")
    add(nativeTarget)
    add("-p")
    add("program")
    add("-e")
    add(main)
    for (klib in klibs) {
      add("-l")
      add(klib)
    }
    when (profile) {
      Profile.Debug -> add("-g")
      Profile.Release -> add("-opt")
    }
    add("-Xinclude=$klibPath")
    add("-Xenable-incremental-compilation")
    add("-Xic-cache-dir=${nativeIcCacheDir(profile)}")
    add("-o")
    add(outputBase)
  }
  return BuildCommand(args = args, outputPath = outputPath)
}

internal fun nativeTestLibraryCommand(
  config: KoltConfig,
  pluginArgs: List<String> = emptyList(),
  konancPath: String? = null,
  klibs: List<String> = emptyList(),
  profile: Profile = Profile.Debug,
): BuildCommand {
  val outputBase = outputNativeTestKlibPath(config, profile)
  val nativeTarget = konanTargetGradleName(config.build.target)
  val args = buildList {
    add(konancPath ?: "konanc")
    add("-target")
    add(nativeTarget)
    addAll(config.build.sources)
    addAll(config.build.testSources)
    add("-p")
    add("library")
    add("-nopack")
    for (klib in klibs) {
      add("-l")
      add(klib)
    }
    add("-o")
    add(outputBase)
    addAll(languageVersionArgs(config))
    addAll(pluginArgs)
  }
  return BuildCommand(args = args, outputPath = outputBase)
}

internal fun nativeTestLinkCommand(
  config: KoltConfig,
  konancPath: String? = null,
  klibs: List<String> = emptyList(),
  profile: Profile = Profile.Debug,
): BuildCommand {
  val outputPath = outputNativeTestKexePath(config, profile)
  val outputBase = "${profileDir(profile)}/${config.name}-test"
  val klibPath = outputNativeTestKlibPath(config, profile)
  val nativeTarget = konanTargetGradleName(config.build.target)
  val args = buildList {
    add(konancPath ?: "konanc")
    add("-target")
    add(nativeTarget)
    add("-p")
    add("program")
    add("-generate-test-runner")
    for (klib in klibs) {
      add("-l")
      add(klib)
    }
    when (profile) {
      Profile.Debug -> add("-g")
      Profile.Release -> add("-opt")
    }
    add("-Xinclude=$klibPath")
    add("-o")
    add(outputBase)
  }
  return BuildCommand(args = args, outputPath = outputPath)
}

// cinterop appends .klib to the -o path, so outputPath omits the extension.
fun cinteropCommand(
  entry: CinteropConfig,
  target: String,
  cinteropPath: String? = null,
  outputDir: String = BUILD_DIR,
): BuildCommand {
  val outputBase = "$outputDir/${entry.name}"
  val args = buildList {
    add(cinteropPath ?: "cinterop")
    add("-target")
    add(konanTargetGradleName(target))
    add("-def")
    add(entry.def)
    add("-o")
    add(outputBase)
    if (entry.packageName != null) {
      add("-pkg")
      add(entry.packageName)
    }
  }
  return BuildCommand(args = args, outputPath = outputBase)
}

fun cinteropOutputKlibPath(entry: CinteropConfig, outputDir: String = BUILD_DIR): String =
  "$outputDir/${entry.name}.klib"

fun cinteropStampPath(entry: CinteropConfig, outputDir: String = BUILD_DIR): String =
  "$outputDir/${entry.name}.klib.stamp"

// Includes kotlinVersion because klib format is not guaranteed compatible across versions.
fun cinteropStamp(entry: CinteropConfig, defMtime: Long, kotlinVersion: String): String =
  buildString {
    append("kotlinVersion=").append(kotlinVersion).append('\n')
    append("name=").append(entry.name).append('\n')
    append("def=").append(entry.def).append('\n')
    append("defMtime=").append(defMtime).append('\n')
    append("package=").append(entry.packageName ?: "").append('\n')
  }

internal fun jarCommand(
  config: KoltConfig,
  jarPath: String? = null,
  profile: Profile = Profile.Debug,
): BuildCommand {
  val outputPath = outputJarPath(config, profile)
  return BuildCommand(
    args = listOf(jarPath ?: "jar", "cf", outputPath, "-C", CLASSES_DIR, "."),
    outputPath = outputPath,
  )
}

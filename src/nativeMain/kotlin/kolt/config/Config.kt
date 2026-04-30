package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.resolve.compareVersions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

const val MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2"

// Kotlin/Native target identifiers, matching `KonanTarget.<n>.name`. Gradle
// Module Metadata uses snake_case variants of these (`linux_x64` etc.); the
// schema-to-metadata mapping lives inside NativeResolver.
val NATIVE_TARGETS = setOf("linuxX64", "linuxArm64", "macosX64", "macosArm64", "mingwX64")

val VALID_TARGETS = setOf("jvm") + NATIVE_TARGETS

val VALID_KINDS = setOf("app", "lib")

// ADR 0023 §1: `kind = "lib"` forbids `[build] main`.
private const val LIB_WITH_MAIN_ERROR = "main has no meaning for a library; remove it"

// ADR 0023 §1: `kind = "app"` requires `[build] main`.
private const val APP_WITHOUT_MAIN_ERROR = "[build] main is required for kind = \"app\""

// Maps a schema-level KonanTarget identifier (camelCase, e.g. `linuxX64`) to
// the snake_case form used by both konanc CLI (`-target linux_x64`) and the
// `org.jetbrains.kotlin.native.target` attribute in Gradle Module Metadata.
// Precondition: `target in NATIVE_TARGETS`. The regex form is total so it
// never throws; callers are responsible for validating the input first.
fun konanTargetGradleName(target: String): String =
  target.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

sealed class ConfigError {
  data class ParseFailed(val message: String) : ConfigError()
}

@Serializable
data class CinteropConfig(
  val name: String,
  val def: String,
  @SerialName("package") val packageName: String? = null,
)

@Serializable
data class KotlinSection(
  val version: String,
  val compiler: String? = null,
  val plugins: Map<String, Boolean> = emptyMap(),
) {
  // Compiler/daemon/toolchain selection uses this. `version` alone stays the
  // language/API version (lockfile key, -language-version flag). When
  // `compiler` is unset, the two collapse — identical to pre-#162 behavior.
  val effectiveCompiler: String
    get() = compiler ?: version
}

@Serializable
data class BuildSection(
  val target: String,
  @SerialName("jvm_target") val jvmTarget: String = "25",
  val jdk: String? = null,
  val main: String?,
  val sources: List<String>,
  @SerialName("test_sources") val testSources: List<String> = listOf("test"),
  val resources: List<String> = emptyList(),
  @SerialName("test_resources") val testResources: List<String> = emptyList(),
)

// Per-target fields deferred per ADR 0023 §3.
@Serializable private data class BuildTargetEntry(val unused: String? = null)

@Serializable data class FmtSection(val style: String = "google")

@Serializable
data class TestSection(
  @SerialName("sys_props") val sysProps: Map<String, SysPropValue> = emptyMap()
)

@Serializable
data class RunSection(
  @SerialName("sys_props") val sysProps: Map<String, SysPropValue> = emptyMap()
)

@Serializable
data class KoltConfig(
  val name: String,
  val version: String,
  val kind: String = "app",
  val kotlin: KotlinSection,
  val build: BuildSection,
  val fmt: FmtSection = FmtSection(),
  val dependencies: Map<String, String> = emptyMap(),
  @SerialName("test-dependencies") val testDependencies: Map<String, String> = emptyMap(),
  val repositories: Map<String, String> = mapOf("central" to MAVEN_CENTRAL_BASE),
  val cinterop: List<CinteropConfig> = emptyList(),
  // [classpaths.<name>] declares a named, resolvable jar bundle independent
  // of [dependencies]. Bundles feed sysprop values via SysPropValue.ClasspathRef
  // and are persisted into kolt.lock under classpathBundles. The TOML shape is
  // identical to [dependencies] (`"group:artifact" = "version"`), so the value
  // type stays a plain Map<String, String> rather than a wrapper data class.
  val classpaths: Map<String, Map<String, String>> = emptyMap(),
  @SerialName("test") val testSection: TestSection = TestSection(),
  @SerialName("run") val runSection: RunSection = RunSection(),
)

private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

// Two-tier parse: ktoml deserializes into the Raw* classes (target/main/sources
// optional, plus `targets` map for `[build.targets.X]`). parseConfig then
// validates and de-sugars into the public KoltConfig/BuildSection where
// `target` is non-null. Keeping the public schema unchanged lets the 22-odd
// `config.build.target` consumers stay typed as String.
@Serializable
private data class RawBuildSection(
  val target: String? = null,
  @SerialName("jvm_target") val jvmTarget: String = "25",
  val jdk: String? = null,
  val main: String? = null,
  val sources: List<String>,
  @SerialName("test_sources") val testSources: List<String> = listOf("test"),
  val resources: List<String> = emptyList(),
  @SerialName("test_resources") val testResources: List<String> = emptyList(),
  val targets: Map<String, BuildTargetEntry>? = null,
)

@Serializable
private data class RawTestSection(
  @SerialName("sys_props") val sysProps: Map<String, RawSysPropValue> = emptyMap()
)

@Serializable
private data class RawRunSection(
  @SerialName("sys_props") val sysProps: Map<String, RawSysPropValue> = emptyMap()
)

@Serializable
private data class RawKoltConfig(
  val name: String,
  val version: String,
  val kind: String = "app",
  val kotlin: KotlinSection,
  val build: RawBuildSection,
  val fmt: FmtSection = FmtSection(),
  val dependencies: Map<String, String> = emptyMap(),
  @SerialName("test-dependencies") val testDependencies: Map<String, String> = emptyMap(),
  val repositories: Map<String, String> = mapOf("central" to MAVEN_CENTRAL_BASE),
  val cinterop: List<CinteropConfig> = emptyList(),
  val classpaths: Map<String, Map<String, String>> = emptyMap(),
  val test: RawTestSection? = null,
  val run: RawRunSection? = null,
)

private fun validateKind(kind: String): Result<Unit, ConfigError> {
  if (kind !in VALID_KINDS) {
    return Err(
      ConfigError.ParseFailed(
        "invalid kind '$kind' (valid kinds: ${VALID_KINDS.joinToString(", ")})"
      )
    )
  }
  return Ok(Unit)
}

private fun validateTarget(target: String): Result<Unit, ConfigError> {
  if (target !in VALID_TARGETS) {
    return Err(
      ConfigError.ParseFailed(
        "invalid target '$target' (valid targets: ${VALID_TARGETS.joinToString(", ")})"
      )
    )
  }
  return Ok(Unit)
}

// Lifts a Map<String, RawSysPropValue> (decoded with all-nullable fields) into
// the typed Map<String, SysPropValue>, attaching offending-key context to any
// shape error. Req 2.1 / 2.2: uniform inline-table value with exactly one of
// { literal, classpath, project_dir } set; otherwise reject naming the key.
private fun liftSysPropsMap(
  raw: Map<String, RawSysPropValue>,
  sectionLabel: String,
): Result<Map<String, SysPropValue>, ConfigError> {
  val out = LinkedHashMap<String, SysPropValue>(raw.size)
  for ((rawKey, rawValue) in raw) {
    val key = rawKey.removeSurrounding("\"")
    val setFields =
      listOfNotNull(
        rawValue.literal?.let { "literal" },
        rawValue.classpath?.let { "classpath" },
        rawValue.projectDir?.let { "project_dir" },
      )
    if (setFields.size != 1) {
      return Err(
        ConfigError.ParseFailed(
          "$sectionLabel \"$key\": value must set exactly one of " +
            "{ literal, classpath, project_dir }, but ${setFields.size} were set: $setFields"
        )
      )
    }
    out[key] =
      when (setFields.single()) {
        "literal" -> SysPropValue.Literal(rawValue.literal!!)
        "classpath" -> SysPropValue.ClasspathRef(rawValue.classpath!!)
        "project_dir" -> SysPropValue.ProjectDir(rawValue.projectDir!!)
        else -> error("unreachable")
      }
  }
  return Ok(out)
}

// Validates a project-root-relative path string. Rejects: empty string,
// absolute paths (leading "/"), and any sequence of segments that resolves
// outside the project root via ".." (Req 2.4 / 2.5). Accepts ".", trailing
// slashes, and non-existent directories (existence is consumer responsibility,
// see design.md SysPropResolver corner-case table).
internal fun validateProjectRelativePath(rel: String): Result<Unit, ConfigError> {
  if (rel.isEmpty()) {
    return Err(ConfigError.ParseFailed("project_dir must not be empty"))
  }
  if (rel.startsWith("/")) {
    return Err(
      ConfigError.ParseFailed(
        "project_dir must be a project-root-relative path, got absolute path '$rel'"
      )
    )
  }
  val segments = rel.split("/").filter { it.isNotEmpty() }
  var depth = 0
  for (seg in segments) {
    when (seg) {
      "." -> Unit // same directory, no depth change
      ".." -> {
        depth -= 1
        if (depth < 0) {
          return Err(
            ConfigError.ParseFailed(
              "project_dir must stay within the project root, got escaping path '$rel'"
            )
          )
        }
      }
      else -> depth += 1
    }
  }
  return Ok(Unit)
}

// Native targets and library kinds reject the JVM-only schema additions
// loudly rather than silently ignoring them, so configurations that have
// no effect never ship (Req 5.1, 5.2). Library kinds may still declare
// [test.sys_props] and [classpaths.X] (Req 5.3, 5.4) — only [run.sys_props]
// is meaningless there.
private fun validateNewSchemaTargetCompat(
  target: String,
  kind: String,
  classpaths: Map<String, Map<String, String>>,
  testSysProps: Map<String, SysPropValue>,
  runSysProps: Map<String, SysPropValue>,
): Result<Unit, ConfigError> {
  if (target in NATIVE_TARGETS) {
    if (classpaths.isNotEmpty()) {
      return Err(
        ConfigError.ParseFailed(
          "[classpaths.*] is JVM-only and cannot be used with native target '$target'"
        )
      )
    }
    if (testSysProps.isNotEmpty()) {
      return Err(
        ConfigError.ParseFailed(
          "[test.sys_props] is JVM-only and cannot be used with native target '$target'"
        )
      )
    }
    if (runSysProps.isNotEmpty()) {
      return Err(
        ConfigError.ParseFailed(
          "[run.sys_props] is JVM-only and cannot be used with native target '$target'"
        )
      )
    }
  }
  if (kind == "lib" && runSysProps.isNotEmpty()) {
    return Err(
      ConfigError.ParseFailed(
        "[run.sys_props] has no effect for kind = \"lib\" (libraries cannot be run); remove it"
      )
    )
  }
  return Ok(Unit)
}

// Every SysPropValue.ClasspathRef must point at a declared bundle. The
// offending sysprop key and the missing bundle name are both surfaced
// (Req 2.3). Both [test.sys_props] and [run.sys_props] are checked against
// the same single [classpaths] declaration scope.
private fun validateBundleReferences(
  testSysProps: Map<String, SysPropValue>,
  runSysProps: Map<String, SysPropValue>,
  classpaths: Map<String, Map<String, String>>,
): Result<Unit, ConfigError> {
  for ((key, value) in testSysProps) {
    if (value is SysPropValue.ClasspathRef && value.bundleName !in classpaths) {
      return Err(
        ConfigError.ParseFailed(
          "[test.sys_props] \"$key\": references undeclared classpath bundle '${value.bundleName}'"
        )
      )
    }
  }
  for ((key, value) in runSysProps) {
    if (value is SysPropValue.ClasspathRef && value.bundleName !in classpaths) {
      return Err(
        ConfigError.ParseFailed(
          "[run.sys_props] \"$key\": references undeclared classpath bundle '${value.bundleName}'"
        )
      )
    }
  }
  return Ok(Unit)
}

// Walks all SysPropValue.ProjectDir entries in [test.sys_props] and
// [run.sys_props] and applies validateProjectRelativePath, attaching the
// offending sysprop key to the error message (Req 2.4 / 2.5).
private fun validateSysPropsProjectDirs(
  testSysProps: Map<String, SysPropValue>,
  runSysProps: Map<String, SysPropValue>,
): Result<Unit, ConfigError> {
  for ((key, value) in testSysProps) {
    if (value is SysPropValue.ProjectDir) {
      val err = validateProjectRelativePath(value.relativePath).getError()
      if (err is ConfigError.ParseFailed) {
        return Err(ConfigError.ParseFailed("[test.sys_props] \"$key\": ${err.message}"))
      }
    }
  }
  for ((key, value) in runSysProps) {
    if (value is SysPropValue.ProjectDir) {
      val err = validateProjectRelativePath(value.relativePath).getError()
      if (err is ConfigError.ParseFailed) {
        return Err(ConfigError.ParseFailed("[run.sys_props] \"$key\": ${err.message}"))
      }
    }
  }
  return Ok(Unit)
}

// ADR 0023 §3: scalar `[build] target = "X"` and `[build.targets.X]` are
// mutually exclusive. Multi-target form is reserved — exactly one entry
// is de-sugared into the scalar form, two or more are rejected.
private fun resolveEffectiveTarget(raw: RawBuildSection): Result<String, ConfigError> {
  val scalar = raw.target
  val tables = raw.targets?.mapKeys { (key, _) -> key.removeSurrounding("\"") }

  if (scalar != null && !tables.isNullOrEmpty()) {
    return Err(
      ConfigError.ParseFailed(
        "[build] target = \"...\" and [build.targets.X] cannot both be specified"
      )
    )
  }
  if (!tables.isNullOrEmpty()) {
    if (tables.size > 1) {
      return Err(
        ConfigError.ParseFailed(
          "multi-target builds are not yet implemented " +
            "(found [build.targets]: ${tables.keys.joinToString(", ")}). " +
            "Pick one target until multi-target ships."
        )
      )
    }
    return Ok(tables.keys.first())
  }
  if (scalar == null) {
    return Err(ConfigError.ParseFailed("[build] target is required"))
  }
  return Ok(scalar)
}

fun parseConfig(tomlString: String): Result<KoltConfig, ConfigError> {
  return try {
    val raw = toml.decodeFromString(RawKoltConfig.serializer(), tomlString)
    // Validation order is load-bearing: tests match on canonical error
    // substrings per design.md §Components → Config parser kind+main rule.
    validateKind(raw.kind).getError()?.let {
      return Err(it)
    }
    if (raw.kind == "lib" && raw.build.main != null) {
      return Err(ConfigError.ParseFailed(LIB_WITH_MAIN_ERROR))
    }
    if (raw.kind == "app" && raw.build.main == null) {
      return Err(ConfigError.ParseFailed(APP_WITHOUT_MAIN_ERROR))
    }
    raw.build.main?.let { main ->
      validateMainFqn(main).getError()?.let {
        return Err(it)
      }
    }
    val effectiveTarget =
      resolveEffectiveTarget(raw.build).getOrElse {
        return Err(it)
      }
    validateTarget(effectiveTarget).getError()?.let {
      return Err(it)
    }
    raw.kotlin.compiler?.let { compiler ->
      if (compareVersions(compiler, raw.kotlin.version) < 0) {
        return Err(
          ConfigError.ParseFailed(
            "[kotlin] compiler '$compiler' is lower than version '${raw.kotlin.version}' " +
              "(a compiler cannot target a newer language than itself)"
          )
        )
      }
    }
    // ktoml preserves quotes in map keys; strip them.
    val cleanedDeps = raw.dependencies.mapKeys { (key, _) -> key.removeSurrounding("\"") }
    val cleanedTestDeps = raw.testDependencies.mapKeys { (key, _) -> key.removeSurrounding("\"") }
    val cleanedRepos =
      raw.repositories
        .mapKeys { (key, _) -> key.removeSurrounding("\"") }
        .mapValues { (_, url) -> url.trimEnd('/') }
    val cleanedClasspaths =
      raw.classpaths
        .mapKeys { (key, _) -> key.removeSurrounding("\"") }
        .mapValues { (_, bundle) -> bundle.mapKeys { (k, _) -> k.removeSurrounding("\"") } }
    val testSysProps =
      liftSysPropsMap(raw.test?.sysProps ?: emptyMap(), "[test.sys_props]").getOrElse {
        return Err(it)
      }
    val runSysProps =
      liftSysPropsMap(raw.run?.sysProps ?: emptyMap(), "[run.sys_props]").getOrElse {
        return Err(it)
      }
    validateNewSchemaTargetCompat(
        target = effectiveTarget,
        kind = raw.kind,
        classpaths = cleanedClasspaths,
        testSysProps = testSysProps,
        runSysProps = runSysProps,
      )
      .getError()
      ?.let {
        return Err(it)
      }
    validateBundleReferences(testSysProps, runSysProps, cleanedClasspaths).getError()?.let {
      return Err(it)
    }
    validateSysPropsProjectDirs(testSysProps, runSysProps).getError()?.let {
      return Err(it)
    }
    Ok(
      KoltConfig(
        name = raw.name,
        version = raw.version,
        kind = raw.kind,
        kotlin = raw.kotlin,
        build =
          BuildSection(
            target = effectiveTarget,
            jvmTarget = raw.build.jvmTarget,
            jdk = raw.build.jdk,
            main = raw.build.main,
            sources = raw.build.sources,
            testSources = raw.build.testSources,
            resources = raw.build.resources,
            testResources = raw.build.testResources,
          ),
        fmt = raw.fmt,
        dependencies = cleanedDeps,
        testDependencies = cleanedTestDeps,
        repositories = cleanedRepos,
        cinterop = raw.cinterop,
        classpaths = cleanedClasspaths,
        testSection = TestSection(testSysProps),
        runSection = RunSection(runSysProps),
      )
    )
  } catch (e: SerializationException) {
    Err(ConfigError.ParseFailed("failed to parse kolt.toml: ${e.message}"))
  } catch (e: IllegalArgumentException) {
    Err(ConfigError.ParseFailed("failed to parse kolt.toml: ${e.message}"))
  }
}

internal fun KoltConfig.isLibrary(): Boolean = kind == "lib"

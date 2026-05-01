package kolt.build

import com.github.michaelbull.result.getOrElse
import kolt.infra.currentWorkingDirectory
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class DriftGuardsTest {

  @Test
  fun daemonKotlinVersionPinsAreInSync() {
    val root = projectRoot()
    val pins =
      listOf(
        Pin(
          "src/nativeMain/.../BundledKotlinVersion.kt `BUNDLED_DAEMON_KOTLIN_VERSION`",
          "$root/src/nativeMain/kotlin/kolt/cli/BundledKotlinVersion.kt",
          Regex("""const\s+val\s+BUNDLED_DAEMON_KOTLIN_VERSION\s*:\s*String\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-jvm-compiler-daemon Main.kt `KOLT_DAEMON_KOTLIN_VERSION`",
          "$root/kolt-jvm-compiler-daemon/src/main/kotlin/kolt/daemon/Main.kt",
          Regex("""const\s+val\s+KOLT_DAEMON_KOTLIN_VERSION\s*:\s*String\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-native-compiler-daemon Main.kt `KOLT_NATIVE_DAEMON_KOTLIN_VERSION`",
          "$root/kolt-native-compiler-daemon/src/main/kotlin/kolt/nativedaemon/Main.kt",
          Regex("""const\s+val\s+KOLT_NATIVE_DAEMON_KOTLIN_VERSION\s*:\s*String\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-jvm-compiler-daemon/kolt.toml `[kotlin] version`",
          "$root/kolt-jvm-compiler-daemon/kolt.toml",
          Regex("""(?ms)^\[kotlin\][^\[]*?^version\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-native-compiler-daemon/kolt.toml `[kotlin] version`",
          "$root/kolt-native-compiler-daemon/kolt.toml",
          Regex("""(?ms)^\[kotlin\][^\[]*?^version\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-jvm-compiler-daemon/kolt.toml `kotlin-build-tools-api`",
          "$root/kolt-jvm-compiler-daemon/kolt.toml",
          Regex(""""org\.jetbrains\.kotlin:kotlin-build-tools-api"\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-jvm-compiler-daemon/kolt.toml `kotlin-build-tools-impl`",
          "$root/kolt-jvm-compiler-daemon/kolt.toml",
          Regex(""""org\.jetbrains\.kotlin:kotlin-build-tools-impl"\s*=\s*"([^"]+)""""),
        ),
      )
    assertAllAgree("daemon Kotlin version", pins)
  }

  @Test
  fun daemonMainClassPinsAreInSync() {
    val root = projectRoot()
    assertAllAgree(
      "kolt-jvm-compiler-daemon main class",
      mainClassPins(
        root = root,
        daemonName = "kolt-jvm-compiler-daemon",
        resolverPath = "$root/src/nativeMain/kotlin/kolt/build/daemon/DaemonJarResolver.kt",
        resolverConst = "DAEMON_MAIN_CLASS",
        tomlPath = "$root/kolt-jvm-compiler-daemon/kolt.toml",
      ),
    )
    assertAllAgree(
      "kolt-native-compiler-daemon main class",
      mainClassPins(
        root = root,
        daemonName = "kolt-native-compiler-daemon",
        resolverPath =
          "$root/src/nativeMain/kotlin/kolt/build/nativedaemon/NativeDaemonJarResolver.kt",
        resolverConst = "NATIVE_DAEMON_MAIN_CLASS",
        tomlPath = "$root/kolt-native-compiler-daemon/kolt.toml",
      ),
    )
  }

  // The Profile.dirName ↔ assemble-dist.sh `kolt build --release`
  // triangle: a rename of either `dirName` literal in Profile.kt or a
  // removal of the `--release` flag from the dist script would silently
  // break tarball assembly (root binary at `build/release/kolt.kexe`
  // would not exist after the build). This test pins both ends.
  @Test
  fun profileLiteralsAndDistScriptAreInSync() {
    val root = projectRoot()
    val script = "$root/scripts/assemble-dist.sh"
    val text = readFileAsString(script).getOrElse { fail("missing $script") }

    assertEquals("debug", Profile.Debug.dirName)
    assertEquals("release", Profile.Release.dirName)

    if (!text.contains("\"\$KOLT\" build --release")) {
      fail(
        "scripts/assemble-dist.sh must invoke \"\$KOLT\" build --release; " +
          "Profile.Release.dirName=\"${Profile.Release.dirName}\" expects the dist " +
          "script to opt into the release profile."
      )
    }
    val expectedRootKexe = "build/${Profile.Release.dirName}/kolt.kexe"
    if (!text.contains(expectedRootKexe)) {
      fail(
        "scripts/assemble-dist.sh must reference the release-profile root binary " +
          "at \"$expectedRootKexe\"; renaming Profile.Release.dirName requires " +
          "updating the dist script in lockstep."
      )
    }
  }

  // The triangle BOOTSTRAP_JDK_VERSION <-> daemon `kolt.toml`
  // (`jdk`/`jvm_target`) <-> `jvmToolchain(N)` is the new surface. Drift
  // shows up as `UnsupportedClassVersionError` on daemon spawn (when
  // daemon `jvm_target` > BOOTSTRAP) or as a wasted JDK install in CI
  // when only the major mismatches.
  @Test
  fun bootstrapJdkPinsAreInSync() {
    val root = projectRoot()
    val pins =
      listOf(
        Pin(
          "src/nativeMain/.../BootstrapJdk.kt `BOOTSTRAP_JDK_VERSION`",
          "$root/src/nativeMain/kotlin/kolt/build/daemon/BootstrapJdk.kt",
          Regex("""const\s+val\s+BOOTSTRAP_JDK_VERSION\s*:\s*String\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-jvm-compiler-daemon/kolt.toml `[build].jdk`",
          "$root/kolt-jvm-compiler-daemon/kolt.toml",
          Regex("""(?m)^jdk\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-jvm-compiler-daemon/kolt.toml `[build].jvm_target`",
          "$root/kolt-jvm-compiler-daemon/kolt.toml",
          Regex("""(?m)^jvm_target\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-native-compiler-daemon/kolt.toml `[build].jdk`",
          "$root/kolt-native-compiler-daemon/kolt.toml",
          Regex("""(?m)^jdk\s*=\s*"([^"]+)""""),
        ),
        Pin(
          "kolt-native-compiler-daemon/kolt.toml `[build].jvm_target`",
          "$root/kolt-native-compiler-daemon/kolt.toml",
          Regex("""(?m)^jvm_target\s*=\s*"([^"]+)""""),
        ),
      )
    assertAllAgree("bootstrap JDK", pins)
  }

  private data class Pin(
    val label: String,
    val path: String,
    val regex: Regex,
    val transform: (String) -> String = { it },
  )

  private data class Extracted(val label: String, val value: String)

  private fun assertAllAgree(subject: String, pins: List<Pin>) {
    val extracted =
      pins.map { p ->
        val text =
          readFileAsString(p.path).getOrElse { fail("$subject: missing source file ${p.path}") }
        val match =
          p.regex.find(text)
            ?: fail("$subject: regex did not match in ${p.path} (label=${p.label})")
        Extracted(p.label, p.transform(match.groupValues[1]))
      }
    val distinct = extracted.map { it.value }.toSet()
    if (distinct.size > 1) {
      val msg = buildString {
        appendLine("$subject pins drift:")
        for (e in extracted) appendLine("  - ${e.label}: \"${e.value}\"")
        append(
          "Update all sites together so they share a single value, " + "then rerun `kolt test`."
        )
      }
      fail(msg)
    }
  }

  // Daemon main class FQN lives in three places per daemon: the resolver
  // const (FQN literal), `kolt.toml [build].main` (lower-case function
  // form, transformed below), and `scripts/assemble-dist.sh`'s DAEMONS
  // map (`<name>:<FQN>`). The transform mirrors `kolt.config.jvmMainClass`
  // — keep both in sync if either changes.
  private fun mainClassPins(
    root: String,
    daemonName: String,
    resolverPath: String,
    resolverConst: String,
    tomlPath: String,
  ): List<Pin> {
    val distScript = "$root/scripts/assemble-dist.sh"
    return listOf(
      Pin(
        "${nameOnly(resolverPath)} `$resolverConst`",
        resolverPath,
        Regex("""const\s+val\s+${Regex.escape(resolverConst)}\s*=\s*"([^"]+)""""),
      ),
      Pin(
        "$daemonName/kolt.toml `[build].main`",
        tomlPath,
        Regex("""(?m)^main\s*=\s*"([^"]+)""""),
        transform = ::jvmMainClass,
      ),
      Pin(
        "scripts/assemble-dist.sh DAEMONS[$daemonName]",
        distScript,
        Regex(""""${Regex.escape(daemonName)}:([^"]+)""""),
      ),
    )
  }

  private fun jvmMainClass(main: String): String {
    val prefix = main.substringBeforeLast("main")
    return "${prefix}MainKt"
  }

  private fun nameOnly(path: String): String = path.substringAfterLast('/')

  // Walk up from cwd until we hit `.git` (project root). `kolt test`
  // launches from the project root so the loop normally terminates on
  // the first iteration; the walk is defensive against future runners
  // that might `cd` into a subdirectory.
  private fun projectRoot(): String {
    var current = currentWorkingDirectory() ?: fail("could not get cwd for project-root lookup")
    while (current.isNotEmpty() && current != "/") {
      if (fileExists("$current/.git")) return current
      val cut = current.lastIndexOf('/')
      if (cut <= 0) break
      current = current.substring(0, cut)
    }
    fail("could not locate project root (no .git ancestor) starting from cwd")
  }
}

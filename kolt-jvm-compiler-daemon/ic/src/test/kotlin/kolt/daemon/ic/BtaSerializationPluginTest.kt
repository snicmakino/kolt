@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.getOrElse
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// ADR 0019 Follow-ups + B-2c: resolves spike #104 O.Q. 6 residual risk.
// Drives BtaIncrementalCompiler end-to-end through a **real** compiler
// plugin (`kotlinx.serialization`) so the adapter's plugin jar delivery,
// PluginTranslator wiring, and BTA's plugin loading have all been
// exercised on the happy path before B-2 is declared done.
//
// The test asserts two structural facts:
//   1. A `@Serializable` data class compiles without error.
//   2. The kotlinx.serialization compiler plugin ran during the compile
//      — evidenced by the generated `$Companion` / `$serializer` symbol
//      inside the produced .class file, which the plugin synthesises
//      and is never present in plain kotlinc output.
//
// The second assertion is the load-bearing one: without it, a regression
// that silently skipped plugin attachment (e.g. the pluginJarResolver
// returning empty, or PluginTranslator's alias map losing the
// `serialization` entry) could still pass the compile because
// `@Serializable` is a harmless annotation on its own. The serializer
// symbol only exists if the plugin actually transformed the AST.
class BtaSerializationPluginTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val serializationPluginJars: List<Path> =
    systemClasspath("kolt.ic.serializationPluginClasspath")
  private val serializationFixtureClasspath: List<Path> =
    systemClasspath("kolt.ic.serializationRuntimeClasspath")

  @Test
  fun `Serializable data class compiles and the plugin generated serializer symbol`() {
    val workRoot = Files.createTempDirectory("bta-serialization-")
    val sourceFile =
      workRoot.resolve("Payload.kt").also {
        it.writeText(
          """
                package fixture

                import kotlinx.serialization.Serializable

                @Serializable
                data class Payload(val name: String, val count: Int)
                """
            .trimIndent()
        )
      }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val projectStateDir = workRoot.resolve("ic").apply { createDirectories() }
    val workingDir = projectStateDir.resolve("main").apply { createDirectories() }

    val resolverCalls = mutableListOf<String>()
    val compiler =
      BtaIncrementalCompiler.create(
          btaImplJars = btaImplJars,
          pluginJarResolver = { alias ->
            resolverCalls.add(alias)
            if (alias == "serialization") serializationPluginJars else emptyList()
          },
        )
        .getOrElse { fail("failed to load BTA toolchain: $it") }

    // kolt.toml enables the serialization plugin so PluginTranslator
    // picks it up on its normal reading path — the test is not
    // bypassing the translator, it is going through it.
    workRoot
      .resolve("kolt.toml")
      .writeText(
        """
            name = "demo"
            version = "0.1.0"

            [kotlin]
            version = "2.3.20"

            [kotlin.plugins]
            serialization = true

            [build]
            target = "jvm"
            main = "fixture.Payload"
            sources = ["."]
            """
          .trimIndent()
      )

    compiler
      .compile(
        IcRequest(
          projectId = "serialization-smoke",
          projectRoot = workRoot,
          sources = listOf(sourceFile),
          classpath = serializationFixtureClasspath,
          outputDir = outputDir,
          workingDir = workingDir,
        )
      )
      .getOrElse { fail("expected successful compile of @Serializable fixture, got: $it") }

    assertTrue(
      resolverCalls.contains("serialization"),
      "PluginTranslator must have consulted the resolver for `serialization`, got: $resolverCalls",
    )

    val classFiles = outputDir.walk().filter { it.extension == "class" }.toList()
    assertTrue(
      classFiles.any { it.fileName.toString() == "Payload.class" },
      "expected fixture.Payload.class in output, got: $classFiles",
    )

    // The load-bearing signal that the plugin ran is the nested
    // `${'$'}serializer` class — a singleton that implements
    // `KSerializer<Payload>` and is synthesised only by the
    // serialization compiler plugin. Plain kotlinc output of a
    // `@Serializable data class` contains no such entry. The file
    // name is `Payload${'$'}${'$'}serializer.class` because the
    // first `${'$'}` is the Kotlin-to-JVM nested class separator
    // and the second is the literal name. A future regression that
    // silently skipped plugin attachment would leave this class
    // absent — this assertion is the guard.
    val serializerName = "Payload\$\$serializer.class"
    val classFileNames = classFiles.map { it.fileName.toString() }
    assertTrue(
      serializerName in classFileNames,
      "kotlinx.serialization plugin must have generated `$serializerName`; " +
        "classFiles=$classFileNames",
    )
  }

  @Test
  fun `serialization plugin jars resolve from the classpath system property`() {
    // Guard the kolt.toml wiring: if a future edit drops the
    // `[classpaths.serialization_plugin]` bundle or misroutes the
    // system property, we want the failure signal here rather than
    // buried inside the compile test above.
    assertTrue(
      serializationPluginJars.isNotEmpty(),
      "serialization compiler plugin classpath must be non-empty",
    )
    assertTrue(
      serializationPluginJars.any { it.fileName.toString().contains("serialization") },
      "expected a serialization-compiler-plugin jar on the resolved classpath, got: $serializationPluginJars",
    )
  }

  @Test
  fun `serialization runtime classpath contains kotlinx-serialization-core`() {
    assertTrue(
      serializationFixtureClasspath.any {
        it.fileName.toString().contains("kotlinx-serialization-core")
      },
      "expected kotlinx-serialization-core on the fixture runtime classpath, " +
        "got: $serializationFixtureClasspath",
    )
  }

  // #162 single-batch invariant guard. When both PluginTranslator and
  // LanguageVersionTranslator return non-empty lists, BtaIncrementalCompiler
  // must pass their concatenation to `applyArgumentStrings` in a *single*
  // call — two sequential calls would reset the earlier batch's args back
  // to parser defaults (see BtaIncrementalCompiler.kt header comment on
  // ordering). A regression that splits the call into two would wipe the
  // plugin freeArgs, silently producing non-serializable output. This test
  // catches that: with compiler=2.3.20 / version=2.1.0 the language-version
  // translator emits two arg pairs, and if plugin args lost the ordering
  // race the `$$serializer.class` synthesis would disappear.
  @Test
  fun `plugin and language-version freeArgs coexist in a single applyArgumentStrings batch`() {
    val workRoot = Files.createTempDirectory("bta-freeargs-batch-")
    val sourceFile =
      workRoot.resolve("Payload.kt").also {
        it.writeText(
          """
                package fixture

                import kotlinx.serialization.Serializable

                @Serializable
                data class Payload(val name: String, val count: Int)
                """
            .trimIndent()
        )
      }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val projectStateDir = workRoot.resolve("ic").apply { createDirectories() }
    val workingDir = projectStateDir.resolve("main").apply { createDirectories() }

    val compiler =
      BtaIncrementalCompiler.create(
          btaImplJars = btaImplJars,
          pluginJarResolver = { alias ->
            if (alias == "serialization") serializationPluginJars else emptyList()
          },
        )
        .getOrElse { fail("failed to load BTA toolchain: $it") }

    // version=2.1.0 with compiler=2.3.20 makes LanguageVersionTranslator
    // emit `-language-version 2.1.0 -api-version 2.1.0`; plugin translator
    // emits `-Xplugin=<serialization jar>`. The batch invariant is that
    // both sets survive into the compile.
    workRoot
      .resolve("kolt.toml")
      .writeText(
        """
            name = "demo"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"
            compiler = "2.3.20"

            [kotlin.plugins]
            serialization = true

            [build]
            target = "jvm"
            main = "fixture.Payload"
            sources = ["."]
            """
          .trimIndent()
      )

    compiler
      .compile(
        IcRequest(
          projectId = "freeargs-batch",
          projectRoot = workRoot,
          sources = listOf(sourceFile),
          classpath = serializationFixtureClasspath,
          outputDir = outputDir,
          workingDir = workingDir,
        )
      )
      .getOrElse {
        fail("expected successful compile with plugin + language-version flags, got: $it")
      }

    val classFileNames =
      outputDir.walk().filter { it.extension == "class" }.map { it.fileName.toString() }.toList()
    val serializerName = "Payload\$\$serializer.class"
    assertTrue(
      serializerName in classFileNames,
      "plugin freeArgs must survive alongside language-version freeArgs in one batch; " +
        "missing `$serializerName` signals a reset; classFiles=$classFileNames",
    )
  }

  private fun systemClasspath(key: String): List<Path> {
    val raw =
      System.getProperty(key)
        ?: error(
          "$key system property not set — check kolt-jvm-compiler-daemon/kolt.toml [test.sys_props]"
        )
    return raw.split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
  }
}

package kolt.nativedaemon.compiler

import com.github.michaelbull.result.getError
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

// Production setup path is best validated end-to-end with a real konanc jar,
// which is deferred to PR 6's integration test (needs KONAN_HOME / a staged
// kotlin-native-compiler-embeddable.jar). Here we pin the setup *error*
// shapes so the daemon can distinguish "jar missing on disk" from "jar is
// not the konanc jar" and surface each to the native client with a
// diagnosable message.
class ReflectiveK2NativeCompilerTest {

  @Test
  fun `create returns KonancJarNotFound when the path does not exist`(@TempDir tmp: Path) {
    val missing = tmp.resolve("absent-konanc.jar")

    val err = ReflectiveK2NativeCompiler.create(missing).getError()

    val e = assertNotNull(err)
    assertIs<SetupError.KonancJarNotFound>(e)
  }

  @Test
  fun `create returns K2NativeClassNotFound when the jar lacks K2Native`(@TempDir tmp: Path) {
    // An empty jar (valid zip with only a MANIFEST.MF) resolves into a
    // URLClassLoader fine but has no `org.jetbrains.kotlin.cli.bc.K2Native`,
    // so the reflective Class.forName fails. This pins the "wrong jar"
    // diagnostic as distinct from "jar missing".
    val emptyJar = tmp.resolve("empty.jar")
    writeEmptyJar(emptyJar)

    val err = ReflectiveK2NativeCompiler.create(emptyJar).getError()

    val e = assertNotNull(err)
    assertIs<SetupError.K2NativeClassNotFound>(e)
  }

  @Test
  fun `create returns KonancJarUnreadable when the path is a directory`(@TempDir tmp: Path) {
    // A directory passed as `--konanc-jar` is a plausible operator
    // mistake; we reject it explicitly rather than letting URLClassLoader
    // produce a confusing lazy failure downstream.
    val dir = Files.createDirectory(tmp.resolve("not-a-jar-dir"))

    val err = ReflectiveK2NativeCompiler.create(dir).getError()

    val e = assertNotNull(err)
    assertIs<SetupError.KonancJarUnreadable>(e)
  }

  private fun writeEmptyJar(path: Path) {
    val manifest = Manifest().also { it.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
    Files.newOutputStream(path).use { out ->
      JarOutputStream(out, manifest).use { /* close writes the manifest + EOCD */ }
    }
  }
}

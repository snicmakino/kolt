package kolt.resolve

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Unit coverage for `makeNativeChildLookup`'s `NativeResolved` variant dispatch.
 *
 * Klib variants forward `artifact.dependencies` (minus konanc-bundled stdlib*) and JvmOnly variants
 * have no Gradle Module Metadata, so they are terminal: descending past them would re-attempt the
 * same `.module` fetch that already 404'd. Both variants share the `processed` cache keyed by
 * `GA:version`, which is transparent to the resolution kernel.
 */
class MakeNativeChildLookupTest {

  // A ResolverDeps that asserts no I/O happens. The lookup must hit the
  // pre-populated `processed` cache without re-fetching, so any call here is
  // a regression.
  private val noIoDeps =
    object : ResolverDeps {
      override fun fileExists(path: String): Boolean {
        fail("unexpected fileExists call: $path")
      }

      override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> {
        fail("unexpected ensureDirectoryRecursive call: $path")
      }

      override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
        fail("unexpected downloadFile call: $url -> $destPath")
      }

      override fun computeSha256(filePath: String): Result<String, Sha256Error> {
        fail("unexpected computeSha256 call: $filePath")
      }

      override fun readFileContent(path: String): Result<String, OpenFailed> {
        fail("unexpected readFileContent call: $path")
      }
    }

  @Test
  fun jvmOnlyNodeReturnsEmptyChildren() {
    val coord = Coordinate("com.example", "fake-jvm-only", "1.0.0")
    val processed =
      mutableMapOf<String, NativeResolved>(
        "com.example:fake-jvm-only:1.0.0" to NativeResolved.JvmOnly(coord)
      )

    val lookup =
      makeNativeChildLookup(
        processed = processed,
        nativeTarget = "linux_x64",
        cacheBase = "/cache",
        repos = listOf("https://repo1.example/"),
        deps = noIoDeps,
      )

    val children =
      lookup("com.example:fake-jvm-only", "1.0.0").get()
        ?: fail("expected Ok with empty children, got Err")
    assertEquals(emptyList<Child>(), children)
  }

  @Test
  fun klibNodeReturnsArtifactDependencies() {
    val coord = Coordinate("com.example", "lib", "1.0.0")
    val redirect = NativeRedirect("com.example", "lib-linuxx64", "1.0.0")
    val artifact =
      NativeArtifact(
        klibFileUrl = "lib-linuxx64-1.0.0.klib",
        klibSha256 = "h",
        dependencies =
          listOf(
            NativeDependency(
              group = "com.example",
              module = "trans",
              version = "2.0.0",
              strict = true,
              rejects = listOf("3.0.0"),
            )
          ),
      )
    val processed =
      mutableMapOf<String, NativeResolved>(
        "${coord.group}:${coord.artifact}:${coord.version}" to
          NativeResolved.Klib(redirect, artifact)
      )

    val lookup =
      makeNativeChildLookup(
        processed = processed,
        nativeTarget = "linux_x64",
        cacheBase = "/cache",
        repos = listOf("https://repo1.example/"),
        deps = noIoDeps,
      )

    val children = assertNotNull(lookup("com.example:lib", "1.0.0").get())
    assertEquals(1, children.size)
    val child = children.single()
    assertEquals("com.example:trans", child.groupArtifact)
    assertEquals("2.0.0", child.version)
    assertEquals(true, child.strict)
    assertEquals(listOf("3.0.0"), child.rejects)
  }

  @Test
  fun klibNodeStripsKotlinStdlibChildren() {
    val redirect = NativeRedirect("com.example", "lib-linuxx64", "1.0.0")
    val artifact =
      NativeArtifact(
        klibFileUrl = "lib-linuxx64-1.0.0.klib",
        klibSha256 = "h",
        dependencies =
          listOf(
            NativeDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.0"),
            NativeDependency("org.jetbrains.kotlin", "kotlin-stdlib-common", "2.0.0"),
            NativeDependency("com.example", "keep", "1.2.3"),
          ),
      )
    val processed =
      mutableMapOf<String, NativeResolved>(
        "com.example:lib:1.0.0" to NativeResolved.Klib(redirect, artifact)
      )

    val lookup =
      makeNativeChildLookup(
        processed = processed,
        nativeTarget = "linux_x64",
        cacheBase = "/cache",
        repos = listOf("https://repo1.example/"),
        deps = noIoDeps,
      )

    val children = assertNotNull(lookup("com.example:lib", "1.0.0").get())
    assertEquals(listOf("com.example:keep"), children.map { it.groupArtifact })
  }
}

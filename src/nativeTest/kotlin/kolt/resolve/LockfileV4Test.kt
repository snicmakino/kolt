package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LockfileV4Test {

  @Test
  fun roundtripsV4LockfileWithEmptyClasspathBundles() {
    val original =
      Lockfile(
        version = 4,
        kotlin = "2.3.20",
        jvmTarget = "25",
        dependencies =
          mapOf(
            "org.jetbrains.kotlin:kotlin-stdlib" to
              LockEntry(version = "2.3.20", sha256 = "abc123", transitive = false, test = false)
          ),
        classpathBundles = emptyMap(),
      )
    val json = serializeLockfile(original)
    val parsed = assertNotNull(parseLockfile(json).get())
    assertEquals(original, parsed)
  }

  @Test
  fun roundtripsV4LockfileWithClasspathBundles() {
    val original =
      Lockfile(
        version = 4,
        kotlin = "2.3.20",
        jvmTarget = "25",
        dependencies =
          mapOf(
            "org.jetbrains.kotlin:kotlin-stdlib" to
              LockEntry(version = "2.3.20", sha256 = "main-sha", transitive = false, test = false)
          ),
        classpathBundles =
          mapOf(
            "bta-impl" to
              mapOf(
                "org.jetbrains.kotlin:kotlin-build-tools-impl" to
                  LockEntry(
                    version = "2.3.20",
                    sha256 = "bundle-sha",
                    transitive = false,
                    test = false,
                  )
              ),
            "fixture" to
              mapOf(
                "org.jetbrains.kotlin:kotlin-stdlib" to
                  LockEntry(
                    version = "2.3.20",
                    sha256 = "fixture-sha",
                    transitive = false,
                    test = false,
                  )
              ),
          ),
      )
    val json = serializeLockfile(original)
    val parsed = assertNotNull(parseLockfile(json).get())
    assertEquals(original, parsed)
  }

  @Test
  fun parsesV4LockfileWithMissingClasspathBundlesField() {
    val json =
      """
      {
        "version": 4,
        "kotlin": "2.3.20",
        "jvm_target": "25",
        "dependencies": {
          "org.jetbrains.kotlin:kotlin-stdlib": {
            "version": "2.3.20",
            "sha256": "abc",
            "transitive": false,
            "test": false
          }
        }
      }
      """
        .trimIndent()
    val parsed = assertNotNull(parseLockfile(json).get())
    assertEquals(4, parsed.version)
    assertEquals(emptyMap(), parsed.classpathBundles)
  }

  @Test
  fun rejectsV3LockfileViaParseLockfile() {
    val json =
      """
      {
        "version": 3,
        "kotlin": "2.3.20",
        "jvm_target": "25",
        "dependencies": {}
      }
      """
        .trimIndent()
    val error = parseLockfile(json).getError()
    val unsupported = assertIs<LockfileError.UnsupportedVersion>(error)
    assertEquals(3, unsupported.version)
  }
}

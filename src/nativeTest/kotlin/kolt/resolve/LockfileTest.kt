package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LockfileTest {

  @Test
  fun parseValidV3Lockfile() {
    val json =
      """
            {
                "version": 3,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core": {
                        "version": "1.9.0",
                        "sha256": "abc123def456"
                    }
                }
            }
        """
        .trimIndent()
    val lockfile = assertNotNull(parseLockfile(json).get())
    assertEquals(3, lockfile.version)
    assertEquals("2.1.0", lockfile.kotlin)
    assertEquals("17", lockfile.jvmTarget)
    assertEquals(1, lockfile.dependencies.size)
    val entry =
      assertNotNull(lockfile.dependencies["org.jetbrains.kotlinx:kotlinx-coroutines-core"])
    assertEquals("1.9.0", entry.version)
    assertEquals("abc123def456", entry.sha256)
    assertEquals(false, entry.transitive)
    assertEquals(false, entry.test)
  }

  @Test
  fun parseEmptyDependencies() {
    val json =
      """
            {
                "version": 3,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {}
            }
        """
        .trimIndent()
    val lockfile = assertNotNull(parseLockfile(json).get())
    assertEquals(0, lockfile.dependencies.size)
  }

  @Test
  fun parseV1LockfileReturnsUnsupportedVersion() {
    val json =
      """
            {
                "version": 1,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {}
            }
        """
        .trimIndent()
    val err = assertIs<LockfileError.UnsupportedVersion>(parseLockfile(json).getError())
    assertEquals(1, err.version)
  }

  @Test
  fun parseV2LockfileReturnsUnsupportedVersion() {
    val json =
      """
            {
                "version": 2,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {
                    "org.jetbrains.kotlin:kotlin-stdlib": {
                        "version": "2.1.0",
                        "sha256": "def456",
                        "transitive": true
                    }
                }
            }
        """
        .trimIndent()
    val err = assertIs<LockfileError.UnsupportedVersion>(parseLockfile(json).getError())
    assertEquals(2, err.version)
  }

  @Test
  fun parseFutureVersionReturnsUnsupportedVersion() {
    val json =
      """
            {
                "version": 99,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {}
            }
        """
        .trimIndent()
    assertIs<LockfileError.UnsupportedVersion>(parseLockfile(json).getError())
  }

  @Test
  fun parseInvalidJsonReturnsErr() {
    assertIs<LockfileError.ParseFailed>(parseLockfile("not json").getError())
  }

  @Test
  fun parseV3EntryWithTestTrueAndTransitiveTrue() {
    val json =
      """
            {
                "version": 3,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {
                    "org.jetbrains.kotlin:kotlin-test-junit5": {
                        "version": "2.1.0",
                        "sha256": "aaa",
                        "transitive": false,
                        "test": true
                    },
                    "org.junit.jupiter:junit-jupiter-api": {
                        "version": "5.10.0",
                        "sha256": "bbb",
                        "transitive": true,
                        "test": true
                    }
                }
            }
        """
        .trimIndent()
    val lockfile = assertNotNull(parseLockfile(json).get())
    val direct = assertNotNull(lockfile.dependencies["org.jetbrains.kotlin:kotlin-test-junit5"])
    assertEquals(true, direct.test)
    assertEquals(false, direct.transitive)
    val transitive = assertNotNull(lockfile.dependencies["org.junit.jupiter:junit-jupiter-api"])
    assertEquals(true, transitive.test)
    assertEquals(true, transitive.transitive)
  }

  @Test
  fun parseV3EntryWithTestFieldAbsentDefaultsToFalse() {
    val json =
      """
            {
                "version": 3,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {
                    "com.example:lib": {
                        "version": "1.0.0",
                        "sha256": "hash"
                    }
                }
            }
        """
        .trimIndent()
    val lockfile = assertNotNull(parseLockfile(json).get())
    assertEquals(false, lockfile.dependencies["com.example:lib"]!!.test)
  }

  @Test
  fun serializeSortsDependenciesAlphabetically() {
    val lockfile =
      Lockfile(
        version = 3,
        kotlin = "2.1.0",
        jvmTarget = "17",
        dependencies =
          mapOf(
            "com.squareup:okhttp" to LockEntry("4.12.0", "bbb222"),
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" to LockEntry("1.9.0", "aaa111"),
          ),
      )
    val serialized = serializeLockfile(lockfile)
    val reparsed = assertNotNull(parseLockfile(serialized).get())
    assertEquals(lockfile, reparsed)

    val comIndex = serialized.indexOf("com.squareup:okhttp")
    val orgIndex = serialized.indexOf("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    assertTrue(comIndex < orgIndex, "dependencies should be sorted alphabetically")
  }

  @Test
  fun serializeV3RoundTripPreservesTestFlag() {
    val lockfile =
      Lockfile(
        version = 3,
        kotlin = "2.1.0",
        jvmTarget = "17",
        dependencies =
          mapOf(
            "org.example:main-lib" to LockEntry("1.0.0", "hash1", transitive = false, test = false),
            "org.example:main-transitive" to
              LockEntry("2.0.0", "hash2", transitive = true, test = false),
            "org.example:test-lib" to LockEntry("3.0.0", "hash3", transitive = false, test = true),
            "org.example:test-transitive" to
              LockEntry("4.0.0", "hash4", transitive = true, test = true),
          ),
      )
    val serialized = serializeLockfile(lockfile)
    val reparsed = assertNotNull(parseLockfile(serialized).get())
    assertEquals(lockfile, reparsed)
    assertEquals(3, reparsed.version)
    assertEquals(false, reparsed.dependencies["org.example:main-lib"]!!.test)
    assertEquals(true, reparsed.dependencies["org.example:test-lib"]!!.test)
    assertEquals(true, reparsed.dependencies["org.example:test-transitive"]!!.test)
  }

  @Test
  fun serializedV3OutputContainsVersionAndTestTrueField() {
    val lockfile =
      Lockfile(
        version = 3,
        kotlin = "2.1.0",
        jvmTarget = "17",
        dependencies =
          mapOf(
            "org.jetbrains.kotlin:kotlin-test-junit5" to
              LockEntry("2.1.0", "hash", transitive = false, test = true)
          ),
      )
    val serialized = serializeLockfile(lockfile)
    assertTrue(serialized.contains("\"version\": 3"), "must emit schema version 3")
    assertTrue(serialized.contains("\"test\": true"), "must emit per-entry test flag when true")
  }
}

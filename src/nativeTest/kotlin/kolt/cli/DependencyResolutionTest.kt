package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.resolve.Origin
import kolt.resolve.ResolvedDep
import kolt.resolve.buildLockfileFromResolved
import kolt.resolve.parseLockfile
import kolt.resolve.serializeLockfile
import kolt.testConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.mkdtemp

class FindOverlappingDependenciesTest {

  @Test
  fun noOverlapReturnsEmpty() {
    val main = mapOf("com.example:a" to "1.0")
    val test = mapOf("com.example:b" to "2.0")

    val result = findOverlappingDependencies(main, test)

    assertTrue(result.isEmpty())
  }

  @Test
  fun sameVersionOverlapIsExcluded() {
    val main = mapOf("com.example:a" to "1.0")
    val test = mapOf("com.example:a" to "1.0")

    val result = findOverlappingDependencies(main, test)

    assertTrue(result.isEmpty())
  }

  @Test
  fun differentVersionOverlapIsDetected() {
    val main = mapOf("com.example:a" to "1.0")
    val test = mapOf("com.example:a" to "2.0")

    val result = findOverlappingDependencies(main, test)

    assertEquals(1, result.size)
    assertEquals("com.example:a", result[0].groupArtifact)
    assertEquals("1.0", result[0].mainVersion)
    assertEquals("2.0", result[0].testVersion)
  }

  @Test
  fun multipleOverlapsDetected() {
    val main = mapOf("com.example:a" to "1.0", "com.example:b" to "2.0", "com.example:c" to "3.0")
    val test = mapOf("com.example:a" to "1.1", "com.example:b" to "2.0", "com.example:c" to "3.1")

    val result = findOverlappingDependencies(main, test)

    assertEquals(2, result.size)
    val keys = result.map { it.groupArtifact }.toSet()
    assertTrue("com.example:a" in keys)
    assertTrue("com.example:c" in keys)
  }

  @Test
  fun emptyMapsReturnEmpty() {
    val result = findOverlappingDependencies(emptyMap(), emptyMap())

    assertTrue(result.isEmpty())
  }
}

@OptIn(ExperimentalForeignApi::class)
class ResolveDependenciesNoDepsTest {
  private var originalCwd: String = ""
  private var tmpDir: String = ""

  @BeforeTest
  fun setUp() {
    originalCwd = memScoped {
      val buf = allocArray<ByteVar>(PATH_MAX)
      getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
    }
    tmpDir = createTempDir("kolt-resolve-deps-")
    check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
  }

  @AfterTest
  fun tearDown() {
    chdir(originalCwd)
    if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
      removeDirectoryRecursive(tmpDir)
    }
  }

  @Test
  fun emptyDepsReturnsOutcomeWithNullClasspathAndEmptyJars() {
    // Use a native target so autoInjectedTestDeps stays empty — the
    // JVM path auto-injects kotlin-test-junit5 and never reaches the
    // empty-deps branch. doInstall exercises this branch for native.
    val config =
      testConfig(target = "linuxX64", dependencies = emptyMap(), testDependencies = emptyMap())

    val outcome =
      resolveDependencies(config).getOrElse { error("resolveDependencies failed: exit=$it") }

    assertNull(outcome.mainClasspath)
    assertTrue(outcome.mainJars.isEmpty())
    assertTrue(outcome.allJars.isEmpty())
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      return result.toKString()
    }
  }
}

class SplitJvmOutcomeTest {

  @Test
  fun emptyDepsProducesNullClasspathAndEmptyJarLists() {
    val outcome = splitJvmOutcome(emptyList())
    assertNull(outcome.mainClasspath)
    assertTrue(outcome.mainJars.isEmpty())
    assertTrue(outcome.allJars.isEmpty())
  }

  @Test
  fun mixedOriginSplitsIntoMainAndAllJars() {
    val mainDep =
      ResolvedDep(
        groupArtifact = "com.example:main-lib",
        version = "1.0.0",
        sha256 = "hashMain",
        cachePath = "/cache/main-lib.jar",
        origin = Origin.MAIN,
      )
    val mainTransitive =
      ResolvedDep(
        groupArtifact = "com.example:main-transitive",
        version = "1.0.0",
        sha256 = "hashMainT",
        cachePath = "/cache/main-transitive.jar",
        transitive = true,
        origin = Origin.MAIN,
      )
    val testDep =
      ResolvedDep(
        groupArtifact = "org.junit.jupiter:junit-jupiter",
        version = "5.10.0",
        sha256 = "hashTest",
        cachePath = "/cache/junit-jupiter.jar",
        origin = Origin.TEST,
      )

    val outcome = splitJvmOutcome(listOf(mainDep, mainTransitive, testDep))

    assertEquals(2, outcome.mainJars.size)
    val mainPaths = outcome.mainJars.map { it.cachePath }.toSet()
    assertTrue("/cache/main-lib.jar" in mainPaths)
    assertTrue("/cache/main-transitive.jar" in mainPaths)
    assertFalse("/cache/junit-jupiter.jar" in mainPaths)

    assertEquals(3, outcome.allJars.size)
    val allPaths = outcome.allJars.map { it.cachePath }.toSet()
    assertTrue("/cache/main-lib.jar" in allPaths)
    assertTrue("/cache/main-transitive.jar" in allPaths)
    assertTrue("/cache/junit-jupiter.jar" in allPaths)

    // allJars = mainJars + testJars in that order; main entries appear first.
    assertEquals(outcome.mainJars, outcome.allJars.take(outcome.mainJars.size))

    val mainClasspath = outcome.mainClasspath
    assertNotNull(mainClasspath)
    assertTrue(mainClasspath.split(":").toSet() == mainPaths)
    assertFalse(mainClasspath.contains("/cache/junit-jupiter.jar"))
  }

  @Test
  fun testOnlyDepsProduceNullMainClasspathButPopulatedAllJars() {
    val testDep =
      ResolvedDep(
        groupArtifact = "org.junit.jupiter:junit-jupiter",
        version = "5.10.0",
        sha256 = "hashTest",
        cachePath = "/cache/junit-jupiter.jar",
        origin = Origin.TEST,
      )

    val outcome = splitJvmOutcome(listOf(testDep))

    assertNull(outcome.mainClasspath)
    assertTrue(outcome.mainJars.isEmpty())
    assertEquals(1, outcome.allJars.size)
    assertEquals("/cache/junit-jupiter.jar", outcome.allJars[0].cachePath)
  }
}

class BuildLockfileFromResolvedTest {

  @Test
  fun mainOriginDepsAreLockedWithTestFalse() {
    val config = testConfig()
    val deps =
      listOf(
        ResolvedDep(
          groupArtifact = "com.example:lib",
          version = "1.0.0",
          sha256 = "hashMain",
          cachePath = "/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
          transitive = false,
          origin = Origin.MAIN,
        )
      )

    val lockfile = buildLockfileFromResolved(config, deps)

    assertEquals(4, lockfile.version)
    val entry = lockfile.dependencies["com.example:lib"]
    assertNotNull(entry)
    assertFalse(entry.test)
  }

  @Test
  fun testOriginDepsAreLockedWithTestTrue() {
    val config = testConfig()
    val deps =
      listOf(
        ResolvedDep(
          groupArtifact = "org.junit.jupiter:junit-jupiter",
          version = "5.10.0",
          sha256 = "hashTest",
          cachePath = "/cache/org/junit/jupiter/junit-jupiter/5.10.0/junit-jupiter-5.10.0.jar",
          transitive = false,
          origin = Origin.TEST,
        )
      )

    val lockfile = buildLockfileFromResolved(config, deps)

    val entry = lockfile.dependencies["org.junit.jupiter:junit-jupiter"]
    assertNotNull(entry)
    assertTrue(entry.test)
  }

  @Test
  fun serializedLockfileCarriesVersion4AndTestFlagForTestEntries() {
    val config = testConfig()
    val deps =
      listOf(
        ResolvedDep(
          groupArtifact = "com.example:main-lib",
          version = "1.0.0",
          sha256 = "hashMain",
          cachePath = "/cache/main-lib.jar",
          origin = Origin.MAIN,
        ),
        ResolvedDep(
          groupArtifact = "com.example:test-lib",
          version = "1.0.0",
          sha256 = "hashTest",
          cachePath = "/cache/test-lib.jar",
          origin = Origin.TEST,
        ),
      )

    val lockfile = buildLockfileFromResolved(config, deps)
    val serialized = serializeLockfile(lockfile)
    assertTrue(serialized.contains("\"version\": 4"))
    assertTrue(serialized.contains("\"test\": true"))

    val parsed = parseLockfile(serialized).getOrElse { error("parseLockfile failed: $it") }
    assertEquals(false, parsed.dependencies["com.example:main-lib"]?.test)
    assertEquals(true, parsed.dependencies["com.example:test-lib"]?.test)
  }
}

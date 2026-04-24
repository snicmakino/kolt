package kolt.build

import kolt.resolve.Origin
import kolt.resolve.ResolvedDep
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkspaceTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun parseJson(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

  @Test
  fun generateWorkspaceJsonMinimalProject() {
    val config = testConfig()

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val modules = root["modules"]!!.jsonArray
    assertEquals(2, modules.size)

    val mainModule = modules[0].jsonObject
    assertEquals("my-app.main", mainModule["name"]!!.jsonPrimitive.content)

    val testModule = modules[1].jsonObject
    assertEquals("my-app.test", testModule["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonNoTestSources() {
    val config = testConfig(testSources = emptyList())

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val modules = root["modules"]!!.jsonArray
    assertEquals(1, modules.size)
    assertEquals("my-app.main", modules[0].jsonObject["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonIncludesSourceRoots() {
    val config = testConfig(sources = listOf("src", "generated"))

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val module = root["modules"]!!.jsonArray[0].jsonObject
    val contentRoots = module["contentRoots"]!!.jsonArray
    assertEquals(1, contentRoots.size)

    val sourceRoots = contentRoots[0].jsonObject["sourceRoots"]!!.jsonArray
    assertEquals(2, sourceRoots.size)
    assertEquals("<WORKSPACE>/src", sourceRoots[0].jsonObject["path"]!!.jsonPrimitive.content)
    assertEquals("<WORKSPACE>/generated", sourceRoots[1].jsonObject["path"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonIncludesTestSourceRoots() {
    val config = testConfig(testSources = listOf("test", "test-integration"))

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val modules = root["modules"]!!.jsonArray
    assertEquals(2, modules.size)

    val testModule = modules[1].jsonObject
    assertEquals("my-app.test", testModule["name"]!!.jsonPrimitive.content)

    val testSourceRoots =
      testModule["contentRoots"]!!.jsonArray[0].jsonObject["sourceRoots"]!!.jsonArray
    assertEquals(2, testSourceRoots.size)
    assertEquals("<WORKSPACE>/test", testSourceRoots[0].jsonObject["path"]!!.jsonPrimitive.content)
    assertEquals("java-test", testSourceRoots[0].jsonObject["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonIncludesLibraries() {
    val config = testConfig()
    val mainDeps =
      listOf(
        ResolvedDep(
          "com.example:lib",
          "1.0.0",
          "abc123",
          "/home/user/.kolt/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
          origin = Origin.MAIN,
        ),
        ResolvedDep(
          "org.other:util",
          "2.0.0",
          "def456",
          "/home/user/.kolt/cache/org/other/util/2.0.0/util-2.0.0.jar",
          transitive = true,
          origin = Origin.MAIN,
        ),
      )

    val result = generateWorkspaceJson(config, mainDeps, emptyList())
    val root = parseJson(result)

    val libraries = root["libraries"]!!.jsonArray
    assertEquals(2, libraries.size)

    val lib = libraries[0].jsonObject
    assertEquals("com.example:lib:1.0.0", lib["name"]!!.jsonPrimitive.content)
    assertEquals("java-imported", lib["type"]!!.jsonPrimitive.content)

    val roots = lib["roots"]!!.jsonArray
    assertEquals(1, roots.size)
    assertEquals(
      "/home/user/.kolt/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
      roots[0].jsonObject["path"]!!.jsonPrimitive.content,
    )
  }

  // kotlin-lsp's LibraryRootData reads multi-root libraries: the first
  // entry defaults to CLASSES, an additional entry with type=SOURCES
  // points at the -sources.jar so IDE go-to-definition navigates into
  // Kotlin source instead of decompiled bytecode (losing inline bodies,
  // default args, reified types).
  @Test
  fun generateWorkspaceJsonLibraryAttachesSourcesRootWhenAvailable() {
    val config = testConfig()
    val mainDeps =
      listOf(
        ResolvedDep(
          "com.example:lib",
          "1.0.0",
          "abc123",
          "/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
          sourcesPath = "/cache/com/example/lib/1.0.0/lib-1.0.0-sources.jar",
        )
      )

    val result = generateWorkspaceJson(config, mainDeps, emptyList())
    val root = parseJson(result)

    val roots = root["libraries"]!!.jsonArray[0].jsonObject["roots"]!!.jsonArray
    assertEquals(2, roots.size)
    assertEquals(
      "/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
      roots[0].jsonObject["path"]!!.jsonPrimitive.content,
    )
    assertEquals(
      null,
      roots[0].jsonObject["type"],
      "binary root omits type so kotlin-lsp defaults to CLASSES",
    )
    assertEquals(
      "/cache/com/example/lib/1.0.0/lib-1.0.0-sources.jar",
      roots[1].jsonObject["path"]!!.jsonPrimitive.content,
    )
    assertEquals("SOURCES", roots[1].jsonObject["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonLibraryOmitsSourcesRootWhenNull() {
    val config = testConfig()
    val mainDeps = listOf(ResolvedDep("com.example:lib", "1.0.0", "abc123", "/cache/lib.jar"))

    val result = generateWorkspaceJson(config, mainDeps, emptyList())
    val root = parseJson(result)

    val roots = root["libraries"]!!.jsonArray[0].jsonObject["roots"]!!.jsonArray
    assertEquals(1, roots.size)
  }

  @Test
  fun generateWorkspaceJsonLibraryAttributes() {
    val config = testConfig()
    val mainDeps = listOf(ResolvedDep("com.example:lib", "1.0.0", "abc123", "/cache/lib.jar"))

    val result = generateWorkspaceJson(config, mainDeps, emptyList())
    val root = parseJson(result)

    val lib = root["libraries"]!!.jsonArray[0].jsonObject
    val attrs = lib["properties"]!!.jsonObject["attributes"]!!.jsonObject
    assertEquals("com.example", attrs["groupId"]!!.jsonPrimitive.content)
    assertEquals("lib", attrs["artifactId"]!!.jsonPrimitive.content)
    assertEquals("1.0.0", attrs["version"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonIncludesSdk() {
    val config = testConfig(jvmTarget = "21")

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val sdks = root["sdks"]!!.jsonArray
    assertEquals(1, sdks.size)

    val sdk = sdks[0].jsonObject
    assertEquals("21", sdk["name"]!!.jsonPrimitive.content)
    assertEquals("jdk", sdk["type"]!!.jsonPrimitive.content)
    assertEquals("JDK_21", sdk["version"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonIncludesKotlinSettings() {
    val config = testConfig(jvmTarget = "17")

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val settings = root["kotlinSettings"]!!.jsonArray
    assertEquals(2, settings.size)

    val main = settings[0].jsonObject
    assertEquals("my-app.main", main["module"]!!.jsonPrimitive.content)

    val compilerArgs = main["compilerArguments"]!!.jsonPrimitive.content
    assertTrue(compilerArgs.startsWith("J{"), "compilerArguments should start with J{ prefix")
    assertTrue(compilerArgs.contains("\"jvmTarget\":\"17\""))
  }

  // kotlin-lsp diagnoses test sources against this entry, so it must carry
  // the same jvmTarget as main (matching the compiler the test actually
  // runs under) and isTestModule=true so IDE features treat assertions and
  // test-only APIs correctly.
  @Test
  fun generateWorkspaceJsonEmitsKotlinSettingsForTestModule() {
    val config = testConfig(jvmTarget = "17", testSources = listOf("test", "test-integration"))

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val settings = root["kotlinSettings"]!!.jsonArray
    assertEquals(2, settings.size)

    val test = settings[1].jsonObject
    assertEquals("my-app.test", test["module"]!!.jsonPrimitive.content)
    assertEquals("my-app.test", test["externalProjectId"]!!.jsonPrimitive.content)
    assertEquals(true, test["isTestModule"]!!.jsonPrimitive.content.toBoolean())

    val testSourceRoots = test["sourceRoots"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf("<WORKSPACE>/test", "<WORKSPACE>/test-integration"), testSourceRoots)

    val compilerArgs = test["compilerArguments"]!!.jsonPrimitive.content
    assertTrue(compilerArgs.startsWith("J{"))
    assertTrue(compilerArgs.contains("\"jvmTarget\":\"17\""))
  }

  @Test
  fun generateWorkspaceJsonKotlinSettingsOmitsTestEntryWhenNoTestSources() {
    val config = testConfig(testSources = emptyList())

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val settings = root["kotlinSettings"]!!.jsonArray
    assertEquals(1, settings.size)
    assertEquals("my-app.main", settings[0].jsonObject["module"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonModuleDependenciesReferenceLibraries() {
    val config = testConfig()
    val mainDeps = listOf(ResolvedDep("com.example:lib", "1.0.0", "abc123", "/cache/lib.jar"))

    val result = generateWorkspaceJson(config, mainDeps, emptyList())
    val root = parseJson(result)

    val module = root["modules"]!!.jsonArray[0].jsonObject
    val moduleDeps = module["dependencies"]!!.jsonArray

    val libraryDep =
      moduleDeps.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "library" }
    assertEquals("com.example:lib:1.0.0", libraryDep["name"]!!.jsonPrimitive.content)
    assertEquals("compile", libraryDep["scope"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonTestModuleDependsOnMainModule() {
    val config = testConfig()

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val testModule = root["modules"]!!.jsonArray[1].jsonObject
    val testDeps = testModule["dependencies"]!!.jsonArray

    val moduleDep =
      testDeps.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "module" }
    assertEquals("my-app.main", moduleDep["name"]!!.jsonPrimitive.content)
  }

  // - Main module's dependency list carries main origin jars only so
  //   IDE navigation on main sources cannot cross into test-only
  //   classes.
  // - Test module sees main + test (test sources typically reference
  //   both). The depends-on-main link still comes via `type=module`.
  // - Top-level `libraries` lists every known jar (main + test) so IDE
  //   caches and library index views are complete.
  @Test
  fun generateWorkspaceJsonSplitsMainAndTestIntoRightModules() {
    val config = testConfig()
    val mainDeps =
      listOf(
        ResolvedDep(
          groupArtifact = "com.example:main-lib",
          version = "1.0.0",
          sha256 = "hashMain",
          cachePath = "/cache/main-lib-1.0.0.jar",
          origin = Origin.MAIN,
        )
      )
    val testDeps =
      listOf(
        ResolvedDep(
          groupArtifact = "org.junit.jupiter:junit-jupiter",
          version = "5.10.0",
          sha256 = "hashJupiter",
          cachePath = "/cache/junit-jupiter-5.10.0.jar",
          origin = Origin.TEST,
        ),
        ResolvedDep(
          groupArtifact = "org.opentest4j:opentest4j",
          version = "1.3.0",
          sha256 = "hashOpentest4j",
          cachePath = "/cache/opentest4j-1.3.0.jar",
          transitive = true,
          origin = Origin.TEST,
        ),
      )

    val result = generateWorkspaceJson(config, mainDeps, testDeps)
    val root = parseJson(result)

    val modules = root["modules"]!!.jsonArray
    val mainLibNames =
      modules[0]
        .jsonObject["dependencies"]!!
        .jsonArray
        .map { it.jsonObject }
        .filter { it["type"]!!.jsonPrimitive.content == "library" }
        .map { it["name"]!!.jsonPrimitive.content }
    assertEquals(listOf("com.example:main-lib:1.0.0"), mainLibNames)
    assertFalse(
      mainLibNames.any { it.contains("junit") || it.contains("opentest4j") },
      "main module must not list test-origin libraries: $mainLibNames",
    )

    val testLibNames =
      modules[1]
        .jsonObject["dependencies"]!!
        .jsonArray
        .map { it.jsonObject }
        .filter { it["type"]!!.jsonPrimitive.content == "library" }
        .map { it["name"]!!.jsonPrimitive.content }
    assertEquals(
      listOf(
        "com.example:main-lib:1.0.0",
        "org.junit.jupiter:junit-jupiter:5.10.0",
        "org.opentest4j:opentest4j:1.3.0",
      ),
      testLibNames,
      "test module lists main first, then test origin deps",
    )

    val topLibs =
      root["libraries"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertEquals(
      listOf(
        "com.example:main-lib:1.0.0",
        "org.junit.jupiter:junit-jupiter:5.10.0",
        "org.opentest4j:opentest4j:1.3.0",
      ),
      topLibs,
      "top-level libraries must index main ∪ test",
    )
  }

  // Per-module java language level is carried via `javaSettings`; the JSON
  // wire has no `-Xjdk-release` slot, so this is the only channel for
  // kotlin-lsp to diagnose mixed Kotlin/Java code at the right level.
  @Test
  fun generateWorkspaceJsonEmitsJavaSettingsForMainAndTest() {
    val config = testConfig(jvmTarget = "17")

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val javaSettings = root["javaSettings"]!!.jsonArray
    assertEquals(2, javaSettings.size)

    val main = javaSettings[0].jsonObject
    assertEquals("my-app.main", main["module"]!!.jsonPrimitive.content)
    assertEquals("JDK_17", main["languageLevelId"]!!.jsonPrimitive.content)
    assertEquals(false, main["inheritedCompilerOutput"]!!.jsonPrimitive.content.toBoolean())
    assertEquals(false, main["excludeOutput"]!!.jsonPrimitive.content.toBoolean())

    val test = javaSettings[1].jsonObject
    assertEquals("my-app.test", test["module"]!!.jsonPrimitive.content)
    assertEquals("JDK_17", test["languageLevelId"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonJavaSettingsOmitsTestEntryWhenNoTestSources() {
    val config = testConfig(testSources = emptyList())

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val javaSettings = root["javaSettings"]!!.jsonArray
    assertEquals(1, javaSettings.size)
    assertEquals("my-app.main", javaSettings[0].jsonObject["module"]!!.jsonPrimitive.content)
  }

  @Test
  fun generateWorkspaceJsonJavaSettingsLanguageLevelTracksJvmTarget() {
    val config = testConfig(jvmTarget = "21")

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val javaSettings = root["javaSettings"]!!.jsonArray
    javaSettings.forEach {
      assertEquals("JDK_21", it.jsonObject["languageLevelId"]!!.jsonPrimitive.content)
    }
  }

  // kotlin-lsp's JSON importer maps `excludedPatterns` to the underlying
  // ContentRootEntity but drops `excludedUrls`, so patterns are the only
  // effective exclusion channel for the WORKSPACE root today.
  @Test
  fun generateWorkspaceJsonMainContentRootExcludesBuildOutputsAndCacheDirs() {
    val config = testConfig()

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val mainContentRoot =
      root["modules"]!!.jsonArray[0].jsonObject["contentRoots"]!!.jsonArray[0].jsonObject
    val excludedPatterns =
      mainContentRoot["excludedPatterns"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf("build", ".kolt-cache", ".kolt"), excludedPatterns)
  }

  @Test
  fun generateWorkspaceJsonTestContentRootExcludesBuildOutputsAndCacheDirs() {
    val config = testConfig()

    val result = generateWorkspaceJson(config, emptyList(), emptyList())
    val root = parseJson(result)

    val testContentRoot =
      root["modules"]!!.jsonArray[1].jsonObject["contentRoots"]!!.jsonArray[0].jsonObject
    val excludedPatterns =
      testContentRoot["excludedPatterns"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf("build", ".kolt-cache", ".kolt"), excludedPatterns)
  }

  @Test
  fun generateKlsClasspathFromResolvedDeps() {
    val deps =
      listOf(
        ResolvedDep("com.example:lib", "1.0.0", "abc", "/cache/lib-1.0.0.jar"),
        ResolvedDep("org.other:util", "2.0.0", "def", "/cache/util-2.0.0.jar"),
      )

    val result = generateKlsClasspath(deps)

    assertEquals("/cache/lib-1.0.0.jar:/cache/util-2.0.0.jar", result)
  }

  @Test
  fun generateKlsClasspathEmptyDeps() {
    val result = generateKlsClasspath(emptyList())
    assertEquals("", result)
  }
}

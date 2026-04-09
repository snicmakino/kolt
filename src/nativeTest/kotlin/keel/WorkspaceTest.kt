package keel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkspaceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseJson(text: String): JsonObject =
        json.parseToJsonElement(text).jsonObject

    @Test
    fun generateWorkspaceJsonMinimalProject() {
        val config = testConfig()
        val deps = emptyList<ResolvedDep>()

        val result = generateWorkspaceJson(config, deps)
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
        val deps = emptyList<ResolvedDep>()

        val result = generateWorkspaceJson(config, deps)
        val root = parseJson(result)

        val modules = root["modules"]!!.jsonArray
        assertEquals(1, modules.size)
        assertEquals("my-app.main", modules[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun generateWorkspaceJsonIncludesSourceRoots() {
        val config = testConfig(sources = listOf("src", "generated"))
        val deps = emptyList<ResolvedDep>()

        val result = generateWorkspaceJson(config, deps)
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
        val deps = emptyList<ResolvedDep>()

        val result = generateWorkspaceJson(config, deps)
        val root = parseJson(result)

        val modules = root["modules"]!!.jsonArray
        assertEquals(2, modules.size)

        val testModule = modules[1].jsonObject
        assertEquals("my-app.test", testModule["name"]!!.jsonPrimitive.content)

        val testSourceRoots = testModule["contentRoots"]!!.jsonArray[0].jsonObject["sourceRoots"]!!.jsonArray
        assertEquals(2, testSourceRoots.size)
        assertEquals("<WORKSPACE>/test", testSourceRoots[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("java-test", testSourceRoots[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun generateWorkspaceJsonIncludesLibraries() {
        val config = testConfig()
        val deps = listOf(
            ResolvedDep("com.example:lib", "1.0.0", "abc123", "/home/user/.keel/cache/com/example/lib/1.0.0/lib-1.0.0.jar"),
            ResolvedDep("org.other:util", "2.0.0", "def456", "/home/user/.keel/cache/org/other/util/2.0.0/util-2.0.0.jar", transitive = true)
        )

        val result = generateWorkspaceJson(config, deps)
        val root = parseJson(result)

        val libraries = root["libraries"]!!.jsonArray
        assertEquals(2, libraries.size)

        val lib = libraries[0].jsonObject
        assertEquals("com.example:lib:1.0.0", lib["name"]!!.jsonPrimitive.content)
        assertEquals("java-imported", lib["type"]!!.jsonPrimitive.content)

        val roots = lib["roots"]!!.jsonArray
        assertEquals(1, roots.size)
        assertEquals(
            "/home/user/.keel/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
            roots[0].jsonObject["path"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun generateWorkspaceJsonLibraryAttributes() {
        val config = testConfig()
        val deps = listOf(
            ResolvedDep("com.example:lib", "1.0.0", "abc123", "/cache/lib.jar")
        )

        val result = generateWorkspaceJson(config, deps)
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
        val deps = emptyList<ResolvedDep>()

        val result = generateWorkspaceJson(config, deps)
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
        val deps = emptyList<ResolvedDep>()

        val result = generateWorkspaceJson(config, deps)
        val root = parseJson(result)

        val settings = root["kotlinSettings"]!!.jsonArray
        assertEquals(1, settings.size)

        val setting = settings[0].jsonObject
        assertEquals("my-app.main", setting["module"]!!.jsonPrimitive.content)

        val compilerArgs = setting["compilerArguments"]!!.jsonPrimitive.content
        assertTrue(compilerArgs.startsWith("J{"), "compilerArguments should start with J{ prefix")
        assertTrue(compilerArgs.contains("\"jvmTarget\":\"17\""))
    }

    @Test
    fun generateWorkspaceJsonModuleDependenciesReferenceLibraries() {
        val config = testConfig()
        val deps = listOf(
            ResolvedDep("com.example:lib", "1.0.0", "abc123", "/cache/lib.jar")
        )

        val result = generateWorkspaceJson(config, deps)
        val root = parseJson(result)

        val module = root["modules"]!!.jsonArray[0].jsonObject
        val moduleDeps = module["dependencies"]!!.jsonArray

        val libraryDep = moduleDeps.map { it.jsonObject }.first {
            it["type"]!!.jsonPrimitive.content == "library"
        }
        assertEquals("com.example:lib:1.0.0", libraryDep["name"]!!.jsonPrimitive.content)
        assertEquals("compile", libraryDep["scope"]!!.jsonPrimitive.content)
    }

    @Test
    fun generateWorkspaceJsonTestModuleDependsOnMainModule() {
        val config = testConfig()
        val deps = emptyList<ResolvedDep>()

        val result = generateWorkspaceJson(config, deps)
        val root = parseJson(result)

        val testModule = root["modules"]!!.jsonArray[1].jsonObject
        val testDeps = testModule["dependencies"]!!.jsonArray

        val moduleDep = testDeps.map { it.jsonObject }.first {
            it["type"]!!.jsonPrimitive.content == "module"
        }
        assertEquals("my-app.main", moduleDep["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun generateKlsClasspathFromResolvedDeps() {
        val deps = listOf(
            ResolvedDep("com.example:lib", "1.0.0", "abc", "/cache/lib-1.0.0.jar"),
            ResolvedDep("org.other:util", "2.0.0", "def", "/cache/util-2.0.0.jar")
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

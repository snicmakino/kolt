package kolt.build

import kolt.config.KoltConfig
import kolt.resolve.ResolvedDep
import kotlinx.serialization.json.*

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val prettyJson = Json {
  prettyPrint = true
  prettyPrintIndent = "  "
}

// kotlin-lsp's JSON importer honors ContentRootData.excludedPatterns but drops
// excludedUrls (see Kotlin/kotlin-lsp workspace-import/.../json/conversion.kt).
// Keep patterns unprefixed; PatternUtil matches against file names, not paths.
private val DEFAULT_CONTENT_ROOT_EXCLUDES = listOf("build", ".kolt-cache", ".kolt")

fun generateWorkspaceJson(
  config: KoltConfig,
  mainDeps: List<ResolvedDep>,
  testDeps: List<ResolvedDep>,
): String {
  val allDeps = mainDeps + testDeps
  val json = buildJsonObject {
    putJsonArray("modules") {
      add(buildMainModule(config, mainDeps))
      if (config.build.testSources.isNotEmpty()) {
        add(buildTestModule(config, allDeps))
      }
    }
    putJsonArray("libraries") { allDeps.forEach { dep -> add(buildLibraryEntry(dep)) } }
    putJsonArray("sdks") { add(buildSdkEntry(config.build.jvmTarget)) }
    putJsonArray("kotlinSettings") {
      add(buildKotlinSettings(config, isTest = false))
      if (config.build.testSources.isNotEmpty()) {
        add(buildKotlinSettings(config, isTest = true))
      }
    }
    putJsonArray("javaSettings") {
      add(buildJavaSettings("${config.name}.main", config.build.jvmTarget))
      if (config.build.testSources.isNotEmpty()) {
        add(buildJavaSettings("${config.name}.test", config.build.jvmTarget))
      }
    }
  }

  return prettyJson.encodeToString(JsonObject.serializer(), json)
}

fun generateKlsClasspath(resolvedDeps: List<ResolvedDep>): String =
  resolvedDeps.joinToString(":") { it.cachePath }

private fun buildMainModule(config: KoltConfig, resolvedDeps: List<ResolvedDep>): JsonObject =
  buildJsonObject {
    put("name", "${config.name}.main")
    put("type", "JAVA_MODULE")
    putJsonArray("dependencies") {
      add(buildJsonObject { put("type", "moduleSource") })
      add(buildJsonObject { put("type", "inheritedSdk") })
      resolvedDeps.forEach { dep ->
        add(
          buildJsonObject {
            put("type", "library")
            put("name", "${dep.groupArtifact}:${dep.version}")
            put("scope", "compile")
          }
        )
      }
    }
    putJsonArray("contentRoots") {
      add(
        buildJsonObject {
          put("path", "<WORKSPACE>/")
          putJsonArray("excludedPatterns") {
            DEFAULT_CONTENT_ROOT_EXCLUDES.forEach { add(JsonPrimitive(it)) }
          }
          putJsonArray("sourceRoots") {
            config.build.sources.forEach { src ->
              add(
                buildJsonObject {
                  put("path", "<WORKSPACE>/$src")
                  put("type", "java-source")
                }
              )
            }
          }
        }
      )
    }
  }

private fun buildTestModule(config: KoltConfig, resolvedDeps: List<ResolvedDep>): JsonObject =
  buildJsonObject {
    put("name", "${config.name}.test")
    put("type", "JAVA_MODULE")
    putJsonArray("dependencies") {
      add(buildJsonObject { put("type", "moduleSource") })
      add(buildJsonObject { put("type", "inheritedSdk") })
      add(
        buildJsonObject {
          put("type", "module")
          put("name", "${config.name}.main")
          put("scope", "compile")
        }
      )
      resolvedDeps.forEach { dep ->
        add(
          buildJsonObject {
            put("type", "library")
            put("name", "${dep.groupArtifact}:${dep.version}")
            put("scope", "compile")
          }
        )
      }
    }
    putJsonArray("contentRoots") {
      add(
        buildJsonObject {
          put("path", "<WORKSPACE>/")
          putJsonArray("excludedPatterns") {
            DEFAULT_CONTENT_ROOT_EXCLUDES.forEach { add(JsonPrimitive(it)) }
          }
          putJsonArray("sourceRoots") {
            config.build.testSources.forEach { src ->
              add(
                buildJsonObject {
                  put("path", "<WORKSPACE>/$src")
                  put("type", "java-test")
                }
              )
            }
          }
        }
      )
    }
  }

private fun buildLibraryEntry(dep: ResolvedDep): JsonObject = buildJsonObject {
  put("name", "${dep.groupArtifact}:${dep.version}")
  put("type", "java-imported")
  putJsonArray("roots") { add(buildJsonObject { put("path", dep.cachePath) }) }
  putJsonObject("properties") {
    putJsonObject("attributes") {
      val parts = dep.groupArtifact.split(":")
      if (parts.size == 2) {
        put("groupId", parts[0])
        put("artifactId", parts[1])
        put("version", dep.version)
      }
    }
  }
}

private fun buildSdkEntry(jvmTarget: String): JsonObject = buildJsonObject {
  put("name", jvmTarget)
  put("type", "jdk")
  put("version", "JDK_$jvmTarget")
  put("homePath", JsonNull)
  putJsonArray("roots") {}
  put("additionalData", "")
}

private fun buildJavaSettings(moduleName: String, jvmTarget: String): JsonObject = buildJsonObject {
  put("module", moduleName)
  put("inheritedCompilerOutput", false)
  put("excludeOutput", false)
  put("compilerOutput", JsonNull)
  put("compilerOutputForTests", JsonNull)
  put("languageLevelId", "JDK_$jvmTarget")
  putJsonObject("manifestAttributes") {}
}

private fun buildKotlinSettings(config: KoltConfig, isTest: Boolean): JsonObject = buildJsonObject {
  val moduleName = "${config.name}.${if (isTest) "test" else "main"}"
  val sources = if (isTest) config.build.testSources else config.build.sources
  put("name", "Kotlin")
  putJsonArray("sourceRoots") { sources.forEach { add(JsonPrimitive("<WORKSPACE>/$it")) } }
  putJsonArray("configFileItems") {}
  put("module", moduleName)
  put("useProjectSettings", false)
  putJsonArray("implementedModuleNames") {}
  putJsonArray("dependsOnModuleNames") {}
  putJsonArray("additionalVisibleModuleNames") {}
  put("productionOutputPath", JsonNull)
  put("testOutputPath", JsonNull)
  putJsonArray("sourceSetNames") {}
  put("isTestModule", isTest)
  put("externalProjectId", moduleName)
  put("isHmppEnabled", true)
  putJsonArray("pureKotlinSourceFolders") {}
  put("kind", "default")
  // kotlin-lsp requires "J" prefix for JSON-encoded compiler arguments.
  put(
    "compilerArguments",
    "J{\"jvmTarget\":\"${config.build.jvmTarget}\",\"pluginOptions\":[],\"pluginClasspaths\":[]}",
  )
  put("additionalArguments", JsonNull)
  put("scriptTemplates", JsonNull)
  put("scriptTemplatesClasspath", JsonNull)
  put("outputDirectoryForJsLibraryFiles", JsonNull)
  put("targetPlatform", JsonNull)
  putJsonArray("externalSystemRunTasks") {}
  put("version", 5)
  put("flushNeeded", false)
}

package kolt.build

import kolt.config.KoltConfig
import kolt.resolve.ResolvedDep
import kotlinx.serialization.json.*

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val prettyJson = Json {
  prettyPrint = true
  prettyPrintIndent = "  "
}

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
    putJsonArray("kotlinSettings") { add(buildKotlinSettings(config)) }
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

private fun buildKotlinSettings(config: KoltConfig): JsonObject = buildJsonObject {
  put("name", "Kotlin")
  putJsonArray("sourceRoots") {
    config.build.sources.forEach { add(JsonPrimitive("<WORKSPACE>/$it")) }
  }
  putJsonArray("configFileItems") {}
  put("module", "${config.name}.main")
  put("useProjectSettings", false)
  putJsonArray("implementedModuleNames") {}
  putJsonArray("dependsOnModuleNames") {}
  putJsonArray("additionalVisibleModuleNames") {}
  put("productionOutputPath", JsonNull)
  put("testOutputPath", JsonNull)
  putJsonArray("sourceSetNames") {}
  put("isTestModule", false)
  put("externalProjectId", "${config.name}.main")
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

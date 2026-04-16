package kolt.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleMetadataTest {

    @Test
    fun parseJvmRedirectFromOkhttpModule() {
        val json = """
        {
          "formatVersion": "1.1",
          "component": {
            "group": "com.squareup.okhttp3",
            "module": "okhttp",
            "version": "5.3.2"
          },
          "variants": [
            {
              "name": "metadataApiElements",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "common"
              }
            },
            {
              "name": "jvmApiElements-published",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../okhttp-jvm/5.3.2/okhttp-jvm-5.3.2.module",
                "group": "com.squareup.okhttp3",
                "module": "okhttp-jvm",
                "version": "5.3.2"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertEquals("com.squareup.okhttp3", redirect?.group)
        assertEquals("okhttp-jvm", redirect?.module)
        assertEquals("5.3.2", redirect?.version)
    }

    @Test
    fun parseJvmRedirectReturnsNullForNonKmpLibrary() {
        val json = """
        {
          "formatVersion": "1.1",
          "component": {
            "group": "com.google.guava",
            "module": "guava",
            "version": "33.0.0-jre"
          },
          "variants": [
            {
              "name": "apiElements",
              "attributes": {
                "org.gradle.usage": "java-api"
              },
              "dependencies": [
                {
                  "group": "com.google.code.findbugs",
                  "module": "jsr305",
                  "version": { "requires": "3.0.2" }
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectReturnsNullForInvalidJson() {
        val redirect = parseJvmRedirect("not json")
        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectReturnsNullForEmptyVariants() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": []
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectSkipsJvmVariantWithoutAvailableAt() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "dependencies": [
                {
                  "group": "com.example",
                  "module": "lib",
                  "version": { "requires": "1.0" }
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectReturnsNullWhenJvmVariantWithoutAvailableAtComesFirst() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "dependencies": [
                {
                  "group": "org.jetbrains.kotlin",
                  "module": "kotlin-stdlib",
                  "version": { "requires": "2.1.0" }
                }
              ]
            },
            {
              "name": "jvmJUnitApiElements",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../kotlin-test-junit/2.1.0/kotlin-test-junit-2.1.0.module",
                "group": "org.jetbrains.kotlin",
                "module": "kotlin-test-junit",
                "version": "2.1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertNull(redirect)
    }

    @Test
    fun parseJvmRedirectReturnsNullWhenJvmVariantWithoutAvailableAtComesAfter() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmJUnitApiElements",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../kotlin-test-junit/2.1.0/kotlin-test-junit-2.1.0.module",
                "group": "org.jetbrains.kotlin",
                "module": "kotlin-test-junit",
                "version": "2.1.0"
              }
            },
            {
              "name": "jvmApiElements",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "dependencies": [
                {
                  "group": "org.jetbrains.kotlin",
                  "module": "kotlin-stdlib",
                  "version": { "requires": "2.1.0" }
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertNull(redirect)
    }

    @Test
    fun parseNativeRedirectExtractsLinuxX64AvailableAt() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "metadataApiElements",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "common"
              }
            },
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "artifactType": "org.jetbrains.kotlin.klib",
                "org.gradle.category": "library",
                "org.gradle.jvm.environment": "non-jvm",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../kotlinx-coroutines-core-linuxx64/1.9.0/kotlinx-coroutines-core-linuxx64-1.9.0.module",
                "group": "org.jetbrains.kotlinx",
                "module": "kotlinx-coroutines-core-linuxx64",
                "version": "1.9.0"
              }
            },
            {
              "name": "macosArm64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "macos_arm64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../kotlinx-coroutines-core-macosarm64/1.9.0/kotlinx-coroutines-core-macosarm64-1.9.0.module",
                "group": "org.jetbrains.kotlinx",
                "module": "kotlinx-coroutines-core-macosarm64",
                "version": "1.9.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseNativeRedirect(json, "linux_x64")

        assertEquals("org.jetbrains.kotlinx", redirect?.group)
        assertEquals("kotlinx-coroutines-core-linuxx64", redirect?.module)
        assertEquals("1.9.0", redirect?.version)
    }

    @Test
    fun parseNativeRedirectReturnsNullForDifferentTarget() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../lib-linuxx64/1.0/lib-linuxx64-1.0.module",
                "group": "com.example",
                "module": "lib-linuxx64",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseNativeRedirect(json, "macos_arm64")

        assertNull(redirect)
    }

    @Test
    fun parseNativeRedirectSkipsNonNativePlatformTypes() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../lib-jvm/1.0/lib-jvm-1.0.module",
                "group": "com.example",
                "module": "lib-jvm",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseNativeRedirect(json, "linux_x64")

        assertNull(redirect)
    }

    @Test
    fun parseNativeRedirectRequiresLibraryCategory() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64SourcesElements-published",
              "attributes": {
                "org.gradle.category": "documentation",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../lib-sources/1.0/lib-sources-1.0.module",
                "group": "com.example",
                "module": "lib-sources",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseNativeRedirect(json, "linux_x64")

        assertNull(redirect)
    }

    @Test
    fun parseNativeRedirectRequiresKotlinApiUsage() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64MetadataElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-metadata",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../lib-meta/1.0/lib-meta-1.0.module",
                "group": "com.example",
                "module": "lib-meta",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseNativeRedirect(json, "linux_x64")

        assertNull(redirect)
    }

    @Test
    fun parseNativeRedirectReturnsNullForInvalidJson() {
        assertNull(parseNativeRedirect("not json", "linux_x64"))
    }

    @Test
    fun parseNativeRedirectReturnsNullForEmptyVariants() {
        val json = """{"formatVersion": "1.1", "variants": []}"""

        assertNull(parseNativeRedirect(json, "linux_x64"))
    }

    @Test
    fun parseNativeArtifactExtractsKlibFileAndDependencies() {
        val json = """
        {
          "formatVersion": "1.1",
          "component": {
            "url": "../../kotlinx-coroutines-core/1.9.0/kotlinx-coroutines-core-1.9.0.module",
            "group": "org.jetbrains.kotlinx",
            "module": "kotlinx-coroutines-core",
            "version": "1.9.0"
          },
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "artifactType": "org.jetbrains.kotlin.klib",
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "dependencies": [
                {
                  "group": "org.jetbrains.kotlinx",
                  "module": "atomicfu",
                  "version": { "requires": "0.25.0" }
                },
                {
                  "group": "org.jetbrains.kotlin",
                  "module": "kotlin-stdlib",
                  "version": { "requires": "2.0.0" }
                }
              ],
              "files": [
                {
                  "name": "kotlinx-coroutines-core.klib",
                  "url": "kotlinx-coroutines-core-linuxx64-1.9.0.klib",
                  "size": 884378,
                  "sha256": "651d39f4ebfdd8218c30cc4c9239194dc23483a2ed8feae161749226f02f76fe"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val artifact = parseNativeArtifact(json, "linux_x64")

        assertEquals("kotlinx-coroutines-core-linuxx64-1.9.0.klib", artifact?.klibFileUrl)
        assertEquals("651d39f4ebfdd8218c30cc4c9239194dc23483a2ed8feae161749226f02f76fe", artifact?.klibSha256)
        val deps = artifact?.dependencies.orEmpty()
        assertEquals(2, deps.size)
        assertEquals("org.jetbrains.kotlinx", deps[0].group)
        assertEquals("atomicfu", deps[0].module)
        assertEquals("0.25.0", deps[0].version)
        assertEquals("kotlin-stdlib", deps[1].module)
        assertEquals("2.0.0", deps[1].version)
    }

    @Test
    fun parseNativeArtifactReturnsNullForDifferentTarget() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "files": [
                {
                  "name": "foo.klib",
                  "url": "foo-linuxx64-1.0.klib",
                  "sha256": "abc"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        assertNull(parseNativeArtifact(json, "macos_arm64"))
    }

    @Test
    fun parseNativeArtifactHandlesEmptyDependencies() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "files": [
                {
                  "name": "lib.klib",
                  "url": "lib-linuxx64-1.0.klib",
                  "sha256": "def"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val artifact = parseNativeArtifact(json, "linux_x64")

        assertEquals("lib-linuxx64-1.0.klib", artifact?.klibFileUrl)
        assertEquals("def", artifact?.klibSha256)
        assertEquals(0, artifact?.dependencies?.size)
    }

    @Test
    fun parseNativeArtifactReturnsNullWhenNoKlibFile() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "files": []
            }
          ]
        }
        """.trimIndent()

        assertNull(parseNativeArtifact(json, "linux_x64"))
    }

    @Test
    fun parseNativeArtifactReturnsNullForInvalidJson() {
        assertNull(parseNativeArtifact("not json", "linux_x64"))
    }

    @Test
    fun parseNativeArtifactPicksKlibFileAmongMultiple() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "files": [
                {
                  "name": "README.txt",
                  "url": "README.txt",
                  "sha256": "ignored"
                },
                {
                  "name": "lib.klib",
                  "url": "lib-linuxx64-2.0.klib",
                  "sha256": "good"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val artifact = parseNativeArtifact(json, "linux_x64")

        assertEquals("lib-linuxx64-2.0.klib", artifact?.klibFileUrl)
        assertEquals("good", artifact?.klibSha256)
    }

    @Test
    fun parseJvmRedirectPicksJvmRuntimeVariantToo() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmRuntimeElements-published",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../lib-jvm/1.0/lib-jvm-1.0.module",
                "group": "com.example",
                "module": "lib-jvm",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertEquals("com.example", redirect?.group)
        assertEquals("lib-jvm", redirect?.module)
        assertEquals("1.0", redirect?.version)
    }

    // Gradle Module Metadata attribute values can be non-string JSON primitives
    // (e.g. kotlinx-datetime:0.7.1-0.6.x-compat emits `"org.gradle.jvm.version": 8`).

    @Test
    fun parseNativeRedirectToleratesNumericAttributeValueOnUnrelatedVariant() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "java-api",
                "org.gradle.jvm.version": 8,
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../lib-jvm/1.0/lib-jvm-1.0.module",
                "group": "com.example",
                "module": "lib-jvm",
                "version": "1.0"
              }
            },
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../lib-linuxx64/1.0/lib-linuxx64-1.0.module",
                "group": "com.example",
                "module": "lib-linuxx64",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseNativeRedirect(json, "linux_x64")

        assertEquals("com.example", redirect?.group)
        assertEquals("lib-linuxx64", redirect?.module)
        assertEquals("1.0", redirect?.version)
    }

    @Test
    fun parseJvmRedirectToleratesNumericJvmVersionAttribute() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.jvm.version": 8,
                "org.gradle.usage": "java-api",
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../lib-jvm/1.0/lib-jvm-1.0.module",
                "group": "com.example",
                "module": "lib-jvm",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseJvmRedirect(json)

        assertEquals("com.example", redirect?.group)
        assertEquals("lib-jvm", redirect?.module)
        assertEquals("1.0", redirect?.version)
    }

    @Test
    fun parseNativeRedirectToleratesBooleanAttributeValue() {
        val json = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native",
                "com.example.experimental": true
              },
              "available-at": {
                "url": "../../lib-linuxx64/1.0/lib-linuxx64-1.0.module",
                "group": "com.example",
                "module": "lib-linuxx64",
                "version": "1.0"
              }
            }
          ]
        }
        """.trimIndent()

        val redirect = parseNativeRedirect(json, "linux_x64")

        assertEquals("com.example", redirect?.group)
        assertEquals("lib-linuxx64", redirect?.module)
        assertEquals("1.0", redirect?.version)
    }
}

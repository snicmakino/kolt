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
        // kotlin-test has jvmApiElements (no available-at) before jvmJUnitApiElements (with available-at).
        // When the library itself provides a JVM jar, we should NOT redirect.
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
        // Reverse of the above: available-at variant comes first, then a plain JVM variant.
        // Should still return null because the library provides its own JVM jar.
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

    // --- parseNativeRedirect ---

    @Test
    fun parseNativeRedirectExtractsLinuxX64AvailableAt() {
        // Based on kotlinx-coroutines-core:1.9.0 actual .module structure.
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
        // Only linuxX64 variant present; asked for macos_arm64 → null
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
        // jvm variants with native.target attribute should not match
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
        // A native variant in the documentation category should be skipped
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
        // Non-api usages (e.g. kotlin-metadata, kotlin-runtime) must be
        // skipped so we only resolve compile-time klibs.
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

    // --- parseNativeArtifact ---

    @Test
    fun parseNativeArtifactExtractsKlibFileAndDependencies() {
        // Based on kotlinx-coroutines-core-linuxx64:1.9.0 actual .module structure.
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
        // Variant matches but files[] has no .klib entry (edge case)
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
        // In practice .module files list only .klib; but if some future
        // variant lists multiple, we should pick the one ending in .klib.
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

    // --- numeric attribute values ---
    //
    // kotlinx-datetime:0.7.1-0.6.x-compat emits `"org.gradle.jvm.version": 8`
    // (integer, not string) on its JVM variants. Gradle Module Metadata does
    // not require attribute values to be strings, so the resolver must accept
    // numeric values — treating them as their string form is fine because all
    // comparisons are against known string literals like "jvm" or "linux_x64".

    @Test
    fun parseNativeRedirectToleratesNumericAttributeValueOnUnrelatedVariant() {
        // A JVM variant with an integer attribute sits alongside the native
        // variant we actually want. Previously Map<String, String> deserialization
        // threw on the integer and the whole document was rejected as invalid.
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
        // The JVM variant we actually want carries the integer attribute.
        // `org.gradle.jvm.version` is checked nowhere, but its mere presence
        // as a non-string broke decoding before the fix.
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
}

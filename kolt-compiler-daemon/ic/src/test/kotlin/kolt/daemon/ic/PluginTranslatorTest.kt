package kolt.daemon.ic

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Unit tests for the kolt.toml [kotlin.plugins] → freeArgs translation path.
// Issue #148 flipped the translator output from the structured
// `COMPILER_PLUGINS` shape (2.3.20-only) to CLI-style `-Xplugin=<jar>` strings
// fed through `CommonToolArguments.applyArgumentStrings`. The latter works
// against every 2.3.x BTA impl the daemon supports — see
// spike/bta-compat-138/REPORT.md.
//
// Plugin-id aliasing is no longer needed: kotlinc discovers the plugin via
// the jar's `META-INF/services/.../CompilerPluginRegistrar` service
// descriptor, so the translator just passes through resolved jar paths.
class PluginTranslatorTest {

    private val baseToml = """
        name = "demo"
        version = "0.1.0"

        [kotlin]
        version = "2.3.20"

        [build]
        target = "jvm"
        main = "demo.Main"
        sources = ["src/main/kotlin"]
    """.trimIndent()

    @Test
    fun `missing kolt_toml yields an empty plugin list`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-empty-")
        val args = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> error("resolver must not be called when no plugins section") },
        )
        assertTrue(args.isEmpty(), "no kolt.toml → no plugin args, got $args")
    }

    @Test
    fun `kolt_toml with no plugins section yields an empty list`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-no-section-")
        projectRoot.resolve("kolt.toml").writeText(baseToml)
        val args = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> error("resolver must not be called when plugins map is empty") },
        )
        assertTrue(args.isEmpty())
    }

    @Test
    fun `serialization plugin entry emits one -Xplugin= arg per resolved jar`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-serialization-")
        projectRoot.resolve("kolt.toml").writeText(
            baseToml + """

                [kotlin.plugins]
                serialization = true
            """.trimIndent(),
        )
        val fakeJar = Path.of("/fake/kotlinx-serialization-compiler-plugin.jar")
        val resolved = mutableListOf<String>()

        val args = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { name ->
                resolved += name
                if (name == "serialization") listOf(fakeJar) else emptyList()
            },
        )

        assertEquals(listOf("serialization"), resolved, "resolver should be asked once for the serialization plugin")
        assertEquals(listOf("-Xplugin=$fakeJar"), args)
    }

    @Test
    fun `disabled plugin entry is skipped entirely`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-disabled-")
        projectRoot.resolve("kolt.toml").writeText(
            baseToml + """

                [kotlin.plugins]
                serialization = false
            """.trimIndent(),
        )
        val args = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> error("resolver must not be called for disabled plugin") },
        )
        assertTrue(args.isEmpty())
    }

    // #65 native client wiring: allopen / noarg must also translate through
    // the same passthrough path. The translator is alias-agnostic now — it
    // only trusts the resolver — so this test pins the "resolver gets asked"
    // behaviour for all three known aliases.
    @Test
    fun `allopen plugin entry emits -Xplugin= passthrough args`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-allopen-")
        projectRoot.resolve("kolt.toml").writeText(
            baseToml + """

                [kotlin.plugins]
                allopen = true
            """.trimIndent(),
        )
        val fakeJar = Path.of("/fake/allopen-compiler-plugin.jar")
        val args = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { name -> if (name == "allopen") listOf(fakeJar) else emptyList() },
        )
        assertEquals(listOf("-Xplugin=$fakeJar"), args)
    }

    @Test
    fun `noarg plugin entry emits -Xplugin= passthrough args`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-noarg-")
        projectRoot.resolve("kolt.toml").writeText(
            baseToml + """

                [kotlin.plugins]
                noarg = true
            """.trimIndent(),
        )
        val fakeJar = Path.of("/fake/noarg-compiler-plugin.jar")
        val args = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { name -> if (name == "noarg") listOf(fakeJar) else emptyList() },
        )
        assertEquals(listOf("-Xplugin=$fakeJar"), args)
    }

    @Test
    fun `multi-jar resolution emits one arg per jar preserving resolver order`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-multijar-")
        projectRoot.resolve("kolt.toml").writeText(
            baseToml + """

                [kotlin.plugins]
                serialization = true
            """.trimIndent(),
        )
        val jars = listOf(
            Path.of("/fake/kotlinx-serialization-compiler-plugin.jar"),
            Path.of("/fake/kotlinx-serialization-compiler-plugin-embeddable.jar"),
        )
        val args = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> jars },
        )
        assertEquals(jars.map { "-Xplugin=$it" }, args)
    }

    @Test
    fun `empty resolver result for an enabled plugin produces no args`() {
        // Production upstream (`PluginJarFetcher` in the CLI) fails before the
        // daemon request is built if a plugin jar cannot be fetched, so the
        // resolver never returns an empty list for an enabled alias in
        // practice. This test pins the boundary case: if it does happen
        // (test mocks, future resolver rewiring) the translator emits no
        // `-Xplugin=` entries rather than a malformed one.
        val projectRoot = Files.createTempDirectory("plugin-translator-unresolved-")
        projectRoot.resolve("kolt.toml").writeText(
            baseToml + """

                [kotlin.plugins]
                serialization = true
            """.trimIndent(),
        )
        val args = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> emptyList() },
        )
        assertTrue(args.isEmpty())
    }
}

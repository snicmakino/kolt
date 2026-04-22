@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

// Proves that the classloader topology ADR 0019 §3 mandates is actually in
// force — i.e., an -impl-only class is reachable only from the child
// URLClassLoader, and not from the loader that faces daemon core. This is a
// test of the *boundary*, not of BtaIncrementalCompiler's compile path: if the
// isolation ever breaks, daemon core would transitively see
// @ExperimentalBuildToolsApi types through normal reflection, and issue #112
// acceptance criterion 2 ("zero `kotlin.build.tools` imports outside ic/")
// would lose its teeth.
//
// The probe class is `org.jetbrains.kotlin.buildtools.internal.jvm.JvmPlatformToolchainImpl`,
// which lives in `kotlin-build-tools-impl` and has no counterpart in -api.
// If a future compiler bump renames or removes it, the constant below is the
// only line that needs updating — and the test failure message will point at
// exactly that line.
class ClassloaderIsolationTest {

    private val btaImplJars: List<Path> = System.getProperty("kolt.ic.btaImplClasspath")
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { Path.of(it) }

    @Test
    fun `impl-only class is reachable from the child URLClassLoader`() {
        val (_, implLoader) = buildTopology()
        // Class.forName through the child loader must resolve the -impl class.
        // Using `Class.forName(name, initialize=false, loader)` avoids running
        // static initializers that might require a full BTA bootstrap.
        val loaded = Class.forName(IMPL_ONLY_PROBE, false, implLoader)
        assertNotNull(loaded, "expected $IMPL_ONLY_PROBE to be reachable from the child URLClassLoader")
    }

    @Test
    fun `impl-only class is NOT reachable from the daemon-core-facing parent`() {
        val (parentLoader, _) = buildTopology()
        // SharedApiClassesClassLoader exposes only org.jetbrains.kotlin.buildtools.api.*
        // to its parent chain. The -impl class must raise ClassNotFoundException
        // when probed through that loader — if it resolves, -impl has leaked up
        // past the isolation boundary and daemon core is no longer protected.
        assertFailsWith<ClassNotFoundException>(
            message = "$IMPL_ONLY_PROBE leaked past SharedApiClassesClassLoader — " +
                "the ADR 0019 §3 adapter invariant is broken",
        ) {
            Class.forName(IMPL_ONLY_PROBE, false, parentLoader)
        }
    }

    // Builds the exact topology BtaIncrementalCompiler.create constructs, but
    // returns both loaders so the test can probe each independently. Keeping
    // this in the test rather than exposing it from production code avoids
    // widening the adapter's public surface for a testability hook.
    @OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
    private fun buildTopology(): Pair<ClassLoader, ClassLoader> {
        require(btaImplJars.isNotEmpty()) {
            "kolt.ic.btaImplClasspath system property is empty — check :ic/build.gradle.kts test task config"
        }
        val parent = org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader()
        val child = java.net.URLClassLoader(
            btaImplJars.map { it.toUri().toURL() }.toTypedArray(),
            parent,
        )
        return parent to child
    }

    companion object {
        private const val IMPL_ONLY_PROBE =
            "org.jetbrains.kotlin.buildtools.internal.jvm.JvmPlatformToolchainImpl"
    }
}

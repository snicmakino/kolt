package kolt.build.daemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DaemonPreconditionsTest {

    private val paths = KoltPaths(home = "/fake/home")
    private val kotlincVersion = "2.1.0"
    private val absProject = "/fake/project"
    private val okJar = DaemonJarResolution.Resolved("/fake/libexec/daemon.jar", DaemonJarResolution.Source.Libexec)
    private val fakeJars = listOf("/fake/home/.kolt/toolchains/kotlinc/2.1.0/lib/a.jar")

    @Test
    fun resolveReturnsCompleteSetupWhenAllInputsPresent() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            resolveJavaBin = { "/fake/home/.kolt/toolchains/jdk/21/bin/java" },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
        )

        val setup = assertNotNull(result.get())
        assertEquals("/fake/home/.kolt/toolchains/jdk/21/bin/java", setup.javaBin)
        assertEquals("/fake/libexec/daemon.jar", setup.daemonJarPath)
        assertEquals(fakeJars, setup.compilerJars)
        val expectedHash = projectHashOf(absProject)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash", setup.daemonDir)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash/daemon.sock", setup.socketPath)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash/daemon.log", setup.logPath)
    }

    @Test
    fun bootstrapJdkMissingShortCircuits() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            resolveJavaBin = { null },
            resolveDaemonJar = { error("must not run after BootstrapJdkMissing") },
            listCompilerJars = { error("must not run after BootstrapJdkMissing") },
        )

        assertNull(result.get())
        val err = assertIs<DaemonPreconditionError.BootstrapJdkMissing>(result.getError())
        assertEquals("/fake/home/.kolt/toolchains/jdk/$BOOTSTRAP_JDK_VERSION", err.jdkInstallDir)
    }

    @Test
    fun daemonJarMissingShortCircuits() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            resolveJavaBin = { "/fake/java" },
            resolveDaemonJar = { DaemonJarResolution.NotFound },
            listCompilerJars = { error("must not run after DaemonJarMissing") },
        )

        assertEquals(DaemonPreconditionError.DaemonJarMissing, result.getError())
    }

    @Test
    fun compilerJarsMissingWhenDirAbsent() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            resolveJavaBin = { "/fake/java" },
            resolveDaemonJar = { okJar },
            listCompilerJars = { null },
        )

        val err = assertIs<DaemonPreconditionError.CompilerJarsMissing>(result.getError())
        assertEquals("/fake/home/.kolt/toolchains/kotlinc/2.1.0/lib", err.kotlincLibDir)
    }

    @Test
    fun compilerJarsMissingWhenDirEmpty() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            resolveJavaBin = { "/fake/java" },
            resolveDaemonJar = { okJar },
            listCompilerJars = { emptyList() },
        )

        assertIs<DaemonPreconditionError.CompilerJarsMissing>(result.getError())
    }

    @Test
    fun warningWordingCoversAllVariants() {
        assertEquals(
            "warning: bootstrap JDK not installed at /opt/jdks/21 — falling back to subprocess compile",
            formatDaemonPreconditionWarning(DaemonPreconditionError.BootstrapJdkMissing("/opt/jdks/21")),
        )
        assertEquals(
            "warning: kolt-compiler-daemon jar not found — falling back to subprocess compile",
            formatDaemonPreconditionWarning(DaemonPreconditionError.DaemonJarMissing),
        )
        assertEquals(
            "warning: no compiler jars found in /x/lib — falling back to subprocess compile",
            formatDaemonPreconditionWarning(DaemonPreconditionError.CompilerJarsMissing("/x/lib")),
        )
    }
}

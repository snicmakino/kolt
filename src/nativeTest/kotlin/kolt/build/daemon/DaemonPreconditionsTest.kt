package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kolt.tool.ToolchainError
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
    private val fakeBtaJars = listOf("/fake/libexec/kolt-bta-impl/kotlin-build-tools-impl.jar")
    private val okBtaImpl = BtaImplJarsResolution.Resolved(fakeBtaJars, BtaImplJarsResolution.Source.Libexec)

    @Test
    fun resolveReturnsCompleteSetupWhenAllInputsPresent() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            ensureJavaBin = { Ok("/fake/home/.kolt/toolchains/jdk/21/bin/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBtaImplJars = { okBtaImpl },
        )

        val setup = assertNotNull(result.get())
        assertEquals("/fake/home/.kolt/toolchains/jdk/21/bin/java", setup.javaBin)
        assertEquals("/fake/libexec/daemon.jar", setup.daemonJarPath)
        assertEquals(fakeJars, setup.compilerJars)
        assertEquals(fakeBtaJars, setup.btaImplJars)
        val expectedHash = projectHashOf(absProject)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash", setup.daemonDir)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash/daemon.sock", setup.socketPath)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash/daemon.log", setup.logPath)
    }

    @Test
    fun bootstrapJdkInstallFailedShortCircuits() {
        val installDir = "/fake/home/.kolt/toolchains/jdk/$BOOTSTRAP_JDK_VERSION"
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            ensureJavaBin = {
                Err(BootstrapJdkError(installDir, ToolchainError("network error downloading jdk 21: connection refused")))
            },
            resolveDaemonJar = { error("must not run after BootstrapJdkInstallFailed") },
            listCompilerJars = { error("must not run after BootstrapJdkInstallFailed") },
            resolveBtaImplJars = { error("must not run after BootstrapJdkInstallFailed") },
        )

        assertNull(result.get())
        val err = assertIs<DaemonPreconditionError.BootstrapJdkInstallFailed>(result.getError())
        assertEquals(installDir, err.jdkInstallDir)
        assertEquals("network error downloading jdk 21: connection refused", err.cause)
    }

    @Test
    fun daemonJarMissingShortCircuits() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { DaemonJarResolution.NotFound },
            listCompilerJars = { error("must not run after DaemonJarMissing") },
            resolveBtaImplJars = { error("must not run after DaemonJarMissing") },
        )

        assertEquals(DaemonPreconditionError.DaemonJarMissing, result.getError())
    }

    @Test
    fun compilerJarsMissingWhenDirAbsent() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { null },
            resolveBtaImplJars = { error("must not run after CompilerJarsMissing") },
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
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { emptyList() },
            resolveBtaImplJars = { error("must not run after CompilerJarsMissing") },
        )

        assertIs<DaemonPreconditionError.CompilerJarsMissing>(result.getError())
    }

    @Test
    fun btaImplJarsMissingShortCircuitsAfterCompilerJarsPass() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = kotlincVersion,
            absProjectPath = absProject,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBtaImplJars = { BtaImplJarsResolution.NotFound("/fake/libexec/kolt-bta-impl") },
        )

        val err = assertIs<DaemonPreconditionError.BtaImplJarsMissing>(result.getError())
        assertEquals("/fake/libexec/kolt-bta-impl", err.probedDir)
    }

    @Test
    fun warningWordingCoversAllVariants() {
        assertEquals(
            "warning: could not install bootstrap JDK at /opt/jdks/21 (HTTP 503) — falling back to subprocess compile",
            formatDaemonPreconditionWarning(
                DaemonPreconditionError.BootstrapJdkInstallFailed("/opt/jdks/21", "HTTP 503"),
            ),
        )
        assertEquals(
            "warning: kolt-compiler-daemon jar not found — falling back to subprocess compile",
            formatDaemonPreconditionWarning(DaemonPreconditionError.DaemonJarMissing),
        )
        assertEquals(
            "warning: no compiler jars found in /x/lib — falling back to subprocess compile",
            formatDaemonPreconditionWarning(DaemonPreconditionError.CompilerJarsMissing("/x/lib")),
        )
        assertEquals(
            "warning: kotlin-build-tools-impl jars not found in /x/libexec/kolt-bta-impl — falling back to subprocess compile",
            formatDaemonPreconditionWarning(
                DaemonPreconditionError.BtaImplJarsMissing("/x/libexec/kolt-bta-impl"),
            ),
        )
    }
}

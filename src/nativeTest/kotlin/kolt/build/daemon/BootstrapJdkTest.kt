package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kolt.tool.ToolchainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnsureBootstrapJavaBinTest {

    private val paths = KoltPaths(home = "/fake/home")
    private val expectedInstallDir = paths.jdkPath(BOOTSTRAP_JDK_VERSION)
    private val expectedJavaBin = paths.javaBin(BOOTSTRAP_JDK_VERSION)

    @Test
    fun alreadyInstalledReturnsPathWithoutInvokingInstaller() {
        val result = ensureBootstrapJavaBin(
            paths = paths,
            resolve = { expectedJavaBin },
            install = { _, _ -> error("must not install when JDK is already present") },
        )

        assertEquals(expectedJavaBin, result.get())
        assertNull(result.getError())
    }

    @Test
    fun missingThenInstalledReturnsPathAfterInstaller() {
        var resolveCalls = 0
        var installCalls = 0
        val result = ensureBootstrapJavaBin(
            paths = paths,
            resolve = {
                resolveCalls++
                if (resolveCalls == 1) null else expectedJavaBin
            },
            install = { version, _ ->
                installCalls++
                assertEquals(BOOTSTRAP_JDK_VERSION, version)
                Ok(Unit)
            },
        )

        assertEquals(expectedJavaBin, result.get())
        assertEquals(2, resolveCalls) // probe → install → re-probe
        assertEquals(1, installCalls)
    }

    @Test
    fun installFailureSurfacesAsBootstrapJdkError() {
        val cause = ToolchainError("network error downloading jdk 21: connection refused")
        val result = ensureBootstrapJavaBin(
            paths = paths,
            resolve = { null },
            install = { _, _ -> Err(cause) },
        )

        assertNull(result.get())
        val err = assertNotNull(result.getError())
        assertEquals(expectedInstallDir, err.jdkInstallDir)
        assertEquals(cause, err.cause)
    }

    @Test
    fun postInstallProbeFailureSurfacesAsBootstrapJdkError() {
        val result = ensureBootstrapJavaBin(
            paths = paths,
            resolve = { null },
            install = { _, _ -> Ok(Unit) },
        )

        val err = assertNotNull(result.getError())
        assertEquals(expectedInstallDir, err.jdkInstallDir)
        assertEquals(
            "java binary not found at $expectedJavaBin after installation",
            err.cause.message,
        )
    }
}

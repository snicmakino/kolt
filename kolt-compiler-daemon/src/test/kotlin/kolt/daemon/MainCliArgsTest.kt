package kolt.daemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Pins the daemon's CLI parser shape so a future contributor cannot silently
// drop `--compiler-jars` or `--bta-impl-jars`. Both flags are load-bearing at
// the native-client spawn boundary: `DaemonCompilerBackend.spawnArgv()` passes
// them on every spawn, and removing either at the daemon-side parser without
// updating the spawn argv would turn every fresh daemon launch into a hard
// CliError.MissingXyz — silent on unit tests, visible only as a field regression.
//
// `--compiler-jars` is intentionally retained after the B-2a refactor even
// though daemon-core code no longer loads kotlin-compiler-embeddable itself
// (the reflective `SharedCompilerHost` was removed in commit ad05de8). Phase
// B-2c work on plugin plumbing may reuse the flag for compiler plugin jars,
// and dropping it now would force a back-incompatible native-client change
// the moment that requirement materialises. This test is the explicit record
// of that intent.
class MainCliArgsTest {

    @Test
    fun parseArgsAcceptsAllThreeRequiredFlags() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/kolt-daemon.sock",
                "--compiler-jars", "/kt/lib/a.jar:/kt/lib/b.jar",
                "--bta-impl-jars", "/bta/impl/kotlin-build-tools-impl.jar",
            ),
        )
        val cli = assertNotNull(result.get())
        assertEquals("/tmp/kolt-daemon.sock", cli.socketPath.toString())
        assertEquals(2, cli.compilerJars.size)
        assertEquals(1, cli.btaImplJars.size)
    }

    @Test
    fun compilerJarsFlagIsRequiredEvenThoughDaemonDoesNotLoadItDirectly() {
        // Regression guard for the "drop --compiler-jars because it is dead"
        // temptation. The field is dead inside daemon core after ad05de8 but
        // the flag must stay on the wire so the native client does not break.
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/kolt-daemon.sock",
                "--bta-impl-jars", "/bta/impl/x.jar",
            ),
        )
        assertEquals(CliError.MissingCompilerJars, result.getError())
    }

    @Test
    fun btaImplJarsFlagIsRequired() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/kolt-daemon.sock",
                "--compiler-jars", "/kt/lib/a.jar",
            ),
        )
        assertEquals(CliError.MissingBtaImplJars, result.getError())
    }

    @Test
    fun emptyCompilerJarsValueRejected() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/kolt-daemon.sock",
                "--compiler-jars", "",
                "--bta-impl-jars", "/bta/impl/x.jar",
            ),
        )
        assertEquals(CliError.EmptyCompilerJars, result.getError())
    }

    @Test
    fun emptyBtaImplJarsValueRejected() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/kolt-daemon.sock",
                "--compiler-jars", "/kt/lib/a.jar",
                "--bta-impl-jars", "",
            ),
        )
        assertEquals(CliError.EmptyBtaImplJars, result.getError())
    }

    @Test
    fun unknownFlagRejected() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/s",
                "--compiler-jars", "/a.jar",
                "--bta-impl-jars", "/b.jar",
                "--frobnicate",
            ),
        )
        val err = assertNotNull(result.getError()) as CliError.UnknownFlag
        assertEquals("--frobnicate", err.flag)
    }
}

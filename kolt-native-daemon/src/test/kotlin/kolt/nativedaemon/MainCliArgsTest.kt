package kolt.nativedaemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Pins the native daemon's CLI parser shape. All three flags — --socket,
// --konanc-jar, --konan-home — are load-bearing at the native client spawn
// boundary (arrives in PR 3). Removing any of them at the daemon without
// updating the spawn argv would turn every fresh daemon launch into a hard
// CliError.MissingXyz, which is silent at unit-test time and only visible as
// a field regression.
class MainCliArgsTest {

    @Test
    fun parseAcceptsAllThreeRequiredFlags() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/kolt-native-daemon.sock",
                "--konanc-jar", "/home/u/.konan/kotlin-native/konan/lib/kotlin-native-compiler-embeddable.jar",
                "--konan-home", "/home/u/.konan/kotlin-native",
            ),
        )

        val cli = assertNotNull(result.get())
        assertEquals(Path.of("/tmp/kolt-native-daemon.sock"), cli.socketPath)
        assertEquals(Path.of("/home/u/.konan/kotlin-native/konan/lib/kotlin-native-compiler-embeddable.jar"), cli.konancJar)
        assertEquals(Path.of("/home/u/.konan/kotlin-native"), cli.konanHome)
    }

    @Test
    fun socketFlagIsRequired() {
        val result = parseArgs(
            arrayOf(
                "--konanc-jar", "/a.jar",
                "--konan-home", "/k",
            ),
        )
        assertEquals(CliError.MissingSocket, result.getError())
    }

    @Test
    fun konancJarFlagIsRequired() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/s",
                "--konan-home", "/k",
            ),
        )
        assertEquals(CliError.MissingKonancJar, result.getError())
    }

    @Test
    fun konanHomeFlagIsRequired() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/s",
                "--konanc-jar", "/a.jar",
            ),
        )
        assertEquals(CliError.MissingKonanHome, result.getError())
    }

    @Test
    fun unknownFlagRejected() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/s",
                "--konanc-jar", "/a.jar",
                "--konan-home", "/k",
                "--frobnicate",
            ),
        )
        val err = assertNotNull(result.getError()) as CliError.UnknownFlag
        assertEquals("--frobnicate", err.flag)
    }

    @Test
    fun emptyValueForSocketRejected() {
        val result = parseArgs(
            arrayOf(
                "--socket", "",
                "--konanc-jar", "/a.jar",
                "--konan-home", "/k",
            ),
        )
        assertEquals(CliError.EmptySocket, result.getError())
    }

    @Test
    fun emptyValueForKonancJarRejected() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/s",
                "--konanc-jar", "",
                "--konan-home", "/k",
            ),
        )
        assertEquals(CliError.EmptyKonancJar, result.getError())
    }

    @Test
    fun emptyValueForKonanHomeRejected() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/s",
                "--konanc-jar", "/a.jar",
                "--konan-home", "",
            ),
        )
        assertEquals(CliError.EmptyKonanHome, result.getError())
    }

    // The three tests below pin the current behaviour where a flag at the
    // end of argv with no following token collapses to MissingXyz (via
    // `getOrNull(i+1)` returning null) rather than a dedicated
    // "value-missing" variant. The JVM daemon parser has the same shape —
    // adding these tests here prevents a future refactor from silently
    // regressing the current diagnosability floor.

    @Test
    fun socketFlagAtEndOfArgvCollapsesToMissingSocket() {
        val result = parseArgs(arrayOf("--socket"))
        assertEquals(CliError.MissingSocket, result.getError())
    }

    @Test
    fun konancJarFlagAtEndOfArgvCollapsesToMissingKonancJar() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/s",
                "--konanc-jar",
            ),
        )
        assertEquals(CliError.MissingKonancJar, result.getError())
    }

    @Test
    fun konanHomeFlagAtEndOfArgvCollapsesToMissingKonanHome() {
        val result = parseArgs(
            arrayOf(
                "--socket", "/tmp/s",
                "--konanc-jar", "/a.jar",
                "--konan-home",
            ),
        )
        assertEquals(CliError.MissingKonanHome, result.getError())
    }
}

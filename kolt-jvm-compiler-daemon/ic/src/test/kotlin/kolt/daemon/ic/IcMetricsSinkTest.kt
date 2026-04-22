package kolt.daemon.ic

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Pins the wire shape of the stderr metrics sink so a future log-parser
// (a follow-up `kolt doctor` or a Phase B smoke test) can rely on the
// format. One structured line per metric, distinctive prefix so the
// daemon's other stderr noise does not collide.
class IcMetricsSinkTest {

    @Test
    fun `NoopIcMetricsSink discards every record call`() {
        // The point of Noop is that there is nothing to assert *on the
        // output*; the assertion is that it does not throw and can be
        // called freely from non-test contexts (default constructor
        // parameter on BtaIncrementalCompiler and SelfHealingIncrementalCompiler).
        NoopIcMetricsSink.record("ic.success")
        NoopIcMetricsSink.record("ic.self_heal", 3)
    }

    @Test
    fun `StderrIcMetricsSink emits one kolt-ic-metric line per record`() {
        val buffer = ByteArrayOutputStream()
        val sink = StderrIcMetricsSink(PrintStream(buffer, true, Charsets.UTF_8))

        sink.record("ic.success")
        sink.record("ic.self_heal", 1)
        sink.record("ic.wall_ms", 412)

        val lines = buffer.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(3, lines.size)
        assertEquals("""kolt-ic-metric {"name":"ic.success","value":1}""", lines[0])
        assertEquals("""kolt-ic-metric {"name":"ic.self_heal","value":1}""", lines[1])
        assertEquals("""kolt-ic-metric {"name":"ic.wall_ms","value":412}""", lines[2])
    }

    @Test
    fun `StderrIcMetricsSink tolerates names with inner dots and underscores`() {
        val buffer = ByteArrayOutputStream()
        val sink = StderrIcMetricsSink(PrintStream(buffer, true, Charsets.UTF_8))

        sink.record("ic.bta.compiled_files", 7)

        assertTrue(
            buffer.toString(Charsets.UTF_8).contains(
                """kolt-ic-metric {"name":"ic.bta.compiled_files","value":7}""",
            ),
        )
    }
}

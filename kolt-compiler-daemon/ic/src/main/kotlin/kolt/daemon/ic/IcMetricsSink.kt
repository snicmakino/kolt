package kolt.daemon.ic

import java.io.PrintStream

// Observation sink for adapter-level incremental-compile events defined
// by ADR 0019 §7. The adapter records counters like `ic.success`,
// `ic.self_heal`, and `ic.fallback_to_full` here; daemon-core code is
// unaware of the sink (it only sees `Ok(IcResponse)` / `Err(IcError)`).
//
// The wire shape is one line per record on the daemon's stderr stream,
// formatted as `kolt-ic-metric {"name":"...","value":...}`. A future
// `kolt doctor` subcommand parses these lines back out when asked what
// the daemon has been doing; a Phase B smoke test can assert the same
// shape. Single-line JSON is deliberate so log-aggregators can frame on
// newline without needing to know anything about multi-line events.
//
// Naming conventions used by the adapter:
//
//   ic.success             — one Ok(IcResponse) crossed the adapter
//   ic.fallback_to_full    — this compile started from an empty
//                            workingDir (cold path or post-self-heal)
//   ic.self_heal           — SelfHealingIncrementalCompiler fired
//                            wipe+retry
//   ic.wall_ms             — last compile wall time, value = ms
//   ic.bta.<bta-name>      — raw BTA BuildMetricsCollector value
//                            forwarded verbatim, sanitised for JSON
//
// Counter names must not contain `"` or `\`; the adapter only emits
// fixed-prefix strings, so this is enforced by construction rather
// than escaping at the sink layer.
interface IcMetricsSink {
    fun record(name: String, value: Long = 1L)
}

object NoopIcMetricsSink : IcMetricsSink {
    override fun record(name: String, value: Long) { /* intentional no-op */ }
}

class StderrIcMetricsSink(
    // Defaults to `System.err` so the daemon's normal stderr stream
    // carries the metric lines. Tests inject a ByteArrayOutputStream-
    // backed PrintStream to observe the wire.
    private val stderr: PrintStream = System.err,
) : IcMetricsSink {
    override fun record(name: String, value: Long) {
        stderr.println("""kolt-ic-metric {"name":"$name","value":$value}""")
    }
}

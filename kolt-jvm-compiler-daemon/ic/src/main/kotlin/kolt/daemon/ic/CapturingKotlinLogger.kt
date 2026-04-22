@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger

// Captures BTA's compile-time log output. `error`-level lines are retained
// for folding into `IcError.CompilationFailed.messages`; `warn` / `info` /
// `debug` / `lifecycle` lines are forwarded to the metrics sink as coarse
// counters so log volume stays observable without polluting the user's
// compile-error stream.
//
// Throwable handling (B-2c): on an `error` call with an attached throwable,
// the captured message retains one line of stack trace in addition to the
// throwable's message. The B-2b implementation kept only `throwable.message`,
// which threw away the frame a user needs to find BTA-internal failures.
// Keeping the full trace would flood the user's stderr; one line is the
// compromise between "silent" and "noisy".
internal class CapturingKotlinLogger(
    private val metrics: IcMetricsSink,
) : KotlinLogger {
    private val errors: MutableList<String> = mutableListOf()

    override val isDebugEnabled: Boolean get() = false

    override fun error(msg: String, throwable: Throwable?) {
        errors.add(formatLine(msg, throwable))
        metrics.record(BtaIncrementalCompiler.METRIC_LOG_ERROR)
    }

    override fun warn(msg: String, throwable: Throwable?) {
        metrics.record(BtaIncrementalCompiler.METRIC_LOG_WARN)
    }

    override fun info(msg: String) {
        metrics.record(BtaIncrementalCompiler.METRIC_LOG_INFO)
    }

    override fun debug(msg: String) {
        // Not recorded: debug is too noisy to count per line and
        // `isDebugEnabled = false` keeps BTA from even producing it.
    }

    override fun lifecycle(msg: String) {
        metrics.record(BtaIncrementalCompiler.METRIC_LOG_LIFECYCLE)
    }

    fun errorMessages(): List<String> = errors.toList()

    private fun formatLine(msg: String, throwable: Throwable?): String {
        if (throwable == null) return msg
        // A pathological `Throwable` subclass can override `toString()` or
        // `getMessage()` and throw. `BtaIncrementalCompiler.compile` wraps
        // the whole compile call in a `Throwable` catch that converts any
        // such throw into `IcError.InternalError`, so it would be caught —
        // but a throw from inside `executeOperation`'s logger call still
        // aborts the compile mid-flight. `runCatching` here keeps the
        // adapter-level error path in charge: on failure we fall back to
        // the throwable's fully-qualified class name, which is always
        // safe because `Class.getName()` cannot throw.
        return runCatching {
            val header = "$msg: ${throwable.toString()}"
            val firstFrame = throwable.stackTrace.firstOrNull() ?: return header
            "$header\n\tat $firstFrame"
        }.getOrElse { "$msg: ${throwable.javaClass.name}" }
    }
}

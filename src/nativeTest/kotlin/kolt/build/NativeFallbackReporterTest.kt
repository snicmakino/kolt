package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeFallbackReporterTest {

    private fun capture(err: NativeCompileError): List<String> {
        val messages = mutableListOf<String>()
        reportNativeFallback(err) { messages += it }
        return messages
    }

    @Test
    fun backendUnavailableVariantsEmitGenericWarning() {
        for (err in listOf(
            NativeCompileError.BackendUnavailable.ForkFailed,
            NativeCompileError.BackendUnavailable.WaitFailed,
            NativeCompileError.BackendUnavailable.SignalKilled,
            NativeCompileError.BackendUnavailable.PopenFailed,
        )) {
            val out = capture(err).single()
            assertTrue(
                out.startsWith("warning:") && out.contains("native compiler daemon unavailable"),
                "unexpected message for $err: $out",
            )
        }
    }

    @Test
    fun backendUnavailableOtherIncludesDetail() {
        val out = capture(
            NativeCompileError.BackendUnavailable.Other("connect refused"),
        ).single()
        assertTrue(out.contains("connect refused"), "expected detail in warning: $out")
    }

    @Test
    fun internalMisuseEscalatesToError() {
        val out = capture(NativeCompileError.InternalMisuse("socket path too long")).single()
        assertTrue(out.startsWith("error:"), "expected error-level, got: $out")
        assertTrue(out.contains("socket path too long"))
    }

    @Test
    fun compilationFailedAndNoCommandAreSilent() {
        // Real compile errors pass through without a fallback notice — the
        // caller has already seen the konanc diagnostics. NoCommand is
        // non-eligible and also silent.
        assertEquals(emptyList(), capture(NativeCompileError.CompilationFailed(exitCode = 1, stderr = "error: ...")))
        assertEquals(emptyList(), capture(NativeCompileError.NoCommand))
    }
}

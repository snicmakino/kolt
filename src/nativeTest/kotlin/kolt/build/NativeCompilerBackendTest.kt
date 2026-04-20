package kolt.build

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Pins `isNativeFallbackEligible` — the single point of truth for which
// error variants allow FallbackNativeCompilerBackend to retry on the
// subprocess path. Mirrors `isFallbackEligible` on the JVM side.
class NativeCompilerBackendTest {

    @Test
    fun backendUnavailableVariantsAreFallbackEligible() {
        assertTrue(isNativeFallbackEligible(NativeCompileError.BackendUnavailable.ForkFailed))
        assertTrue(isNativeFallbackEligible(NativeCompileError.BackendUnavailable.WaitFailed))
        assertTrue(isNativeFallbackEligible(NativeCompileError.BackendUnavailable.SignalKilled))
        assertTrue(isNativeFallbackEligible(NativeCompileError.BackendUnavailable.PopenFailed))
        assertTrue(isNativeFallbackEligible(NativeCompileError.BackendUnavailable.Other("connect refused")))
    }

    @Test
    fun internalMisuseIsFallbackEligible() {
        assertTrue(isNativeFallbackEligible(NativeCompileError.InternalMisuse("socket path too long")))
    }

    @Test
    fun compilationFailedIsNotFallbackEligible() {
        // Real konanc compilation errors pass through untouched — the
        // subprocess path would fail the same way, and retrying adds a
        // cold-JVM tax.
        assertFalse(
            isNativeFallbackEligible(
                NativeCompileError.CompilationFailed(
                    exitCode = 1,
                    stderr = "error: unresolved reference: foo",
                ),
            ),
        )
    }

    @Test
    fun noCommandIsNotFallbackEligible() {
        // If there's no konanc binary to fall back to, retrying is pointless.
        assertFalse(isNativeFallbackEligible(NativeCompileError.NoCommand))
    }
}

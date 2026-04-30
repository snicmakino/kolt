package kolt.nativedaemon.compiler

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

// Exercises the NativeCompiler contract shape. The production implementation
// (ReflectiveK2NativeCompiler) is covered separately. This test pins the
// success / internal-error split: non-zero `exitCode` is a normal
// *compilation* outcome (source errors), not an adapter-level failure, so it
// rides Ok side of the Result. Only truly unexpected invocation failures
// (e.g., reflection threw) become NativeCompileError.
class NativeCompilerTest {

  @Test
  fun `compile returns Ok with exitCode 0 and empty stderr on success`() {
    val compiler = FakeCompiler { args ->
      assertEquals(listOf("-target", "linux_x64", "A.kt"), args)
      Ok(NativeCompileOutcome(exitCode = 0, stderr = ""))
    }

    val result = compiler.compile(listOf("-target", "linux_x64", "A.kt"))

    val outcome = assertNotNull(result.get())
    assertEquals(0, outcome.exitCode)
    assertEquals("", outcome.stderr)
  }

  @Test
  fun `non-zero exitCode is an Ok outcome not an error`() {
    val compiler = FakeCompiler {
      Ok(NativeCompileOutcome(exitCode = 1, stderr = "error: unresolved reference: foo"))
    }

    val result = compiler.compile(listOf("-target", "linux_x64", "A.kt"))

    val outcome = assertNotNull(result.get())
    assertEquals(1, outcome.exitCode)
    assertEquals("error: unresolved reference: foo", outcome.stderr)
  }

  @Test
  fun `reflective invocation failure surfaces as InvocationFailed`() {
    val boom = RuntimeException("K2Native.exec threw")
    val compiler = FakeCompiler { Err(NativeCompileError.InvocationFailed(boom)) }

    val err = compiler.compile(emptyList()).getError()

    assertNotNull(err)
    assertIs<NativeCompileError.InvocationFailed>(err)
    assertEquals(boom, err.cause)
  }

  private class FakeCompiler(
    private val handler: (List<String>) -> Result<NativeCompileOutcome, NativeCompileError>
  ) : NativeCompiler {
    override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> =
      handler(args)
  }
}

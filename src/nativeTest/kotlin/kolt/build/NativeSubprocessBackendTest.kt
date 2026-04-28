package kolt.build

import com.github.michaelbull.result.getError
import kolt.infra.ProcessError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

class NativeSubprocessBackendTest {

  @Test
  fun argvPrependsKonancBin() {
    val argv =
      nativeSubprocessArgv(
        konancBin = "/opt/konanc",
        args = listOf("-target", "linux_x64", "src/Main.kt", "-p", "library", "-o", "build/m-klib"),
      )

    assertEquals(
      listOf(
        "/opt/konanc",
        "-target",
        "linux_x64",
        "src/Main.kt",
        "-p",
        "library",
        "-o",
        "build/m-klib",
      ),
      argv,
    )
  }

  @Test
  fun argvAcceptsEmptyArgs() {
    assertEquals(listOf("/opt/konanc"), nativeSubprocessArgv("/opt/konanc", emptyList()))
  }

  @Test
  fun processErrorForkFailedMapsToBackendUnavailable() {
    assertEquals(
      NativeCompileError.BackendUnavailable.ForkFailed,
      mapProcessErrorToNativeCompileError(ProcessError.ForkFailed),
    )
  }

  @Test
  fun processErrorWaitFailedMapsToBackendUnavailable() {
    assertEquals(
      NativeCompileError.BackendUnavailable.WaitFailed,
      mapProcessErrorToNativeCompileError(ProcessError.WaitFailed),
    )
  }

  @Test
  fun processErrorPopenFailedMapsToBackendUnavailable() {
    assertEquals(
      NativeCompileError.BackendUnavailable.PopenFailed,
      mapProcessErrorToNativeCompileError(ProcessError.PopenFailed),
    )
  }

  @Test
  fun processErrorSignalKilledMapsToBackendUnavailable() {
    assertEquals(
      NativeCompileError.BackendUnavailable.SignalKilled,
      mapProcessErrorToNativeCompileError(ProcessError.SignalKilled),
    )
  }

  @Test
  fun processErrorNonZeroExitMapsToCompilationFailed() {
    val err = mapProcessErrorToNativeCompileError(ProcessError.NonZeroExit(exitCode = 1))
    val compilationFailed = assertIs<NativeCompileError.CompilationFailed>(err)
    assertEquals(1, compilationFailed.exitCode)
    // stderr is empty because the subprocess inherits the parent's fd;
    // the caller never captures it. Matches the JVM subprocess backend.
    assertEquals("", compilationFailed.stderr)
  }

  @Test
  fun processErrorEmptyArgsMapsToNoCommand() {
    assertEquals(
      NativeCompileError.NoCommand,
      mapProcessErrorToNativeCompileError(ProcessError.EmptyArgs),
    )
  }

  // run_konan resolves `java` through `$JAVA_HOME/bin/java` first; the
  // backend has to setenv that in the child or a clean host with no system
  // Java exits 127 on `java: command not found` (#285).
  @Test
  fun compileSetsJavaHomeWhenSupplied() {
    val backend = NativeSubprocessBackend(konancBin = "sh", javaHome = "/managed/jdk/home")
    val error =
      backend.compile(listOf("-c", "[ \"\$JAVA_HOME\" = \"/managed/jdk/home\" ]")).getError()
    if (error != null) {
      kotlin.test.fail("expected sh to see JAVA_HOME, got error: $error")
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  @Test
  fun compileLeavesParentJavaHomeUntouchedWhenUnset() {
    val sentinel = "/kolt-test-sentinel/home"
    val previous = platform.posix.getenv("JAVA_HOME")?.toKString()
    platform.posix.setenv("JAVA_HOME", sentinel, 1)
    try {
      val backend = NativeSubprocessBackend(konancBin = "sh", javaHome = null)
      val error = backend.compile(listOf("-c", "[ \"\$JAVA_HOME\" = \"$sentinel\" ]")).getError()
      if (error != null) {
        kotlin.test.fail("expected child to inherit parent JAVA_HOME, got: $error")
      }
    } finally {
      if (previous != null) platform.posix.setenv("JAVA_HOME", previous, 1)
      else platform.posix.unsetenv("JAVA_HOME")
    }
  }
}

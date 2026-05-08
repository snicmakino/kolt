package kolt.build

import com.github.michaelbull.result.getError
import kolt.infra.ProcessError
import kolt.infra.output.ColorPolicy
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
  fun argvOmitsJvmFlagsOnPreJdk23() {
    val argv =
      nativeSubprocessArgv(
        konancBin = "/opt/konanc",
        args = listOf("src/Main.kt", "-o", "build/m"),
        jdkMajorVersion = 22,
      )

    assertEquals(listOf("/opt/konanc", "src/Main.kt", "-o", "build/m"), argv)
  }

  @Test
  fun argvOmitsJvmFlagsWhenJdkVersionUnknown() {
    val argv =
      nativeSubprocessArgv(
        konancBin = "/opt/konanc",
        args = listOf("src/Main.kt"),
        jdkMajorVersion = null,
      )

    assertEquals(listOf("/opt/konanc", "src/Main.kt"), argv)
  }

  @Test
  fun argvAddsJvmFlagsAfterKonancBinOnJdk23() {
    val argv =
      nativeSubprocessArgv(
        konancBin = "/opt/konanc",
        args = listOf("src/Main.kt", "-o", "build/m"),
        jdkMajorVersion = 23,
      )

    assertEquals(
      listOf(
        "/opt/konanc",
        "-J--sun-misc-unsafe-memory-access=allow",
        "-J--enable-native-access=ALL-UNNAMED",
        "src/Main.kt",
        "-o",
        "build/m",
      ),
      argv,
    )
  }

  @Test
  fun argvAddsJvmFlagsOnJdk25() {
    val argv =
      nativeSubprocessArgv(
        konancBin = "/opt/konanc",
        args = listOf("src/Main.kt"),
        jdkMajorVersion = 25,
      )

    assertEquals(
      listOf(
        "/opt/konanc",
        "-J--sun-misc-unsafe-memory-access=allow",
        "-J--enable-native-access=ALL-UNNAMED",
        "src/Main.kt",
      ),
      argv,
    )
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

  // When ColorPolicy disables stderr color, the child konanc must see
  // NO_COLOR=1 in its env so the underlying compiler emits plain
  // (non-ANSI) diagnostics. Mirrors the kotlinc-side contract.
  @Test
  fun compileInjectsNoColorEnvWhenColorPolicyDisablesStderr() {
    val backend = NativeSubprocessBackend(konancBin = "sh", colorPolicy = { ColorPolicy.Never })
    val error = backend.compile(listOf("-c", "[ \"\$NO_COLOR\" = \"1\" ]")).getError()
    if (error != null) {
      kotlin.test.fail("expected sh to see NO_COLOR=1, got error: $error")
    }
  }

  // When ColorPolicy enables color, kolt must NOT inject NO_COLOR — the child
  // should inherit parent env verbatim so konanc keeps emitting ANSI codes
  // for fd-inherit pass-through.
  @OptIn(ExperimentalForeignApi::class)
  @Test
  fun compileDoesNotInjectNoColorEnvWhenColorPolicyAllowsStderr() {
    val previous = platform.posix.getenv("NO_COLOR")?.toKString()
    platform.posix.unsetenv("NO_COLOR")
    try {
      val backend = NativeSubprocessBackend(konancBin = "sh", colorPolicy = { ColorPolicy.Always })
      val error = backend.compile(listOf("-c", "[ -z \"\$NO_COLOR\" ]")).getError()
      if (error != null) {
        kotlin.test.fail("expected sh to see NO_COLOR unset, got error: $error")
      }
    } finally {
      if (previous != null) platform.posix.setenv("NO_COLOR", previous, 1)
    }
  }

  // JAVA_HOME injection must still work when NO_COLOR is also injected — both
  // env entries land in the child without one displacing the other.
  @Test
  fun compileSetsBothJavaHomeAndNoColorWhenColorDisabledAndJavaHomeSupplied() {
    val backend =
      NativeSubprocessBackend(
        konancBin = "sh",
        javaHome = "/managed/jdk/home",
        colorPolicy = { ColorPolicy.Never },
      )
    val error =
      backend
        .compile(
          listOf("-c", "[ \"\$JAVA_HOME\" = \"/managed/jdk/home\" ] && [ \"\$NO_COLOR\" = \"1\" ]")
        )
        .getError()
    if (error != null) {
      kotlin.test.fail("expected sh to see both JAVA_HOME and NO_COLOR, got error: $error")
    }
  }
}

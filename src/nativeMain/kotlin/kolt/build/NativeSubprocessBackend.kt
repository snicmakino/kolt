package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.infra.ProcessError
import kolt.infra.executeCommand
import kolt.infra.output.ColorPolicy
import kolt.infra.output.Stream

// Baseline native backend: invokes `konanc` as a subprocess. This is the
// pre-ADR-0024 behaviour kept intact so FallbackNativeCompilerBackend can
// retry here when the daemon is unreachable. stderr is not captured —
// konanc inherits the parent process's stderr fd, same as the JVM-side
// SubprocessCompilerBackend.
//
// run_konan is a shell wrapper that resolves `java` through `$JAVA_HOME`
// first and falls back to PATH; without either it exits 127 on
// `java: command not found`. `javaHome` injects the env var into the child
// so the wrapper does not depend on the host's PATH (#285).
//
// `colorPolicy` is a seam so tests drive both branches without re-installing
// the global ColorPolicy. Production callers leave it at the default and the
// CLI's startup `ColorPolicy.install` governs behavior.
class NativeSubprocessBackend(
  private val konancBin: String,
  private val javaHome: String? = null,
  private val jdkMajorVersion: Int? = null,
  private val colorPolicy: () -> ColorPolicy = ColorPolicy::current,
) : NativeCompilerBackend {

  override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> {
    val argv = nativeSubprocessArgv(konancBin, args, jdkMajorVersion)
    val env = buildMap {
      if (javaHome != null) put("JAVA_HOME", javaHome)
      // Signal konanc to emit plain (non-ANSI) diagnostics when kolt's color
      // is off. Pass-through is the inherit-default — we set nothing when
      // color is on so the child observes parent env verbatim.
      if (!colorPolicy().shouldColor(Stream.Stderr)) put("NO_COLOR", "1")
    }
    executeCommand(argv, env).getOrElse { err ->
      return Err(mapProcessErrorToNativeCompileError(err))
    }
    return Ok(NativeCompileOutcome(stderr = ""))
  }
}

// `args` are the konanc argv AFTER the binary (ADR 0024 §4); the subprocess
// path prepends the binary here. The daemon path forwards `args` as-is.
//
// `-J<flag>` items are stripped of the `-J` prefix by run_konan and forwarded
// to the JVM (`java_args` in run_konan). Two flags silence konanc-side
// warnings on JDK 23+: `--sun-misc-unsafe-memory-access=allow` (JEP 498,
// fired by intellij-util's `objectFieldOffset` use) and
// `--enable-native-access=ALL-UNNAMED` (jansi's `System::load`). Both flags
// are unrecognised on JDK <23, so gate before adding.
internal fun nativeSubprocessArgv(
  konancBin: String,
  args: List<String>,
  jdkMajorVersion: Int? = null,
): List<String> = buildList {
  add(konancBin)
  if (jdkMajorVersion != null && jdkMajorVersion >= 23) {
    add("-J--sun-misc-unsafe-memory-access=allow")
    add("-J--enable-native-access=ALL-UNNAMED")
  }
  addAll(args)
}

internal fun mapProcessErrorToNativeCompileError(error: ProcessError): NativeCompileError =
  when (error) {
    is ProcessError.EmptyArgs -> NativeCompileError.NoCommand
    is ProcessError.ForkFailed -> NativeCompileError.BackendUnavailable.ForkFailed
    is ProcessError.WaitFailed -> NativeCompileError.BackendUnavailable.WaitFailed
    is ProcessError.PopenFailed -> NativeCompileError.BackendUnavailable.PopenFailed
    is ProcessError.NonZeroExit ->
      NativeCompileError.CompilationFailed(exitCode = error.exitCode, stderr = "")
    is ProcessError.SignalKilled -> NativeCompileError.BackendUnavailable.SignalKilled
  }

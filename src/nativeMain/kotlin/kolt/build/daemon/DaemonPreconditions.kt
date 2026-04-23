package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.isDirectory
import kolt.infra.listJarFiles
import kolt.resolve.compareVersions
import kolt.resolve.defaultResolverDeps

internal data class DaemonSetup(
  val javaBin: String,
  val daemonLaunchArgs: List<String>,
  val compilerJars: List<String>,
  val btaImplJars: List<String>,
  val daemonDir: String,
  val socketPath: String,
  val logPath: String,
)

// Soft floor for daemon-supported Kotlin versions per ADR 0022. The 2.3.x
// family shares one binary-compat surface (verified by spike #138); 2.2.x
// and earlier crash at `KotlinToolchains.loadImplementation` because their
// V1 compat shim lives in `internal.compat.*`, outside what the daemon's
// SharedApiClassesClassLoader exposes.
internal const val KOTLIN_VERSION_FLOOR = "2.3.0"

// Linux AF_UNIX `sun_path` field is a 108-byte char array. `bind()` on a
// longer path fails with `ENAMETOOLONG` / `EINVAL` at daemon spawn —
// *after* we have already spent money installing toolchains and launching
// the JVM. Checked up front as a precondition so the fallback rail catches
// the miss cleanly instead. See #146.
internal const val SUN_PATH_CAPACITY: Int = 108

// `applyPluginsFingerprintToFile` (in BuildCommands.kt) injects either
// `-noplugins` (10 bytes) or `-<8hex>` (9 bytes) before `.sock`. The
// no-plugins case is the worst case; use it as a conservative upper bound
// so the precondition stays independent of the plugin set. Over-estimating
// by 1 byte is fine — sun_path is a hard kernel limit, and we'd rather
// fall back to subprocess one byte early than crash at bind().
private const val MAX_FINGERPRINT_SUFFIX_BYTES: Int = "-noplugins".length

// None of these are build failures — ADR 0016 §5.
internal sealed interface DaemonPreconditionError {
  data class BootstrapJdkInstallFailed(val jdkInstallDir: String, val cause: String) :
    DaemonPreconditionError

  data object DaemonJarMissing : DaemonPreconditionError

  data class CompilerJarsMissing(val kotlincLibDir: String) : DaemonPreconditionError

  // ADR 0022: the daemon adapter is compiled against BTA-API 2.3.x.
  // Below the floor, no fetch can rescue us — the impl jar's V1 compat
  // shim lives in `internal.compat.*`, which the daemon classloader
  // hierarchy does not share.
  data class KotlinVersionBelowFloor(val requested: String, val floor: String) :
    DaemonPreconditionError

  data class BtaImplFetchFailed(val version: String, val cause: BtaImplFetchError) :
    DaemonPreconditionError

  // #146: projected socket path would exceed Linux sun_path capacity.
  // Caught as a precondition because the alternative — `bind()` failing
  // mid-spawn — leaves half-initialised daemon state behind.
  data class SocketPathTooLong(val socketPath: String, val projectedBytes: Int, val maxBytes: Int) :
    DaemonPreconditionError
}

internal fun resolveDaemonPreconditions(
  paths: KoltPaths,
  kotlincVersion: String,
  absProjectPath: String,
  bundledKotlinVersion: String,
  ensureJavaBin: (KoltPaths) -> Result<String, BootstrapJdkError> = ::ensureBootstrapJavaBin,
  resolveDaemonJar: () -> DaemonJarResolution = ::resolveDaemonJar,
  listCompilerJars: (String) -> List<String>? = { dir -> listJarFiles(dir).getOrElse { null } },
  resolveBundledBtaImplJars: () -> BtaImplJarsResolution = ::resolveBtaImplJars,
  fetchBtaImplJars: (String, String) -> Result<List<String>, BtaImplFetchError> = { v, base ->
    ensureBtaImplJars(v, base, defaultResolverDeps())
  },
  isDirectory: (String) -> Boolean = ::isDirectory,
  warningSink: (String) -> Unit = {},
): Result<DaemonSetup, DaemonPreconditionError> {
  // Floor check first: cheaper than any disk probe and short-circuits
  // every variant the daemon cannot serve. Equal to or above 2.3.0
  // counts as in-family per ADR 0022 §3.
  if (compareVersions(kotlincVersion, KOTLIN_VERSION_FLOOR) < 0) {
    return Err(
      DaemonPreconditionError.KotlinVersionBelowFloor(
        requested = kotlincVersion,
        floor = KOTLIN_VERSION_FLOOR,
      )
    )
  }

  // sun_path length check is pure string math, do it before any disk
  // probe. `setup.socketPath` gets a `-<fingerprint>` suffix added later
  // by `applyPluginsFingerprintToFile`; we over-estimate with the
  // longer `-noplugins` variant so the gate is plugin-set-agnostic.
  val projectHash = projectHashOf(absProjectPath)
  val baseSocketPath = paths.daemonSocketPath(projectHash, kotlincVersion)
  val projectedSocketBytes = baseSocketPath.length + MAX_FINGERPRINT_SUFFIX_BYTES
  if (projectedSocketBytes > SUN_PATH_CAPACITY) {
    return Err(
      DaemonPreconditionError.SocketPathTooLong(
        socketPath = baseSocketPath,
        projectedBytes = projectedSocketBytes,
        maxBytes = SUN_PATH_CAPACITY,
      )
    )
  }

  val javaBin =
    ensureJavaBin(paths).getOrElse { err ->
      return Err(
        DaemonPreconditionError.BootstrapJdkInstallFailed(
          jdkInstallDir = err.jdkInstallDir,
          cause = err.cause.message,
        )
      )
    }

  val daemonLaunchArgs =
    when (val res = resolveDaemonJar()) {
      is DaemonJarResolution.Resolved -> res.launchArgs
      DaemonJarResolution.NotFound -> return Err(DaemonPreconditionError.DaemonJarMissing)
    }

  val kotlincLibDir = "${paths.kotlincPath(kotlincVersion)}/lib"
  val compilerJars = listCompilerJars(kotlincLibDir)
  if (compilerJars.isNullOrEmpty()) {
    return Err(DaemonPreconditionError.CompilerJarsMissing(kotlincLibDir))
  }

  // Bundled-version fast path: a fresh kolt install ships the matching
  // BTA-impl classpath under <prefix>/libexec/kolt-bta-impl/, so a first
  // build at the bundled version pays no Maven Central round trip. On a
  // miss (dev binary, or a broken install) fall through to the fetcher —
  // same code path non-bundled versions always take. Other 2.3.x patches
  // resolve through the fetcher.
  val bundledLibexecJars =
    if (kotlincVersion == bundledKotlinVersion) {
      when (val res = resolveBundledBtaImplJars()) {
        is BtaImplJarsResolution.Resolved -> res.jars
        is BtaImplJarsResolution.NotFound -> {
          // When the probed libexec kolt-bta-impl dir's parent is a
          // directory, the selfExe is sitting in an installed layout
          // (`<prefix>/bin/kolt` + `<prefix>/libexec/`) whose -impl
          // subdir is empty or missing. That is an install integrity
          // regression — warn before silently falling through to the
          // Maven fetcher. Dev binaries (no libexec sibling) stay
          // silent because the heuristic fails.
          parentDir(res.probedDir)?.let { parent ->
            if (isDirectory(parent)) {
              warningSink(
                "warning: installed kolt libexec is missing ${res.probedDir} " +
                  "— fetching kotlin-build-tools-impl from Maven Central instead. " +
                  "Reinstall kolt to restore the bundled fast path."
              )
            }
          }
          null
        }
      }
    } else null

  val btaImplJars =
    bundledLibexecJars
      ?: fetchBtaImplJars(kotlincVersion, paths.cacheBase).getOrElse {
        return Err(DaemonPreconditionError.BtaImplFetchFailed(kotlincVersion, it))
      }

  return Ok(
    DaemonSetup(
      javaBin = javaBin,
      daemonLaunchArgs = daemonLaunchArgs,
      compilerJars = compilerJars,
      btaImplJars = btaImplJars,
      daemonDir = paths.daemonDir(projectHash, kotlincVersion),
      socketPath = baseSocketPath,
      logPath = paths.daemonLogPath(projectHash, kotlincVersion),
    )
  )
}

internal fun formatDaemonPreconditionWarning(err: DaemonPreconditionError): String =
  when (err) {
    is DaemonPreconditionError.BootstrapJdkInstallFailed ->
      "warning: could not install bootstrap JDK at ${err.jdkInstallDir} (${err.cause}) — falling back to subprocess compile"
    DaemonPreconditionError.DaemonJarMissing ->
      "warning: kolt-jvm-compiler-daemon jar not found — falling back to subprocess compile"
    is DaemonPreconditionError.CompilerJarsMissing ->
      "warning: no compiler jars found in ${err.kotlincLibDir} — falling back to subprocess compile"
    is DaemonPreconditionError.KotlinVersionBelowFloor ->
      "warning: kolt daemon supports Kotlin >= ${err.floor}; your kolt.toml requests ${err.requested} — " +
        "falling back to subprocess compile. Pass --no-daemon to silence this warning."
    is DaemonPreconditionError.BtaImplFetchFailed ->
      "warning: could not fetch kotlin-build-tools-impl ${err.version} from Maven Central " +
        "(${formatBtaImplFetchError(err.cause)}) — falling back to subprocess compile"
    is DaemonPreconditionError.SocketPathTooLong ->
      "warning: daemon socket path ${err.socketPath} would be ${err.projectedBytes} bytes, " +
        "exceeding the Linux AF_UNIX ${err.maxBytes}-byte limit — falling back to subprocess compile. " +
        "Move the project under a shorter \$HOME, or pass --no-daemon to silence this warning."
  }

private fun formatBtaImplFetchError(err: BtaImplFetchError): String =
  when (err) {
    is BtaImplFetchError.ResolveFailed -> err.cause.toString()
    is BtaImplFetchError.ResolvedEmpty -> "resolver returned no jars"
  }

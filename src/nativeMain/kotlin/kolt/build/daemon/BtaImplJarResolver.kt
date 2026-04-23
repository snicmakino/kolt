package kolt.build.daemon

import com.github.michaelbull.result.getOrElse
import kolt.infra.listJarFiles
import kolt.infra.readSelfExe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

sealed interface BtaImplJarsResolution {
  data class Resolved(val jars: List<String>, val source: Source) : BtaImplJarsResolution

  data class NotFound(val probedDir: String) : BtaImplJarsResolution

  enum class Source {
    Env,
    Libexec,
  }
}

internal const val KOLT_BTA_IMPL_JARS_DIR_ENV = "KOLT_BTA_IMPL_JARS_DIR"

internal const val BTA_IMPL_DIR_NAME = "kolt-bta-impl"

// Probe order: env override -> libexec. There is no dev fallback: the
// `kolt-build-tools-impl` closure is not a kolt build output (daemon
// kolt.toml declares the `-api` artifact, not `-impl`), so dev flows run
// `scripts/assemble-dist.sh` once and point `KOLT_BTA_IMPL_JARS_DIR` at
// the produced `dist/.../libexec/kolt-bta-impl/`, or let DaemonPreconditions
// fall through to the Maven Central fetcher on NotFound for bundled versions.
fun resolveBtaImplJarsPure(
  envDirValue: String?,
  selfExePath: String?,
  listJarFiles: (String) -> List<String>?,
): BtaImplJarsResolution {
  if (envDirValue != null && envDirValue.isNotEmpty()) {
    val jars = listJarFiles(envDirValue)
    return if (!jars.isNullOrEmpty()) {
      BtaImplJarsResolution.Resolved(jars, BtaImplJarsResolution.Source.Env)
    } else {
      BtaImplJarsResolution.NotFound(envDirValue)
    }
  }

  if (selfExePath == null) return BtaImplJarsResolution.NotFound("<no selfExe>")

  val binDir = parentDir(selfExePath)
  val prefix = binDir?.let { parentDir(it) }
  if (prefix != null) {
    val libexec = "$prefix/libexec/$BTA_IMPL_DIR_NAME"
    val jars = listJarFiles(libexec)
    if (!jars.isNullOrEmpty()) {
      return BtaImplJarsResolution.Resolved(jars, BtaImplJarsResolution.Source.Libexec)
    }
    return BtaImplJarsResolution.NotFound(libexec)
  }
  return BtaImplJarsResolution.NotFound("<no prefix>")
}

@OptIn(ExperimentalForeignApi::class)
fun resolveBtaImplJars(): BtaImplJarsResolution {
  val envDir = platform.posix.getenv(KOLT_BTA_IMPL_JARS_DIR_ENV)?.toKString()
  val selfExe = readSelfExe().getOrElse { null }
  return resolveBtaImplJarsPure(
    envDirValue = envDir,
    selfExePath = selfExe,
    listJarFiles = { dir -> listJarFiles(dir).getOrElse { null } },
  )
}

package kolt.usertool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapError
import kolt.build.daemon.ensureBootstrapJavaBin
import kolt.config.KoltPaths
import kolt.infra.ProcessError
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import libarchive.ARCHIVE_EOF
import libarchive.ARCHIVE_OK
import libarchive.archive_entry_pathname
import libarchive.archive_entry_size
import libarchive.archive_error_string
import libarchive.archive_read_close
import libarchive.archive_read_data
import libarchive.archive_read_free
import libarchive.archive_read_new
import libarchive.archive_read_next_header
import libarchive.archive_read_open_filename
import libarchive.archive_read_support_filter_all
import libarchive.archive_read_support_format_all

private const val MANIFEST_PATH = "META-INF/MANIFEST.MF"
private const val MAIN_CLASS_KEY = "Main-Class"

// libarchive read block size used elsewhere in kolt — see ArchiveExtraction.
private const val READ_BLOCK_SIZE: ULong = 10240u

/**
 * Read the `Main-Class` attribute from a jar's `META-INF/MANIFEST.MF`.
 *
 * Returns:
 * - [ToolLaunchError.NotRunnableJar] when libarchive cannot open the file as a zip (missing magic,
 *   truncated, plain-text masquerading as `.jar`). The caller fills the alias.
 * - [ToolLaunchError.MainClassMissing] when the archive opens but `META-INF/MANIFEST.MF` is absent
 *   from the entries, or present but does not contain a `Main-Class:` line.
 *
 * MANIFEST.MF line-folding (RFC: a line beginning with SP or HT continues the previous logical
 * line) is honoured for `Main-Class` only — the value is reassembled across continuations before
 * the lookup. Other RFC 822 corner cases are intentionally out of scope for v1.
 *
 * The alias placeholder on returned errors is `""` because this internal helper has no alias
 * context; [launch] lifts the error and substitutes the correct alias before surfacing to callers.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun readMainClassFromJar(jarPath: String): Result<String, ToolLaunchError> {
  val reader =
    archive_read_new()
      ?: return Err(
        ToolLaunchError.NotRunnableJar(
          alias = "",
          jarPath = jarPath,
          reason = "archive_read_new returned null",
        )
      )
  archive_read_support_format_all(reader)
  archive_read_support_filter_all(reader)

  try {
    val openRc = archive_read_open_filename(reader, jarPath, READ_BLOCK_SIZE.convert())
    if (openRc != ARCHIVE_OK) {
      return Err(
        ToolLaunchError.NotRunnableJar(
          alias = "",
          jarPath = jarPath,
          reason = "archive_read_open_filename: ${errString(reader)}",
        )
      )
    }
    return findMainClass(reader, jarPath)
  } finally {
    archive_read_close(reader)
    archive_read_free(reader)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun findMainClass(
  reader: CPointer<cnames.structs.archive>,
  jarPath: String,
): Result<String, ToolLaunchError> = memScoped {
  val entryHolder = alloc<CPointerVar<cnames.structs.archive_entry>>()
  while (true) {
    val rc = archive_read_next_header(reader, entryHolder.ptr)
    if (rc == ARCHIVE_EOF) break
    if (rc != ARCHIVE_OK) {
      return@memScoped Err(
        ToolLaunchError.NotRunnableJar(
          alias = "",
          jarPath = jarPath,
          reason = "archive_read_next_header: ${errString(reader)}",
        )
      )
    }
    val entry =
      entryHolder.value
        ?: return@memScoped Err(
          ToolLaunchError.NotRunnableJar(
            alias = "",
            jarPath = jarPath,
            reason = "archive_read_next_header returned null entry",
          )
        )
    val pathname = archive_entry_pathname(entry)?.toKString().orEmpty()
    if (pathname != MANIFEST_PATH) continue

    val size = archive_entry_size(entry)
    val content = readEntryBytes(reader, size).decodeToString()
    val mainClass = extractMainClass(content)
    return@memScoped if (mainClass != null) {
      Ok(mainClass)
    } else {
      Err(ToolLaunchError.MainClassMissing(alias = "", jarPath = jarPath))
    }
  }
  Err(ToolLaunchError.MainClassMissing(alias = "", jarPath = jarPath))
}

@OptIn(ExperimentalForeignApi::class)
private fun readEntryBytes(reader: CPointer<cnames.structs.archive>, size: Long): ByteArray {
  // For MANIFEST.MF (typically <4KiB), a single buffer keyed off the declared
  // entry size is enough. We still loop archive_read_data because libarchive
  // does not guarantee returning the whole entry in one call.
  val capacity = if (size in 1..(64 * 1024)) size.toInt() else 64 * 1024
  val out = ArrayList<Byte>(capacity)
  memScoped {
    val buf = allocArray<ByteVar>(capacity)
    while (true) {
      val n = archive_read_data(reader, buf, capacity.convert())
      if (n <= 0) break
      out.addAll(buf.readBytes(n.toInt()).asList())
    }
  }
  return out.toByteArray()
}

/**
 * Extract the `Main-Class` value from a MANIFEST.MF content string, honouring RFC continuation
 * lines (next line starts with SP or HT — drop the leading whitespace and concatenate). Returns
 * null when the attribute is absent. Manifest line endings are normalised to handle CRLF / LF
 * before scanning.
 */
internal fun extractMainClass(manifest: String): String? {
  val normalised = manifest.replace("\r\n", "\n").replace("\r", "\n")
  val lines = normalised.split("\n")
  var i = 0
  while (i < lines.size) {
    val line = lines[i]
    val colon = line.indexOf(':')
    if (colon > 0) {
      val key = line.substring(0, colon)
      if (key == MAIN_CLASS_KEY) {
        // Value starts after `: ` (one optional space). Trim leading single space if present.
        val rawValue = line.substring(colon + 1).let { if (it.startsWith(' ')) it.drop(1) else it }
        val joined = StringBuilder(rawValue)
        var j = i + 1
        while (j < lines.size && (lines[j].startsWith(" ") || lines[j].startsWith("\t"))) {
          // Spec: continuation drops exactly one leading whitespace.
          joined.append(lines[j].substring(1))
          j++
        }
        val value = joined.toString().trim()
        return if (value.isEmpty()) null else value
      }
    }
    i++
  }
  return null
}

@OptIn(ExperimentalForeignApi::class)
private fun errString(handle: CPointer<cnames.structs.archive>): String =
  archive_error_string(handle)?.toKString() ?: "unknown libarchive error"

/**
 * Launch the runnable jar represented by [jarHandle] under the bootstrap JDK.
 *
 * Failure modes mapped to [ToolLaunchError] (cause-distinguishable, R5.4):
 * - MANIFEST.MF cannot be read or `Main-Class:` is absent → `MainClassMissing` / `NotRunnableJar`.
 *   The alias placeholder set by [readMainClassFromJar] is overwritten with the real alias here.
 * - Bootstrap JDK install / probe fails → `JdkUnavailable`. The cause string captures the install
 *   target so users can re-run install or diagnose disk / network issues.
 * - The child process exits or is signalled. Successful exits (including non-zero) propagate as
 *   `Result.Ok(exitCode)` so kolt's wrapper transparently passes through the tool's value (R2.2).
 *   `executeCommand` returns `Err(NonZeroExit(code))` on non-zero — that is unwrapped here into
 *   `Ok(code)`. Fork / wait / signal failures are surfaced as `JdkUnavailable` with the underlying
 *   `ProcessError` in the cause: the design's three EXIT_TOOL_ERROR variants are `NotRunnableJar` /
 *   `MainClassMissing` / `JdkUnavailable`, and process-spawn failures fit the "broken host
 *   environment" axis rather than a tool-jar defect — keeping the variant count at three in line
 *   with `ToolErrorTest`.
 *
 * `KOLT_VERBOSE=1` (in the [env] map) emits a single stderr line `tool=<alias> jdk=<javaBin>
 * jar=<jarPath>` immediately before launch (R6.3). It is the only kolt-side stderr written on the
 * launch path; tool stderr is inherited from the child.
 */
internal fun launch(
  alias: String,
  jarHandle: ToolJarHandle,
  args: List<String>,
  paths: KoltPaths,
  env: Map<String, String>,
  resolveJavaBin: (KoltPaths) -> Result<String, String> = ::ensureBootstrapJavaBinDefault,
  exec: (List<String>, Map<String, String>) -> Result<Int, ProcessError> = ::executeCommand,
  log: (String) -> Unit = ::eprintln,
): Result<Int, ToolLaunchError> {
  readMainClassFromJar(jarHandle.jarPath).getOrElse { err ->
    return Err(rebrandWithAlias(err, alias))
  }

  val javaBin =
    resolveJavaBin(paths).getOrElse { cause ->
      return Err(ToolLaunchError.JdkUnavailable(cause = cause))
    }

  if (env["KOLT_VERBOSE"] == "1") {
    log("tool=$alias jdk=$javaBin jar=${jarHandle.jarPath}")
  }

  val command = listOf(javaBin, "-jar", jarHandle.jarPath) + args
  val result = exec(command, env)
  val err = result.getError() ?: return Ok(result.getOrElse { error("unreachable") })
  return when (err) {
    is ProcessError.NonZeroExit -> Ok(err.exitCode)
    else ->
      // Fork / wait / signal failures: render the underlying ProcessError into the JDK
      // cause so the user sees a non-empty diagnostic. See KDoc above for rationale.
      Err(ToolLaunchError.JdkUnavailable(cause = "process launch failed: $err"))
  }
}

private fun rebrandWithAlias(err: ToolLaunchError, alias: String): ToolLaunchError =
  when (err) {
    is ToolLaunchError.NotRunnableJar -> err.copy(alias = alias)
    is ToolLaunchError.MainClassMissing -> err.copy(alias = alias)
    is ToolLaunchError.JdkUnavailable -> err
  }

internal fun ensureBootstrapJavaBinDefault(paths: KoltPaths): Result<String, String> =
  ensureBootstrapJavaBin(paths).mapError {
    "bootstrap JDK install failed at ${it.jdkInstallDir}: ${it.cause.message}"
  }

package kolt.infra

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import libarchive.AE_IFLNK
import libarchive.ARCHIVE_EOF
import libarchive.ARCHIVE_EXTRACT_PERM
import libarchive.ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS
import libarchive.ARCHIVE_EXTRACT_SECURE_NODOTDOT
import libarchive.ARCHIVE_EXTRACT_SECURE_SYMLINKS
import libarchive.ARCHIVE_EXTRACT_TIME
import libarchive.ARCHIVE_FAILED
import libarchive.ARCHIVE_OK
import libarchive.archive_entry_filetype
import libarchive.archive_entry_pathname
import libarchive.archive_entry_size
import libarchive.archive_entry_symlink
import libarchive.archive_error_string
import libarchive.archive_read_close
import libarchive.archive_read_data_block
import libarchive.archive_read_free
import libarchive.archive_read_new
import libarchive.archive_read_next_header
import libarchive.archive_read_open_filename
import libarchive.archive_read_support_filter_all
import libarchive.archive_read_support_format_all
import libarchive.archive_write_close
import libarchive.archive_write_data_block
import libarchive.archive_write_disk_new
import libarchive.archive_write_disk_set_options
import libarchive.archive_write_disk_set_standard_lookup
import libarchive.archive_write_finish_entry
import libarchive.archive_write_free
import libarchive.archive_write_header
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd

internal sealed class ExtractError {
  abstract val message: String

  data class ArchiveNotFound(override val message: String) : ExtractError()

  data class OpenFailed(override val message: String) : ExtractError()

  data class ReadFailed(override val message: String) : ExtractError()

  data class WriteFailed(override val message: String) : ExtractError()

  data class SecurityViolation(override val message: String) : ExtractError()
}

private const val EXTRACT_BLOCK_SIZE: ULong = 10240u

// libarchive's ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS rejects any entry
// whose pathname (after any rewriting) is absolute, so we cannot rewrite
// entry paths to absolute destDir-prefixed paths. Instead we chdir into
// destDir for the duration of extraction and let entry paths stay relative.
// ADR 0031 §5: chdir is process-global and safe only under kolt's sequential
// bootstrap install; revisit before introducing concurrent toolchain installs.
@OptIn(ExperimentalForeignApi::class)
internal fun extractArchive(archivePath: String, destDir: String): Result<Unit, ExtractError> {
  if (!fileExists(archivePath)) {
    return Err(ExtractError.ArchiveNotFound("archive not found: $archivePath"))
  }
  val extractFlags: Int =
    ARCHIVE_EXTRACT_PERM or
      ARCHIVE_EXTRACT_TIME or
      ARCHIVE_EXTRACT_SECURE_SYMLINKS or
      ARCHIVE_EXTRACT_SECURE_NODOTDOT or
      ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS

  val savedCwd =
    memScoped {
      val buf = allocArray<ByteVar>(PATH_MAX)
      getcwd(buf, PATH_MAX.convert())?.toKString()
    } ?: return Err(ExtractError.OpenFailed("could not capture cwd before extraction"))

  // chdir below makes relative archivePath unreachable, so resolve it
  // against savedCwd up front. absolutise() leaves absolute paths alone.
  val resolvedArchivePath = absolutise(archivePath, savedCwd)

  if (chdir(destDir) != 0) {
    return Err(ExtractError.OpenFailed("could not chdir to destDir: $destDir"))
  }

  val reader =
    archive_read_new()
      ?: run {
        chdir(savedCwd)
        return Err(ExtractError.OpenFailed("archive_read_new returned null"))
      }
  archive_read_support_format_all(reader)
  archive_read_support_filter_all(reader)

  val writer =
    archive_write_disk_new()
      ?: run {
        archive_read_free(reader)
        chdir(savedCwd)
        return Err(ExtractError.OpenFailed("archive_write_disk_new returned null"))
      }
  archive_write_disk_set_options(writer, extractFlags)
  archive_write_disk_set_standard_lookup(writer)

  try {
    val openRc =
      archive_read_open_filename(reader, resolvedArchivePath, EXTRACT_BLOCK_SIZE.convert())
    if (openRc != ARCHIVE_OK) {
      return Err(ExtractError.OpenFailed("archive_read_open_filename: ${errString(reader)}"))
    }
    return extractEntries(reader, writer)
  } finally {
    archive_read_close(reader)
    archive_read_free(reader)
    archive_write_close(writer)
    archive_write_free(writer)
    chdir(savedCwd)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun extractEntries(
  reader: CPointer<cnames.structs.archive>,
  writer: CPointer<cnames.structs.archive>,
): Result<Unit, ExtractError> = memScoped {
  val entryHolder = alloc<CPointerVar<cnames.structs.archive_entry>>()
  while (true) {
    val rc = archive_read_next_header(reader, entryHolder.ptr)
    if (rc == ARCHIVE_EOF) return@memScoped Ok(Unit)
    if (rc != ARCHIVE_OK) {
      return@memScoped Err(
        ExtractError.ReadFailed("archive_read_next_header: ${errString(reader)}")
      )
    }
    val entry =
      entryHolder.value
        ?: return@memScoped Err(
          ExtractError.ReadFailed("archive_read_next_header returned null entry")
        )

    val originalPath = archive_entry_pathname(entry)?.toKString().orEmpty()

    // libarchive's SECURE_SYMLINKS only blocks writes through symlink path
    // components already on disk; it does not validate the target string of
    // a symlink entry being created, so we inspect targets ourselves to
    // reject ones that escape destDir.
    if (archive_entry_filetype(entry).toInt() and AE_IFLNK.toInt() == AE_IFLNK.toInt()) {
      val target = archive_entry_symlink(entry)?.toKString().orEmpty()
      if (symlinkTargetEscapes(originalPath, target)) {
        return@memScoped Err(
          ExtractError.SecurityViolation(
            "symlink '$originalPath' targets '$target' which escapes destDir"
          )
        )
      }
    }

    val headerRc = archive_write_header(writer, entry)
    when {
      headerRc == ARCHIVE_FAILED ->
        return@memScoped Err(
          ExtractError.SecurityViolation(
            "archive_write_header rejected entry '$originalPath': ${errString(writer)}"
          )
        )
      headerRc != ARCHIVE_OK ->
        return@memScoped Err(ExtractError.WriteFailed("archive_write_header: ${errString(writer)}"))
    }

    if (archive_entry_size(entry) > 0) {
      copyEntryData(reader, writer).let { copyErr ->
        if (copyErr != null) return@memScoped Err(copyErr)
      }
    }

    val finishRc = archive_write_finish_entry(writer)
    if (finishRc != ARCHIVE_OK) {
      return@memScoped Err(
        ExtractError.WriteFailed("archive_write_finish_entry: ${errString(writer)}")
      )
    }
  }
  @Suppress("UNREACHABLE_CODE") Ok(Unit)
}

@OptIn(ExperimentalForeignApi::class)
private fun copyEntryData(
  reader: CPointer<cnames.structs.archive>,
  writer: CPointer<cnames.structs.archive>,
): ExtractError? = memScoped {
  val buffHolder = alloc<COpaquePointerVar>()
  val sizeHolder = alloc<platform.posix.size_tVar>()
  val offsetHolder = alloc<platform.posix.int64_tVar>()
  while (true) {
    val rc = archive_read_data_block(reader, buffHolder.ptr, sizeHolder.ptr, offsetHolder.ptr)
    if (rc == ARCHIVE_EOF) return@memScoped null
    if (rc != ARCHIVE_OK) {
      return@memScoped ExtractError.ReadFailed("archive_read_data_block: ${errString(reader)}")
    }
    val written =
      archive_write_data_block(writer, buffHolder.value, sizeHolder.value, offsetHolder.value)
    if (written < 0) {
      return@memScoped ExtractError.WriteFailed("archive_write_data_block: ${errString(writer)}")
    }
  }
  @Suppress("UNREACHABLE_CODE") null
}

// Resolve `target` as if it were a symlink at `entryPath` (relative to
// destDir) and return true when the resulting path escapes destDir.
// Absolute targets always escape; relative targets escape when their depth
// counter goes negative while walking from the entry's parent directory.
private fun symlinkTargetEscapes(entryPath: String, target: String): Boolean {
  if (target.isEmpty()) return false
  if (target.startsWith("/")) return true

  val parentSegments = entryPath.split("/").filter { it.isNotEmpty() && it != "." }
  var depth = (parentSegments.size - 1).coerceAtLeast(0)

  for (segment in target.split("/")) {
    when (segment) {
      "",
      "." -> Unit
      ".." -> {
        if (depth == 0) return true
        depth--
      }
      else -> depth++
    }
  }
  return false
}

@OptIn(ExperimentalForeignApi::class)
private fun errString(handle: CPointer<cnames.structs.archive>): String =
  archive_error_string(handle)?.toKString() ?: "unknown libarchive error"

package kolt.infra

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.*
import libcurl.CURLE_OK
import libcurl.CURLINFO_RESPONSE_CODE
import libcurl.CURLOPT_CONNECTTIMEOUT
import libcurl.CURLOPT_FOLLOWLOCATION
import libcurl.CURLOPT_MAXREDIRS
import libcurl.CURLOPT_TIMEOUT
import libcurl.CURLOPT_URL
import libcurl.CURLOPT_WRITEDATA
import libcurl.CURLOPT_WRITEFUNCTION
import libcurl.curl_easy_cleanup
import libcurl.curl_easy_getinfo
import libcurl.curl_easy_init
import libcurl.curl_easy_perform
import libcurl.curl_easy_setopt
import libcurl.curl_easy_strerror
import platform.posix.FILE
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getpid
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rename
import platform.posix.stat
import platform.posix.time
import platform.posix.time_tVar

sealed class DownloadError {
  data class HttpFailed(val url: String, val statusCode: Int) : DownloadError()

  data class WriteFailed(val path: String) : DownloadError()

  data class NetworkError(val url: String, val message: String) : DownloadError()
}

private const val STALE_TEMP_AGE_SECONDS: Long = 86_400L

@OptIn(ExperimentalForeignApi::class)
fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
  val parentDir = parentDirOf(destPath)
  if (parentDir != null) {
    cleanupStaleTemps(parentDir)
  }

  // Per-pid temp suffix lets concurrent downloaders of the same coordinate
  // each own their own intermediate file; the eventual rename(2) is atomic
  // on the same filesystem so any reader sees either no file or a complete one.
  val tempPath = "$destPath.tmp.${getpid()}"

  val curl =
    curl_easy_init() ?: return Err(DownloadError.NetworkError(url, "failed to initialize libcurl"))

  val fp = fopen(tempPath, "wb")
  if (fp == null) {
    curl_easy_cleanup(curl)
    return Err(DownloadError.WriteFailed(tempPath))
  }

  var fileClosed = false
  var success = false
  try {
    curl_easy_setopt(curl, CURLOPT_URL, url)
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L)
    curl_easy_setopt(curl, CURLOPT_MAXREDIRS, 10L)
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 30L)
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 300L)
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, staticCFunction(::curlWriteCallback))
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, fp)

    val res = curl_easy_perform(curl)
    if (res != CURLE_OK) {
      val msg = curl_easy_strerror(res)?.toKString() ?: "unknown error"
      return Err(DownloadError.NetworkError(url, msg))
    }

    memScoped {
      val httpCode = alloc<LongVar>()
      curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, httpCode.ptr)
      val code = httpCode.value.toInt()
      if (code !in 200..299) {
        return Err(DownloadError.HttpFailed(url, code))
      }
    }

    // Close before rename so flushed bytes are on disk and the FILE* does
    // not point at a renamed inode on the unlikely close-after-rename path.
    fclose(fp)
    fileClosed = true

    if (rename(tempPath, destPath) != 0) {
      return Err(DownloadError.WriteFailed(destPath))
    }

    success = true
    return Ok(Unit)
  } finally {
    if (!fileClosed) fclose(fp)
    curl_easy_cleanup(curl)
    if (!success) remove(tempPath)
  }
}

// Sweep `*.tmp.*` files older than `olderThanSeconds` from `cacheDir`.
// Errors (cannot opendir, cannot stat) are swallowed silently — sweeping
// is best-effort and must never fail a download.
@OptIn(ExperimentalForeignApi::class)
private fun cleanupStaleTemps(cacheDir: String, olderThanSeconds: Long = STALE_TEMP_AGE_SECONDS) {
  val dir = opendir(cacheDir) ?: return
  val nowSec = memScoped {
    val nowVar = alloc<time_tVar>()
    time(nowVar.ptr)
  }
  try {
    while (true) {
      val entry = readdir(dir) ?: break
      val name = entry.pointed.d_name.toKString()
      if (name == "." || name == "..") continue
      if (!name.contains(".tmp.")) continue
      val full = "$cacheDir/$name"
      memScoped {
        val st = alloc<stat>()
        if (stat(full, st.ptr) == 0) {
          val mtime = st.st_mtim.tv_sec
          if (nowSec - mtime > olderThanSeconds) {
            remove(full)
          }
        }
      }
    }
  } finally {
    closedir(dir)
  }
}

private fun parentDirOf(path: String): String? {
  val idx = path.lastIndexOf('/')
  return when {
    idx < 0 -> null
    idx == 0 -> "/"
    else -> path.substring(0, idx)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun curlWriteCallback(
  ptr: CPointer<ByteVar>?,
  size: ULong,
  nmemb: ULong,
  stream: COpaquePointer?,
): ULong {
  val fp: CPointer<FILE> = stream?.reinterpret() ?: return 0u
  return fwrite(ptr, size, nmemb, fp)
}

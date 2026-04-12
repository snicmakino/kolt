package kolt.infra

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.*
import libcurl.CURLINFO_RESPONSE_CODE
import libcurl.CURLOPT_CONNECTTIMEOUT
import libcurl.CURLOPT_FOLLOWLOCATION
import libcurl.CURLOPT_MAXREDIRS
import libcurl.CURLOPT_TIMEOUT
import libcurl.CURLOPT_URL
import libcurl.CURLOPT_WRITEDATA
import libcurl.CURLOPT_WRITEFUNCTION
import libcurl.CURLE_OK
import libcurl.curl_easy_cleanup
import libcurl.curl_easy_getinfo
import libcurl.curl_easy_init
import libcurl.curl_easy_perform
import libcurl.curl_easy_setopt
import libcurl.curl_easy_strerror
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove

sealed class DownloadError {
    data class HttpFailed(val url: String, val statusCode: Int) : DownloadError()
    data class WriteFailed(val path: String) : DownloadError()
    data class NetworkError(val url: String, val message: String) : DownloadError()
}

@OptIn(ExperimentalForeignApi::class)
fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
    val curl = curl_easy_init()
        ?: return Err(DownloadError.NetworkError(url, "failed to initialize libcurl"))

    val fp = fopen(destPath, "wb")
    if (fp == null) {
        curl_easy_cleanup(curl)
        return Err(DownloadError.WriteFailed(destPath))
    }

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

        success = true
        return Ok(Unit)
    } finally {
        fclose(fp)
        curl_easy_cleanup(curl)
        if (!success) remove(destPath)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun curlWriteCallback(
    ptr: CPointer<ByteVar>?,
    size: ULong,
    nmemb: ULong,
    stream: COpaquePointer?
): ULong {
    val fp: CPointer<FILE> = stream?.reinterpret() ?: return 0u
    return fwrite(ptr, size, nmemb, fp)
}

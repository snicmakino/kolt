package kolt.infra

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.*
import org.kotlincrypto.hash.sha2.SHA256
import platform.posix.*

data class Sha256Error(val path: String)

// In-memory SHA-256 hex digest. Callers that need only a byte-array hash
// (e.g. hashing a project path for the daemon socket directory name) use
// this instead of dragging bytes through a temporary file.
fun sha256Hex(bytes: ByteArray): String {
    val digest = SHA256()
    digest.update(bytes)
    return digest.digest().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}

@OptIn(ExperimentalForeignApi::class)
fun computeSha256(filePath: String): Result<String, Sha256Error> {
    val fp = fopen(filePath, "rb") ?: return Err(Sha256Error(filePath))
    try {
        val digest = SHA256()
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            while (true) {
                val bytesRead = fread(buffer, 1u, 4096u, fp)
                if (bytesRead == 0uL) break
                digest.update(buffer.readBytes(bytesRead.toInt()))
            }
        }
        val hash = digest.digest()
        return Ok(hash.joinToString("") { it.toUByte().toString(16).padStart(2, '0') })
    } finally {
        fclose(fp)
    }
}

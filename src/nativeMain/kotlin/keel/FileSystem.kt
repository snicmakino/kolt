package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kotlinx.cinterop.*
import platform.posix.*

data class OpenFailed(val path: String)

data class MkdirFailed(val path: String)

@OptIn(ExperimentalForeignApi::class)
fun readFileAsString(path: String): Result<String, OpenFailed> {
    val fp = fopen(path, "r") ?: return Err(OpenFailed(path))
    try {
        val chunks = mutableListOf<String>()
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            while (true) {
                val bytesRead = fread(buffer, 1u, 4096u, fp)
                if (bytesRead == 0uL) break
                chunks.add(buffer.readBytes(bytesRead.toInt()).decodeToString())
            }
        }
        return Ok(chunks.joinToString(""))
    } finally {
        fclose(fp)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun fileExists(path: String): Boolean {
    return access(path, F_OK) == 0
}

@OptIn(ExperimentalForeignApi::class)
fun ensureDirectory(path: String): Result<Unit, MkdirFailed> {
    if (fileExists(path)) return Ok(Unit)
    return if (mkdir(path, 0b111111101u) == 0) { // 0755
        Ok(Unit)
    } else {
        Err(MkdirFailed(path))
    }
}

data class WriteFailed(val path: String)

@OptIn(ExperimentalForeignApi::class)
fun writeFileAsString(path: String, content: String): Result<Unit, WriteFailed> {
    val fp = fopen(path, "w") ?: return Err(WriteFailed(path))
    try {
        val bytes = content.encodeToByteArray()
        if (bytes.isNotEmpty()) {
            val written = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), fp)
            }
            if (written != bytes.size.toULong()) {
                return Err(WriteFailed(path))
            }
        }
        return Ok(Unit)
    } finally {
        fclose(fp)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> {
    if (fileExists(path)) return Ok(Unit)
    val isAbsolute = path.startsWith("/")
    val parts = path.split("/").filter { it.isNotEmpty() }
    var current = ""
    for (part in parts) {
        current = when {
            current.isEmpty() && isAbsolute -> "/$part"
            current.isEmpty() -> part
            else -> "$current/$part"
        }
        if (!fileExists(current)) {
            if (mkdir(current, 0b111111101u) != 0) {
                return Err(MkdirFailed(current))
            }
        }
    }
    return Ok(Unit)
}

@OptIn(ExperimentalForeignApi::class)
fun deleteFile(path: String) {
    remove(path)
}

data class RemoveFailed(val path: String)

@OptIn(ExperimentalForeignApi::class)
fun removeDirectoryRecursive(path: String): Result<Unit, RemoveFailed> {
    val dir = opendir(path) ?: return Err(RemoveFailed(path))
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            val childPath = "$path/$name"
            if (isDirectory(childPath)) {
                removeDirectoryRecursive(childPath).getOrElse { return Err(it) }
            } else {
                if (remove(childPath) != 0) return Err(RemoveFailed(childPath))
            }
        }
    } finally {
        closedir(dir)
    }
    return if (rmdir(path) == 0) Ok(Unit) else Err(RemoveFailed(path))
}

@OptIn(ExperimentalForeignApi::class)
private fun isDirectory(path: String): Boolean {
    memScoped {
        val statBuf = alloc<stat>()
        if (lstat(path, statBuf.ptr) != 0) return false
        return (statBuf.st_mode.toInt() and S_IFMT) == S_IFDIR
    }
}

data class HomeNotFound(val message: String = "HOME environment variable is not set")

@OptIn(ExperimentalForeignApi::class)
fun homeDirectory(): Result<String, HomeNotFound> {
    val home = getenv("HOME")?.toKString()
    return if (home.isNullOrEmpty()) {
        Err(HomeNotFound())
    } else {
        Ok(home)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun eprintln(msg: String) {
    val bytes = (msg + "\n").encodeToByteArray()
    bytes.usePinned { pinned ->
        write(STDERR_FILENO, pinned.addressOf(0), bytes.size.toULong())
    }
}

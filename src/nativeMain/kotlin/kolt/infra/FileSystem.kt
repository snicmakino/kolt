package kolt.infra

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
fun isDirectory(path: String): Boolean {
    memScoped {
        val statBuf = alloc<stat>()
        if (lstat(path, statBuf.ptr) != 0) return false
        return (statBuf.st_mode.toInt() and S_IFMT) == S_IFDIR
    }
}

/**
 * Returns `true` iff [path] resolves (via `stat(2)`, following
 * symlinks) to a regular file. Distinct from a naive "not a
 * directory" test because symlinks, FIFOs, sockets, and device
 * nodes are also not directories but are not regular files either.
 * Used by [listJarFiles] to filter out anything a caller cannot
 * hand to a JVM URL classloader as a jar.
 */
@OptIn(ExperimentalForeignApi::class)
fun isRegularFile(path: String): Boolean {
    memScoped {
        val statBuf = alloc<stat>()
        if (stat(path, statBuf.ptr) != 0) return false
        return (statBuf.st_mode.toInt() and S_IFMT) == S_IFREG
    }
}

@OptIn(ExperimentalForeignApi::class)
fun fileMtime(path: String): Long? {
    memScoped {
        val statBuf = alloc<stat>()
        if (stat(path, statBuf.ptr) != 0) return null
        return statBuf.st_mtim.tv_sec
    }
}

/** Return the newest mtime among all .kt files in the given directories. 0 if no files found. */
fun newestMtime(directories: List<String>): Long {
    var newest = 0L
    for (dir in directories) {
        collectNewestMtime(dir) { mtime ->
            if (mtime > newest) newest = mtime
        }
    }
    return newest
}

private fun collectNewestMtime(directory: String, onFile: (Long) -> Unit) {
    if (!fileExists(directory)) return
    val files = listKotlinFiles(directory).getOrElse { return }
    for (file in files) {
        val mtime = fileMtime(file) ?: continue
        onFile(mtime)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun newestMtimeAll(directory: String): Long {
    var newest = 0L
    collectAllFileMtimes(directory) { mtime ->
        if (mtime > newest) newest = mtime
    }
    return newest
}

@OptIn(ExperimentalForeignApi::class)
private fun collectAllFileMtimes(directory: String, onFile: (Long) -> Unit) {
    if (!fileExists(directory)) return
    val dir = opendir(directory) ?: return
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            val childPath = "$directory/$name"
            if (isDirectory(childPath)) {
                collectAllFileMtimes(childPath, onFile)
            } else {
                val mtime = fileMtime(childPath) ?: continue
                onFile(mtime)
            }
        }
    } finally {
        closedir(dir)
    }
}

/**
 * Non-recursively lists entries in [path] whose name ends with `.jar`
 * **and** which resolve (via `stat(2)`) to regular files, returned
 * as absolute paths (`$path/$name`) in lexicographic order. Symlinks
 * are followed, so a symlink pointing at a real jar is admitted and
 * a symlink pointing at a directory named `foo.jar` is filtered out.
 * Returns [ListFilesFailed] if the directory cannot be opened. An
 * empty successful result is distinct from the error case, so a
 * caller can treat "directory missing" (Err) and "directory exists
 * but has no jars" (empty Ok) differently.
 */
@OptIn(ExperimentalForeignApi::class)
fun listJarFiles(path: String): Result<List<String>, ListFilesFailed> {
    val dir = opendir(path) ?: return Err(ListFilesFailed(path))
    val entries = mutableListOf<String>()
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            if (!name.endsWith(".jar")) continue
            val childPath = "$path/$name"
            if (!isRegularFile(childPath)) continue
            entries.add(childPath)
        }
    } finally {
        closedir(dir)
    }
    entries.sort()
    return Ok(entries)
}

@OptIn(ExperimentalForeignApi::class)
fun listSubdirectories(path: String): Result<List<String>, ListFilesFailed> {
    val dir = opendir(path) ?: return Err(ListFilesFailed(path))
    val entries = mutableListOf<String>()
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            if (isDirectory("$path/$name")) {
                entries.add(name)
            }
        }
    } finally {
        closedir(dir)
    }
    entries.sort()
    return Ok(entries)
}

data class ListFilesFailed(val path: String)

@OptIn(ExperimentalForeignApi::class)
fun listKotlinFiles(directory: String): Result<List<String>, ListFilesFailed> {
    val files = mutableListOf<String>()
    collectKotlinFiles(directory, files).let { error ->
        if (error != null) return Err(error)
    }
    files.sort()
    return Ok(files)
}

// Expand a caller-supplied source path list to a flat list of .kt files.
// Directory entries are walked recursively via `collectKotlinFiles`;
// individual .kt files are kept verbatim; anything else (non-.kt file, or a
// path that does not exist) is kept as-is so the caller can still surface
// a useful backend error.
//
// Motivation: Phase A (kotlin-compiler-embeddable) accepted directories on
// the kotlinc command line and walked them implicitly. Phase B's BTA
// (`jvmCompilationOperationBuilder(sources: List<Path>, ...)`) requires
// individual file paths and reports `Is a directory` on the first entry
// that happens to be a directory — which turns a typical
// `sources = ["src"]` into a hard failure. Expanding here keeps both
// backends happy with one call site. See issue #117.
//
// Order is stable: entries within each directory are sorted by
// `listKotlinFiles`, and the overall order preserves the caller's
// `sources` list position so kotlinc / BTA see the same module-boundary
// order a hand-written CLI would produce.
fun expandKotlinSources(sources: List<String>): Result<List<String>, ListFilesFailed> {
    val expanded = mutableListOf<String>()
    for (entry in sources) {
        when {
            isDirectory(entry) -> {
                val found = listKotlinFiles(entry).getOrElse { err -> return Err(err) }
                expanded.addAll(found)
            }
            else -> expanded.add(entry)
        }
    }
    return Ok(expanded)
}

@OptIn(ExperimentalForeignApi::class)
private fun collectKotlinFiles(directory: String, result: MutableList<String>): ListFilesFailed? {
    val dir = opendir(directory) ?: return ListFilesFailed(directory)
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            val childPath = "$directory/$name"
            if (isDirectory(childPath)) {
                collectKotlinFiles(childPath, result)?.let { return it }
            } else if (name.endsWith(".kt")) {
                result.add(childPath)
            }
        }
    } finally {
        closedir(dir)
    }
    return null
}

data class CopyFailed(val path: String)

fun copyDirectoryContents(src: String, dest: String): Result<Unit, CopyFailed> {
    if (!fileExists(src)) return Err(CopyFailed(src))
    return copyDirRecursive(src, dest)
}

@OptIn(ExperimentalForeignApi::class)
private fun copyDirRecursive(src: String, dest: String): Result<Unit, CopyFailed> {
    val dir = opendir(src) ?: return Err(CopyFailed(src))
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            val srcChild = "$src/$name"
            val destChild = "$dest/$name"
            if (isDirectory(srcChild)) {
                ensureDirectory(destChild).getOrElse { return Err(CopyFailed(destChild)) }
                copyDirRecursive(srcChild, destChild).getOrElse { return Err(it) }
            } else {
                copyFile(srcChild, destChild).getOrElse { return Err(it) }
            }
        }
    } finally {
        closedir(dir)
    }
    return Ok(Unit)
}

@OptIn(ExperimentalForeignApi::class)
private fun copyFile(src: String, dest: String): Result<Unit, CopyFailed> {
    val srcFp = fopen(src, "rb") ?: return Err(CopyFailed(src))
    try {
        val destFp = fopen(dest, "wb") ?: return Err(CopyFailed(dest))
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(4096)
                while (true) {
                    val bytesRead = fread(buffer, 1u, 4096u, srcFp)
                    if (bytesRead == 0uL) break
                    val written = fwrite(buffer, 1u, bytesRead, destFp)
                    if (written != bytesRead) return Err(CopyFailed(dest))
                }
            }
        } finally {
            fclose(destFp)
        }
    } finally {
        fclose(srcFp)
    }
    return Ok(Unit)
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

/**
 * Returns the absolute path of the current working directory, or
 * `null` if `getcwd(3)` fails. Distinct from [homeDirectory] so
 * callers can use a cwd they just entered without re-reading `$HOME`.
 */
@OptIn(ExperimentalForeignApi::class)
fun currentWorkingDirectory(): String? = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX)
    getcwd(buf, PATH_MAX.toULong())?.toKString()
}

/**
 * If [path] starts with `/`, returns it unchanged. Otherwise returns
 * `"$cwd/$path"` (with any trailing slash on [cwd] collapsed so the
 * result never contains `//`). This is deliberately not a
 * path-canonicalising helper — it does **not** resolve `..`, `.`,
 * or symlinks. It exists so [doBuild] can hand absolute source /
 * output paths to any backend without relying on the backend
 * inheriting the kolt process's cwd, which is a load-bearing
 * assumption today for the daemon path (see ADR 0016 §3 and the
 * note on `CompileRequest.workingDir`).
 */
fun absolutise(path: String, cwd: String): String {
    if (path.startsWith('/')) return path
    val base = if (cwd.endsWith('/')) cwd.dropLast(1) else cwd
    return "$base/$path"
}

@OptIn(ExperimentalForeignApi::class)
fun eprintln(msg: String) {
    val bytes = (msg + "\n").encodeToByteArray()
    bytes.usePinned { pinned ->
        write(STDERR_FILENO, pinned.addressOf(0), bytes.size.toULong())
    }
}

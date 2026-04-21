package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.config.resolveKoltPaths
import kolt.infra.RemoveFailed
import kolt.infra.directorySize
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.infra.formatBytes
import kolt.infra.removeDirectoryRecursive

internal data class CacheCleanArgs(val includeTools: Boolean)

// `error` is non-null when removal of one of the targets fails; the
// `removedPaths` and `freedBytes` fields still report whatever was
// successfully cleared up to that point so the caller can tell the
// user how much was reclaimed before the failure.
internal data class CacheCleanResult(
    val removedPaths: List<String>,
    val freedBytes: Long,
    val error: RemoveFailed? = null,
)

internal fun parseCacheCleanArgs(args: List<String>): Result<CacheCleanArgs, String> {
    var includeTools = false
    for (arg in args) {
        when (arg) {
            "--tools" -> includeTools = true
            else -> return Err("error: unknown flag '$arg'")
        }
    }
    return Ok(CacheCleanArgs(includeTools))
}

internal fun doCache(args: List<String>): Result<Unit, Int> {
    if (args.isEmpty()) {
        printCacheUsage()
        return Err(EXIT_CONFIG_ERROR)
    }
    return when (args[0]) {
        "clean" -> doCacheClean(args.drop(1))
        else -> {
            printCacheUsage()
            Err(EXIT_CONFIG_ERROR)
        }
    }
}

internal fun cleanCacheDirs(paths: KoltPaths, includeTools: Boolean): CacheCleanResult {
    val targets = mutableListOf(paths.cacheBase)
    if (includeTools) targets.add(paths.toolsDir)

    val removed = mutableListOf<String>()
    var freed = 0L
    for (target in targets) {
        if (!fileExists(target)) continue
        // Size is computed before removal but only credited *after* a
        // successful remove, so a partial failure doesn't claim freed
        // bytes for a target that's still on disk.
        val targetSize = directorySize(target)
        val err = removeDirectoryRecursive(target).getError()
        if (err != null) {
            return CacheCleanResult(removed, freed, err)
        }
        freed += targetSize
        removed.add(target)
    }
    return CacheCleanResult(removed, freed)
}

private fun doCacheClean(args: List<String>): Result<Unit, Int> {
    val parsed = parseCacheCleanArgs(args).getOrElse { error ->
        eprintln(error)
        printCacheUsage()
        return Err(EXIT_CONFIG_ERROR)
    }

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_CONFIG_ERROR) }

    val result = cleanCacheDirs(paths, parsed.includeTools)
    for (path in result.removedPaths) println("removed $path")

    if (result.error != null) {
        eprintln("error: could not remove ${result.error.path}")
        if (result.freedBytes > 0) {
            println("partially freed ${formatBytes(result.freedBytes)} before failure")
        }
        return Err(EXIT_BUILD_ERROR)
    }

    if (result.freedBytes == 0L) {
        println("nothing to clean")
    } else {
        println("freed ${formatBytes(result.freedBytes)}")
    }
    return Ok(Unit)
}

private fun printCacheUsage() {
    eprintln("usage: kolt cache <subcommand>")
    eprintln("")
    eprintln("subcommands:")
    eprintln("  clean         Remove the global dependency cache")
    eprintln("")
    eprintln("flags:")
    eprintln("  --tools       Also remove cached tools (ktfmt, junit-console)")
}

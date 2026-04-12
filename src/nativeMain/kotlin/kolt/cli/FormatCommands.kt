package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.build.formatCommand
import kolt.config.*
import kolt.infra.*
import kolt.tool.*
import kotlin.system.exitProcess

internal fun doFmt(args: List<String>) {
    val checkOnly = "--check" in args

    val config = loadProjectConfig()

    val paths = resolveKoltPaths(EXIT_FORMAT_ERROR)
    val ktfmtPath = ensureTool(paths, KTFMT_SPEC, EXIT_FORMAT_ERROR)

    val files = buildList {
        for (dir in config.sources) {
            if (isDirectory(dir)) {
                addAll(listKotlinFiles(dir).getOrElse { error ->
                    eprintln("error: could not read directory ${error.path}")
                    exitProcess(EXIT_FORMAT_ERROR)
                })
            }
        }
        for (dir in config.testSources) {
            if (isDirectory(dir)) {
                addAll(listKotlinFiles(dir).getOrElse { error ->
                    eprintln("error: could not read directory ${error.path}")
                    exitProcess(EXIT_FORMAT_ERROR)
                })
            }
        }
    }

    if (files.isEmpty()) {
        println("no kotlin files to format")
        return
    }

    val cmd = formatCommand(ktfmtPath, files, checkOnly, style = config.fmtStyle)

    if (checkOnly) {
        println("checking format...")
    } else {
        println("formatting ${files.size} files...")
    }

    executeCommand(cmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> eprintln(if (checkOnly) "error: format check failed" else "error: formatting failed")
            else -> eprintln("error: failed to run ktfmt")
        }
        exitProcess(EXIT_FORMAT_ERROR)
    }

    if (checkOnly) {
        println("format check passed")
    } else {
        println("formatted ${files.size} files")
    }
}

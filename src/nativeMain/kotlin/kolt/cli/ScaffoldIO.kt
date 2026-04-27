package kolt.cli

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.STDIN_FILENO
import platform.posix.isatty

internal interface ScaffoldIO {
  fun isStdinTty(): Boolean

  fun readLine(): String?

  fun println(msg: String)
}

internal object SystemScaffoldIO : ScaffoldIO {
  @OptIn(ExperimentalForeignApi::class)
  override fun isStdinTty(): Boolean = isatty(STDIN_FILENO) == 1

  override fun readLine(): String? = readlnOrNull()

  override fun println(msg: String) = kotlin.io.println(msg)
}

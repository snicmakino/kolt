package bench

import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader

class IsolatingCompileDriver(
    private val compilerJars: List<File>,
    private val compileClasspath: List<File> = emptyList(),
) {
    fun compile(sources: List<File>, outputDir: File): CompileResult {
        val urls = compilerJars.map { it.toURI().toURL() }.toTypedArray()
        val loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())

        val args = buildCompileArgs(sources, outputDir, compileClasspath)
        val compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", true, loader)
        val compiler = compilerClass.getDeclaredConstructor().newInstance()
        val cliCompiler = Class.forName("org.jetbrains.kotlin.cli.common.CLICompiler", true, loader)
        val execMethod = cliCompiler.getMethod("exec", PrintStream::class.java, Array<String>::class.java)

        val exitCode = execMethod.invoke(compiler, nullPrintStream, args) as Enum<*>
        loader.close()
        return if (exitCode.name == "OK") CompileResult.Ok
        else CompileResult.Failed(exitCode.name, emptyList())
    }

    companion object {
        private val nullPrintStream = PrintStream(java.io.OutputStream.nullOutputStream())
    }
}

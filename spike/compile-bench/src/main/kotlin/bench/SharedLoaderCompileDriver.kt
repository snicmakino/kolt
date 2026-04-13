package bench

import java.io.File
import java.io.PrintStream
import java.lang.reflect.Method
import java.net.URLClassLoader

class SharedLoaderCompileDriver(
    compilerJars: List<File>,
    private val compileClasspath: List<File> = emptyList(),
) {
    private val loader: URLClassLoader
    private val compiler: Any
    private val execMethod: Method

    init {
        val urls = compilerJars.map { it.toURI().toURL() }.toTypedArray()
        loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
        val compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", true, loader)
        compiler = compilerClass.getDeclaredConstructor().newInstance()
        val cliCompiler = Class.forName("org.jetbrains.kotlin.cli.common.CLICompiler", true, loader)
        execMethod = cliCompiler.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
    }

    fun compile(sources: List<File>, outputDir: File): CompileResult {
        val args = buildCompileArgs(sources, outputDir, compileClasspath)
        val exitCode = execMethod.invoke(compiler, nullPrintStream, args) as Enum<*>
        return if (exitCode.name == "OK") CompileResult.Ok
        else CompileResult.Failed(exitCode.name, emptyList())
    }

    companion object {
        private val nullPrintStream = PrintStream(java.io.OutputStream.nullOutputStream())
    }
}

internal fun buildCompileArgs(
    sources: List<File>,
    outputDir: File,
    compileClasspath: List<File>,
): Array<String> {
    val cp = compileClasspath.joinToString(File.pathSeparator) { it.absolutePath }
    val args = mutableListOf(
        "-d", outputDir.absolutePath,
        "-no-stdlib",
        "-no-reflect",
    )
    if (cp.isNotEmpty()) {
        args += "-classpath"
        args += cp
    }
    sources.forEach { args += it.absolutePath }
    return args.toTypedArray()
}

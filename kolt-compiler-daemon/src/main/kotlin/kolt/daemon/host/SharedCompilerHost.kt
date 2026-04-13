package kolt.daemon.host

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader

data class CompileRequest(
    val sources: List<String>,
    val classpath: List<String>,
    val outputPath: String,
    val moduleName: String,
    val extraArgs: List<String> = emptyList(),
)

data class CompileOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

sealed interface CompileHostError {
    data class LoaderInitFailed(val reason: String) : CompileHostError
    data class ReflectionFailed(val reason: String) : CompileHostError
}

interface CompilerHost {
    fun compile(request: CompileRequest): Result<CompileOutcome, CompileHostError>
}

class SharedCompilerHost private constructor(
    private val loader: URLClassLoader,
    private val compiler: Any,
    private val execMethod: Method,
) : CompilerHost {

    // @Synchronized is a tripwire: DaemonServer serialises connections today, but a future
    // concurrency change must not silently race the shared K2JVMCompiler instance. See #91.
    @Synchronized
    override fun compile(request: CompileRequest): Result<CompileOutcome, CompileHostError> {
        val args = buildArgs(request)
        val captured = ByteArrayOutputStream()
        val sink = PrintStream(captured, true, Charsets.UTF_8)

        val exitEnum = try {
            execMethod.invoke(compiler, sink, args)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            return Err(CompileHostError.ReflectionFailed(cause.message ?: cause.javaClass.name))
        } catch (e: IllegalAccessException) {
            return Err(CompileHostError.ReflectionFailed(e.message ?: "illegal access"))
        } as Enum<*>

        val exitCode = exitCodeFromEnum(exitEnum.name)
        return Ok(
            CompileOutcome(
                exitCode = exitCode,
                stdout = "",
                stderr = captured.toString(Charsets.UTF_8),
            ),
        )
    }

    private fun buildArgs(request: CompileRequest): Array<String> {
        val args = mutableListOf(
            "-d", request.outputPath,
            "-module-name", request.moduleName,
        )
        if (request.classpath.isNotEmpty()) {
            args += "-classpath"
            args += request.classpath.joinToString(File.pathSeparator)
        }
        args += request.extraArgs
        args += request.sources
        return args.toTypedArray()
    }

    private fun exitCodeFromEnum(name: String): Int = when (name) {
        "OK" -> 0
        "COMPILATION_ERROR" -> 1
        "INTERNAL_ERROR" -> 2
        "SCRIPT_EXECUTION_ERROR" -> 3
        "OOM_ERROR" -> 4
        else -> 5
    }

    companion object {
        fun create(compilerJars: List<File>): Result<SharedCompilerHost, CompileHostError.LoaderInitFailed> {
            return try {
                val urls = compilerJars.map { it.toURI().toURL() }.toTypedArray()
                val loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
                val compilerClass = Class.forName(
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    true,
                    loader,
                )
                val compiler = compilerClass.getDeclaredConstructor().newInstance()
                val cliCompilerClass = Class.forName(
                    "org.jetbrains.kotlin.cli.common.CLICompiler",
                    true,
                    loader,
                )
                val execMethod = cliCompilerClass.getMethod(
                    "exec",
                    PrintStream::class.java,
                    Array<String>::class.java,
                )
                Ok(SharedCompilerHost(loader, compiler, execMethod))
            } catch (e: ReflectiveOperationException) {
                Err(CompileHostError.LoaderInitFailed(e.message ?: e.javaClass.name))
            } catch (e: LinkageError) {
                Err(CompileHostError.LoaderInitFailed(e.message ?: e.javaClass.name))
            }
        }
    }
}

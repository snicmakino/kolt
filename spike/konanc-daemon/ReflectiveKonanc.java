import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Spike #166: Verify that K2Native can be invoked repeatedly within a single
 * JVM process (daemon feasibility). Loads K2Native via URLClassLoader and
 * calls exec(PrintStream, String[]) N times, printing wall time per call.
 *
 * Usage: java -Dkonan.home=<KONAN_HOME> ReflectiveKonanc <N> <konanc-args...>
 *   N = number of repeated invocations (use 1 for single-shot baseline)
 *
 * Example:
 *   java -Dkonan.home=$KONAN_HOME -Xmx3G \
 *       -cp .:$KONAN_HOME/konan/lib/kotlin-native-compiler-embeddable.jar \
 *       ReflectiveKonanc 5 -target linux_x64 src/Main.kt -p library -nopack -o build/out
 */
public class ReflectiveKonanc {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ReflectiveKonanc <N> <konanc-args...>");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);
        String[] konanArgs = Arrays.copyOfRange(args, 1, args.length);

        System.err.println("ReflectiveKonanc: " + n + " invocations, args=" + Arrays.toString(konanArgs));

        // Load K2Native class. It extends CLICompiler which has:
        //   fun exec(errStream: PrintStream, vararg args: String): ExitCode
        // ExitCode is an enum with OK, COMPILATION_ERROR, INTERNAL_ERROR, etc.
        Class<?> k2NativeClass = Class.forName("org.jetbrains.kotlin.cli.bc.K2Native");
        Object k2Native = k2NativeClass.getDeclaredConstructor().newInstance();

        // CLICompiler.exec(PrintStream, String...)
        Method execMethod = findExecMethod(k2NativeClass);
        System.err.println("ReflectiveKonanc: found exec method: " + execMethod);

        // Suppress compiler output for timing; capture stderr separately.
        PrintStream devNull = new PrintStream(OutputStream.nullOutputStream());

        long[] wallMs = new long[n];
        String[] exitCodes = new String[n];
        long[] heapMb = new long[n];

        Runtime rt = Runtime.getRuntime();

        for (int i = 0; i < n; i++) {
            long start = System.nanoTime();
            Object exitCode;
            try {
                exitCode = execMethod.invoke(k2Native, devNull, konanArgs);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                System.err.printf("invocation %d: EXCEPTION %s: %s%n",
                    i + 1, cause.getClass().getName(), cause.getMessage());
                exitCodes[i] = "EXCEPTION";
                wallMs[i] = (System.nanoTime() - start) / 1_000_000;
                heapMb[i] = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                continue;
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            wallMs[i] = elapsed;
            exitCodes[i] = exitCode.toString();
            heapMb[i] = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            System.err.printf("invocation %d: %s  %dms  heap=%dMB%n",
                i + 1, exitCode, elapsed, heapMb[i]);
        }

        // Print structured output for the benchmark harness.
        System.out.println("# ReflectiveKonanc results");
        System.out.println("invocations: " + n);
        System.out.println("args: " + Arrays.toString(konanArgs));
        for (int i = 0; i < n; i++) {
            System.out.printf("run %d: %s %dms %dMB%n", i + 1, exitCodes[i], wallMs[i], heapMb[i]);
        }
    }

    /**
     * Find the exec(PrintStream, String[]) method. CLICompiler declares it
     * as a varargs method which compiles to exec(PrintStream, String[]).
     */
    private static Method findExecMethod(Class<?> clazz) throws NoSuchMethodException {
        // Walk up the hierarchy to find exec(PrintStream, String[])
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("exec") || m.getName().equals("execImpl")) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2
                            && params[0] == PrintStream.class
                            && params[1] == String[].class) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        }
        // Fallback: try the public API
        return clazz.getMethod("exec", PrintStream.class, String[].class);
    }
}

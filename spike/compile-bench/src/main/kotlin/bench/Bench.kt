package bench

import java.io.File
import kotlin.system.measureTimeMillis

fun main() {
    val jars = cpProp("kotlinc.classpath")
    val fixtureCp = cpProp("fixture.classpath")
    val sources = listOf(File("fixtures/Hello.kt").absoluteFile)
    check(sources.first().exists()) { "missing fixture: ${sources.first().path}" }

    val iterations = 10

    println("=== Scenario A: shared URLClassLoader (baseline, n=$iterations) ===")
    val shared = SharedLoaderCompileDriver(jars, fixtureCp)
    val sharedTimes = runScenario(iterations) { shared.compile(sources, freshOutputDir()) }
    reportTimes(sharedTimes)

    System.gc()
    Thread.sleep(200)

    println()
    println("=== Scenario B: fresh URLClassLoader per compile (isolated, n=$iterations) ===")
    val iso = IsolatingCompileDriver(jars, fixtureCp)
    val isoTimes = runScenario(iterations) { iso.compile(sources, freshOutputDir()) }
    reportTimes(isoTimes)

    System.gc()
    Thread.sleep(200)

    val longN = 50
    println()
    println("=== Scenario C: shared loader long run (n=$longN, watch-mode simulation) ===")
    val sharedC = SharedLoaderCompileDriver(jars, fixtureCp)
    val memBefore = usedHeapMb()
    val longTimes = runScenario(longN) { sharedC.compile(sources, freshOutputDir()) }
    System.gc()
    Thread.sleep(200)
    val memAfter = usedHeapMb()
    reportTimes(longTimes)
    println("  heap before: $memBefore MB")
    println("  heap after : $memAfter MB")
    println("  heap delta : ${memAfter - memBefore} MB")
    val firstQuarter = longTimes.drop(1).take(longN / 4).average()
    val lastQuarter = longTimes.takeLast(longN / 4).average()
    println("  early warm avg : ${firstQuarter.toLong()} ms")
    println("  late  warm avg : ${lastQuarter.toLong()} ms")
    println("  drift (late - early): ${(lastQuarter - firstQuarter).toLong()} ms")

    val sharedWarm = sharedTimes.drop(1).average()
    val isoWarm = isoTimes.drop(1).average()
    val overhead = (isoWarm - sharedWarm).toLong()
    val gate = 1000L
    val verdictB = if (overhead < gate) "PASS" else "FAIL"
    val driftGate = 500L
    val verdictC = if ((lastQuarter - firstQuarter) < driftGate && (memAfter - memBefore) < 200) "PASS" else "FAIL"

    println()
    println("=== Verdict ===")
    println("Scenario A (shared warm avg): ${sharedWarm.toLong()} ms")
    println("Scenario B (isolated warm avg): ${isoWarm.toLong()} ms")
    println("Scenario B overhead: $overhead ms  ->  kill criterion < $gate ms  ->  $verdictB")
    println("Scenario C drift: ${(lastQuarter - firstQuarter).toLong()} ms, heap delta: ${memAfter - memBefore} MB  ->  $verdictC")
}

private fun usedHeapMb(): Long {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
}

private fun runScenario(n: Int, block: () -> CompileResult): List<Long> = List(n) {
    var result: CompileResult? = null
    val ms = measureTimeMillis { result = block() }
    check(result is CompileResult.Ok) { "compile failed: $result" }
    ms
}

private fun reportTimes(ms: List<Long>) {
    val cold = ms.first()
    val warm2 = ms.getOrNull(1) ?: -1L
    val sorted = ms.sorted()
    val median = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    val avgWarm = ms.drop(1).average().toLong()
    println("  cold   : $cold ms")
    println("  warm-2 : $warm2 ms")
    println("  median : $median ms")
    println("  warm avg (excl. cold): $avgWarm ms")
    println("  all    : $ms")
}

private fun freshOutputDir(): File {
    val d = File.createTempFile("bench-out-", "").apply { delete() }
    d.mkdirs()
    d.deleteOnExit()
    return d
}

private fun cpProp(key: String): List<File> =
    (System.getProperty(key) ?: error("$key not set"))
        .split(File.pathSeparatorChar).map(::File)

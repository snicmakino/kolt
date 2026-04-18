package bench

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
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

    System.gc()
    Thread.sleep(200)

    val rotatingDir = File("fixtures/rotating").absoluteFile
    val rotatingSources = rotatingDir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }
        ?.sortedBy { it.name }
        ?: error("missing rotating fixtures dir: ${rotatingDir.path}")
    check(rotatingSources.size >= 10) {
        "expected at least 10 rotating fixtures, got ${rotatingSources.size}"
    }

    // n=50 mirrors Scenario C for apples-to-apples comparison. Note that with 10 fixtures
    // the first and last quarters (12 samples each) see slightly different fixture mixes;
    // the rotation/JIT noise floor is larger than per-fixture variance so this is tolerated.
    val rotatingN = 50
    println()
    println("=== Scenario D: shared loader rotating fixtures (n=$rotatingN, ${rotatingSources.size} files) ===")
    val sharedD = SharedLoaderCompileDriver(jars, fixtureCp)
    val memBeforeD = usedHeapMb()
    val rotatingTimes = List(rotatingN) { i ->
        var result: CompileResult? = null
        val source = listOf(rotatingSources[i % rotatingSources.size])
        val ms = measureTimeMillis { result = sharedD.compile(source, freshOutputDir()) }
        check(result is CompileResult.Ok) { "rotating compile failed at i=$i (${source.first().name}): $result" }
        ms
    }
    System.gc()
    Thread.sleep(200)
    val memAfterD = usedHeapMb()
    reportTimes(rotatingTimes)
    println("  heap before: $memBeforeD MB")
    println("  heap after : $memAfterD MB")
    println("  heap delta : ${memAfterD - memBeforeD} MB")
    val firstQuarterD = rotatingTimes.drop(1).take(rotatingN / 4).average()
    val lastQuarterD = rotatingTimes.takeLast(rotatingN / 4).average()
    println("  early warm avg : ${firstQuarterD.toLong()} ms")
    println("  late  warm avg : ${lastQuarterD.toLong()} ms")
    println("  drift (late - early): ${(lastQuarterD - firstQuarterD).toLong()} ms")

    val sharedWarm = sharedTimes.drop(1).average()
    val isoWarm = isoTimes.drop(1).average()
    val overhead = (isoWarm - sharedWarm).toLong()
    val gate = 1000L
    val verdictB = if (overhead < gate) "PASS" else "FAIL"
    val driftGate = 500L
    val verdictC = if ((lastQuarter - firstQuarter) < driftGate && (memAfter - memBefore) < 200) "PASS" else "FAIL"

    val rotatingWarm = rotatingTimes.drop(1).average()
    // Gate D against C (same n=50, same shared loader, single hot file) so the ratio
    // isolates the rotation effect. A (n=10 Hello.kt) is kept as info only — comparing
    // rotating non-trivial fixtures to a trivial stub would measure fixture size, not
    // shared-loader reuse under rotation.
    val longWarm = longTimes.drop(1).average()
    val rotatingRatio = rotatingWarm / longWarm
    val rotatingRatioA = rotatingWarm / sharedWarm
    // Signed drift: we only want to FAIL on late-window regressions (leak / fragmentation).
    // A strongly negative drift means the JIT is still improving at iteration ~40, which
    // is healthy — do not switch this to abs().
    val rotatingDrift = (lastQuarterD - firstQuarterD)
    val rotatingHeapDelta = memAfterD - memBeforeD
    val rotatingRatioGate = 2.0
    val rotatingDriftGate = 200L
    val rotatingHeapGate = 50L
    val verdictD = if (
        rotatingRatio < rotatingRatioGate &&
        rotatingDrift < rotatingDriftGate &&
        rotatingHeapDelta < rotatingHeapGate
    ) "PASS" else "FAIL"

    println()
    println("=== Verdict ===")
    println("Scenario A (shared warm avg): ${sharedWarm.toLong()} ms")
    println("Scenario B (isolated warm avg): ${isoWarm.toLong()} ms")
    println("Scenario B overhead: $overhead ms  ->  kill criterion < $gate ms  ->  $verdictB")
    println("Scenario C drift: ${(lastQuarter - firstQuarter).toLong()} ms, heap delta: ${memAfter - memBefore} MB  ->  $verdictC")
    println("Scenario D (rotating warm avg): ${rotatingWarm.toLong()} ms")
    println(
        "Scenario D ratio vs C: ${"%.2f".format(rotatingRatio)}x (gate < ${rotatingRatioGate}x), " +
            "drift: ${rotatingDrift.toLong()} ms (gate < $rotatingDriftGate ms), " +
            "heap delta: $rotatingHeapDelta MB (gate < $rotatingHeapGate MB)  ->  $verdictD"
    )
    println("Scenario D ratio vs A (info only): ${"%.2f".format(rotatingRatioA)}x")

    System.gc()
    Thread.sleep(200)

    // Scenario F: concurrent compiles on a shared URLClassLoader (#91).
    // Warm the loader first so t=2 isn't dominated by the ~2.5s cold JIT cost —
    // scaling against rotatingWarm (D) is only meaningful from a hot state.
    val concurrentPerThread = 20
    // Include t=1 as an inline serial baseline. Taking it on the same driver right
    // before the concurrent runs gives an apples-to-apples comparison; the external
    // rotatingWarm baseline from D is too noisy (early warm ~4x late warm) to use
    // as the denominator for small-thread-count scaling.
    val concurrentThreadCounts = listOf(1, 2, 4, 8)
    println()
    println(
        "=== Scenario F: shared loader concurrent compiles " +
            "(per-thread=$concurrentPerThread, threads=$concurrentThreadCounts) ==="
    )
    val sharedF = SharedLoaderCompileDriver(jars, fixtureCp)
    repeat(10) { sharedF.compile(sources, freshOutputDir()) }

    val concurrentResults = mutableListOf<ConcurrentResult>()
    for (t in concurrentThreadCounts) {
        concurrentResults += runConcurrent(sharedF, rotatingSources, t, concurrentPerThread)
        System.gc()
        Thread.sleep(200)
    }

    concurrentResults.forEach { r ->
        val avg = r.perCompileTimes.average().toLong()
        val sorted = r.perCompileTimes.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        val throughput = r.totalCompiles * 1000.0 / r.wallMs
        println(
            "  t=${r.threads}: wall=${r.wallMs} ms, compiles=${r.totalCompiles}, failures=${r.failures}, " +
                "per-compile avg=$avg ms, median=$median ms, throughput=${"%.2f".format(throughput)} compiles/sec"
        )
        r.firstFailure?.let { e ->
            println("  first failure: ${e::class.qualifiedName}: ${e.message}")
            e.stackTrace.take(6).forEach { println("    at $it") }
            var cause = e.cause
            while (cause != null && cause !== e) {
                println("  caused by: ${cause::class.qualifiedName}: ${cause.message}")
                cause.stackTrace.take(3).forEach { println("    at $it") }
                cause = cause.cause
            }
        }
    }

    val t1 = concurrentResults.first { it.threads == 1 }
    val serialThroughputF = t1.totalCompiles * 1000.0 / t1.wallMs
    val t2 = concurrentResults.first { it.threads == 2 }
    val t2Throughput = t2.totalCompiles * 1000.0 / t2.wallMs
    val t2Scaling = t2Throughput / serialThroughputF
    val scalingGate = 1.5
    val anyFailures = concurrentResults.any { it.failures > 0 }
    val verdictF = if (!anyFailures && t2Scaling >= scalingGate) "PASS" else "FAIL"

    println()
    println("=== Scenario F Verdict ===")
    println(
        "Serial baseline (inline t=1, same driver): ${t1.wallMs * 1.0 / t1.totalCompiles} ms/compile, " +
            "${"%.2f".format(serialThroughputF)} compiles/sec"
    )
    concurrentResults.forEach { r ->
        val throughput = r.totalCompiles * 1000.0 / r.wallMs
        val scaling = throughput / serialThroughputF
        println(
            "  t=${r.threads}: ${"%.2f".format(throughput)} compiles/sec, " +
                "scaling=${"%.2f".format(scaling)}x, failures=${r.failures}"
        )
    }
    println(
        "Decision: failures=${concurrentResults.sumOf { it.failures }}, " +
            "t=2 scaling=${"%.2f".format(t2Scaling)}x (gate >= ${scalingGate}x)  ->  $verdictF"
    )

    System.gc()
    Thread.sleep(200)

    // Scenario E is gated behind an env flag because a 1000-iteration run takes several
    // minutes. Default off so the routine spike run stays fast; enable with
    //   BENCH_LONG_RUN=1 ./gradlew run
    // or a number to override the iteration count, e.g. BENCH_LONG_RUN=500.
    val longRunFlag = System.getenv("BENCH_LONG_RUN")
    if (longRunFlag != null) {
        // "=1" is the common enable-flag idiom but it also round-trips as "run 1 iter",
        // which produces empty windows and NaN drift. Require a minimum that keeps the
        // drift windows disjoint and non-trivial. Unparseable input is an error, not a
        // silent default — the user asked for the long run explicitly.
        val minLeakN = 400
        val leakN = longRunFlag.toIntOrNull()
            ?: error("BENCH_LONG_RUN must be an integer, got: $longRunFlag")
        check(leakN >= minLeakN) { "BENCH_LONG_RUN must be >= $minLeakN, got: $leakN" }
        val sampleEvery = 50
        println()
        println("=== Scenario E: shared loader long-run leak check (n=$leakN, sample every $sampleEvery) ===")
        val sharedE = SharedLoaderCompileDriver(jars, fixtureCp)
        // Sample heap after forced GC so live-set is visible, not floating garbage.
        System.gc()
        Thread.sleep(200)
        val baselineHeap = usedHeapMb()
        println("  baseline heap (post-gc, pre-compile): $baselineHeap MB")
        val heapSamples = mutableListOf(HeapSample(0, baselineHeap))
        val leakTimes = ArrayList<Long>(leakN)
        val wallStart = System.currentTimeMillis()
        for (i in 0 until leakN) {
            var result: CompileResult? = null
            val ms = measureTimeMillis { result = sharedE.compile(sources, freshOutputDir()) }
            check(result is CompileResult.Ok) { "long-run compile failed at i=$i: $result" }
            leakTimes += ms
            if ((i + 1) % sampleEvery == 0) {
                System.gc()
                Thread.sleep(100)
                val sample = HeapSample(i + 1, usedHeapMb())
                heapSamples += sample
                val elapsedSec = (System.currentTimeMillis() - wallStart) / 1000
                println("  iter=${sample.iter.toString().padStart(4)}  heap=${sample.heapMb.toString().padStart(4)} MB  t+${elapsedSec}s  last compile=${ms} ms")
            }
        }
        val totalWallSec = (System.currentTimeMillis() - wallStart) / 1000
        println("  total wall: ${totalWallSec}s")

        reportTimes(leakTimes)

        // Time drift gate: first N warm samples vs last N, disjoint.
        // minLeakN guarantees warmTimes.size >= 399 so windowSize is always 100.
        val warmTimes = leakTimes.drop(1)
        val windowSize = 100
        check(warmTimes.size >= 2 * windowSize) {
            "warmTimes too small for disjoint windows: ${warmTimes.size}"
        }
        val firstWindow = warmTimes.take(windowSize).average()
        val lastWindow = warmTimes.takeLast(windowSize).average()
        val timeDriftRatio = lastWindow / firstWindow
        println("  first $windowSize warm avg: ${firstWindow.toLong()} ms")
        println("  last  $windowSize warm avg: ${lastWindow.toLong()} ms")
        println("  time drift ratio (last/first): ${"%.2f".format(timeDriftRatio)}x")

        // Plateau detection: find the first post-baseline sample `i` such that every
        // subsequent sample is within ±slack of sample[i]. This measures *suffix
        // flatness*, not "is sample[i] close to the future max" — the latter would
        // trivially match the baseline whenever total growth fits in slack.
        // The baseline sample (iter=0, pre-compile) is excluded from candidates: it's
        // the starting state, not steady state.
        val plateauSlack = 20L
        val postBaseline = heapSamples.drop(1)
        val plateauIter = run {
            for (idx in postBaseline.indices) {
                val suffix = postBaseline.subList(idx, postBaseline.size)
                val suffixMax = suffix.maxOf { it.heapMb }
                val suffixMin = suffix.minOf { it.heapMb }
                if (suffixMax - suffixMin <= plateauSlack) {
                    return@run postBaseline[idx].iter
                }
            }
            null
        }
        val maxHeap = heapSamples.maxOf { it.heapMb }
        val heapDeltaTotal = maxHeap - baselineHeap
        println("  max heap observed: $maxHeap MB")
        println("  heap delta (max - baseline): $heapDeltaTotal MB")
        println("  plateau iter (within ${plateauSlack} MB): ${plateauIter ?: "none"}")

        val plateauIterGate = 500
        val maxHeapGate = 512L
        val timeDriftGate = 1.20
        val verdictE = if (
            plateauIter != null && plateauIter <= plateauIterGate &&
            maxHeap < maxHeapGate &&
            timeDriftRatio < timeDriftGate
        ) "PASS" else "FAIL"
        println()
        println("=== Scenario E Verdict ===")
        println(
            "plateau iter: ${plateauIter ?: "none"} (gate <= $plateauIterGate), " +
                "max heap: $maxHeap MB (gate < $maxHeapGate MB), " +
                "time drift: ${"%.2f".format(timeDriftRatio)}x (gate < ${timeDriftGate}x)  ->  $verdictE"
        )
    } else {
        println()
        println("(Scenario E skipped — set BENCH_LONG_RUN=1 to enable the 1000-iter leak check)")
    }
}

private data class HeapSample(val iter: Int, val heapMb: Long)

private data class ConcurrentResult(
    val threads: Int,
    val wallMs: Long,
    val totalCompiles: Int,
    val failures: Int,
    val firstFailure: Throwable?,
    val perCompileTimes: List<Long>,
)

private fun runConcurrent(
    driver: SharedLoaderCompileDriver,
    rotatingSources: List<File>,
    threads: Int,
    perThread: Int,
): ConcurrentResult {
    val startLatch = CountDownLatch(1)
    val allTimes = Array(threads) { LongArray(perThread) }
    val failureCounts = IntArray(threads)
    val firstErrors = arrayOfNulls<Throwable>(threads)
    val workers = (0 until threads).map { idx ->
        Thread({
            startLatch.await()
            for (i in 0 until perThread) {
                val src = listOf(rotatingSources[(idx * perThread + i) % rotatingSources.size])
                var result: CompileResult? = null
                var error: Throwable? = null
                val ms = measureTimeMillis {
                    try {
                        result = driver.compile(src, freshOutputDir())
                    } catch (e: Throwable) {
                        error = if (e is InvocationTargetException) (e.cause ?: e) else e
                    }
                }
                allTimes[idx][i] = ms
                if (error != null || result !is CompileResult.Ok) {
                    failureCounts[idx] += 1
                    if (firstErrors[idx] == null) {
                        firstErrors[idx] = error
                            ?: RuntimeException("compile failed: $result")
                    }
                }
            }
        }, "bench-f-t$threads-$idx").apply { isDaemon = true; start() }
    }
    val wallStart = System.currentTimeMillis()
    startLatch.countDown()
    workers.forEach { it.join() }
    val wallMs = System.currentTimeMillis() - wallStart
    val firstFailure = firstErrors.firstOrNull { it != null }
    return ConcurrentResult(
        threads = threads,
        wallMs = wallMs,
        totalCompiles = threads * perThread,
        failures = failureCounts.sum(),
        firstFailure = firstFailure,
        perCompileTimes = allTimes.flatMap { it.toList() },
    )
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

package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M10(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag10(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper10(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 10 + (k shl 1)
    return acc
}

fun fold10(xs: List<Int>): Int {
    val scale = 10
    return xs.fold(0) { acc, v -> acc + v * scale + helper10(v and 3) }
}

fun map10(xs: List<Int>): List<String> =
    xs.map { it * 10 }.filter { it >= 0 }.map { tag10(it) }

fun work10(prev: M9): M10 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(10)
        1 -> Op.Mul(pickLarger(1, 10))
        2 -> Op.Shift((10 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold10(prev.c)
    val mapped = map10(prev.c)
    val label = describe(container) + "-10/" + mapped.size
    return M10(applied + folded, label, prev.c + folded + 10)
}


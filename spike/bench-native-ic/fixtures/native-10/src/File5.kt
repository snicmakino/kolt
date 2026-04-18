package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M5(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag5(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper5(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 5 + (k shl 1)
    return acc
}

fun fold5(xs: List<Int>): Int {
    val scale = 5
    return xs.fold(0) { acc, v -> acc + v * scale + helper5(v and 3) }
}

fun map5(xs: List<Int>): List<String> =
    xs.map { it * 5 }.filter { it >= 0 }.map { tag5(it) }

fun work5(prev: M4): M5 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(5)
        1 -> Op.Mul(pickLarger(1, 5))
        2 -> Op.Shift((5 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold5(prev.c)
    val mapped = map5(prev.c)
    val label = describe(container) + "-5/" + mapped.size
    return M5(applied + folded, label, prev.c + folded + 5)
}


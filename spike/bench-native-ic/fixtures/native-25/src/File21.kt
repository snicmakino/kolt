package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M21(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag21(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper21(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 21 + (k shl 1)
    return acc
}

fun fold21(xs: List<Int>): Int {
    val scale = 21
    return xs.fold(0) { acc, v -> acc + v * scale + helper21(v and 3) }
}

fun map21(xs: List<Int>): List<String> =
    xs.map { it * 21 }.filter { it >= 0 }.map { tag21(it) }

fun work21(prev: M20): M21 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(21)
        1 -> Op.Mul(pickLarger(1, 21))
        2 -> Op.Shift((21 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold21(prev.c)
    val mapped = map21(prev.c)
    val label = describe(container) + "-21/" + mapped.size
    return M21(applied + folded, label, prev.c + folded + 21)
}


package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M48(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag48(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper48(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 48 + (k shl 1)
    return acc
}

fun fold48(xs: List<Int>): Int {
    val scale = 48
    return xs.fold(0) { acc, v -> acc + v * scale + helper48(v and 3) }
}

fun map48(xs: List<Int>): List<String> =
    xs.map { it * 48 }.filter { it >= 0 }.map { tag48(it) }

fun work48(prev: M47): M48 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(48)
        1 -> Op.Mul(pickLarger(1, 48))
        2 -> Op.Shift((48 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold48(prev.c)
    val mapped = map48(prev.c)
    val label = describe(container) + "-48/" + mapped.size
    return M48(applied + folded, label, prev.c + folded + 48)
}


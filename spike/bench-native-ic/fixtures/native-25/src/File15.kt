package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M15(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag15(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper15(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 15 + (k shl 1)
    return acc
}

fun fold15(xs: List<Int>): Int {
    val scale = 15
    return xs.fold(0) { acc, v -> acc + v * scale + helper15(v and 3) }
}

fun map15(xs: List<Int>): List<String> =
    xs.map { it * 15 }.filter { it >= 0 }.map { tag15(it) }

fun work15(prev: M14): M15 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(15)
        1 -> Op.Mul(pickLarger(1, 15))
        2 -> Op.Shift((15 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold15(prev.c)
    val mapped = map15(prev.c)
    val label = describe(container) + "-15/" + mapped.size
    return M15(applied + folded, label, prev.c + folded + 15)
}


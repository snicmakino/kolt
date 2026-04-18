package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M8(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag8(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper8(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 8 + (k shl 1)
    return acc
}

fun fold8(xs: List<Int>): Int {
    val scale = 8
    return xs.fold(0) { acc, v -> acc + v * scale + helper8(v and 3) }
}

fun map8(xs: List<Int>): List<String> =
    xs.map { it * 8 }.filter { it >= 0 }.map { tag8(it) }

fun work8(prev: M7): M8 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(8)
        1 -> Op.Mul(pickLarger(1, 8))
        2 -> Op.Shift((8 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold8(prev.c)
    val mapped = map8(prev.c)
    val label = describe(container) + "-8/" + mapped.size
    return M8(applied + folded, label, prev.c + folded + 8)
}


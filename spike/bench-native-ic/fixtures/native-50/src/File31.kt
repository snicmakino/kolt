package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M31(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag31(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper31(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 31 + (k shl 1)
    return acc
}

fun fold31(xs: List<Int>): Int {
    val scale = 31
    return xs.fold(0) { acc, v -> acc + v * scale + helper31(v and 3) }
}

fun map31(xs: List<Int>): List<String> =
    xs.map { it * 31 }.filter { it >= 0 }.map { tag31(it) }

fun work31(prev: M30): M31 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(31)
        1 -> Op.Mul(pickLarger(1, 31))
        2 -> Op.Shift((31 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold31(prev.c)
    val mapped = map31(prev.c)
    val label = describe(container) + "-31/" + mapped.size
    return M31(applied + folded, label, prev.c + folded + 31)
}


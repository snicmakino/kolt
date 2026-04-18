package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M22(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag22(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper22(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 22 + (k shl 1)
    return acc
}

fun fold22(xs: List<Int>): Int {
    val scale = 22
    return xs.fold(0) { acc, v -> acc + v * scale + helper22(v and 3) }
}

fun map22(xs: List<Int>): List<String> =
    xs.map { it * 22 }.filter { it >= 0 }.map { tag22(it) }

fun work22(prev: M21): M22 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(22)
        1 -> Op.Mul(pickLarger(1, 22))
        2 -> Op.Shift((22 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold22(prev.c)
    val mapped = map22(prev.c)
    val label = describe(container) + "-22/" + mapped.size
    return M22(applied + folded, label, prev.c + folded + 22)
}


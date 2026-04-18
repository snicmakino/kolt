package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M14(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag14(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper14(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 14 + (k shl 1)
    return acc
}

fun fold14(xs: List<Int>): Int {
    val scale = 14
    return xs.fold(0) { acc, v -> acc + v * scale + helper14(v and 3) }
}

fun map14(xs: List<Int>): List<String> =
    xs.map { it * 14 }.filter { it >= 0 }.map { tag14(it) }

fun work14(prev: M13): M14 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(14)
        1 -> Op.Mul(pickLarger(1, 14))
        2 -> Op.Shift((14 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold14(prev.c)
    val mapped = map14(prev.c)
    val label = describe(container) + "-14/" + mapped.size
    return M14(applied + folded, label, prev.c + folded + 14)
}


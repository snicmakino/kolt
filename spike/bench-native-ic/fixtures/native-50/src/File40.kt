package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M40(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag40(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper40(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 40 + (k shl 1)
    return acc
}

fun fold40(xs: List<Int>): Int {
    val scale = 40
    return xs.fold(0) { acc, v -> acc + v * scale + helper40(v and 3) }
}

fun map40(xs: List<Int>): List<String> =
    xs.map { it * 40 }.filter { it >= 0 }.map { tag40(it) }

fun work40(prev: M39): M40 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(40)
        1 -> Op.Mul(pickLarger(1, 40))
        2 -> Op.Shift((40 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold40(prev.c)
    val mapped = map40(prev.c)
    val label = describe(container) + "-40/" + mapped.size
    return M40(applied + folded, label, prev.c + folded + 40)
}


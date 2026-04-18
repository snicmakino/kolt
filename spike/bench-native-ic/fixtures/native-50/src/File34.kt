package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M34(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag34(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper34(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 34 + (k shl 1)
    return acc
}

fun fold34(xs: List<Int>): Int {
    val scale = 34
    return xs.fold(0) { acc, v -> acc + v * scale + helper34(v and 3) }
}

fun map34(xs: List<Int>): List<String> =
    xs.map { it * 34 }.filter { it >= 0 }.map { tag34(it) }

fun work34(prev: M33): M34 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(34)
        1 -> Op.Mul(pickLarger(1, 34))
        2 -> Op.Shift((34 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold34(prev.c)
    val mapped = map34(prev.c)
    val label = describe(container) + "-34/" + mapped.size
    return M34(applied + folded, label, prev.c + folded + 34)
}


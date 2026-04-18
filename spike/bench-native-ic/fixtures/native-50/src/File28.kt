package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M28(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag28(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper28(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 28 + (k shl 1)
    return acc
}

fun fold28(xs: List<Int>): Int {
    val scale = 28
    return xs.fold(0) { acc, v -> acc + v * scale + helper28(v and 3) }
}

fun map28(xs: List<Int>): List<String> =
    xs.map { it * 28 }.filter { it >= 0 }.map { tag28(it) }

fun work28(prev: M27): M28 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(28)
        1 -> Op.Mul(pickLarger(1, 28))
        2 -> Op.Shift((28 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold28(prev.c)
    val mapped = map28(prev.c)
    val label = describe(container) + "-28/" + mapped.size
    return M28(applied + folded, label, prev.c + folded + 28)
}


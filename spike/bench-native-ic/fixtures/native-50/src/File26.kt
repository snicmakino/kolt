package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M26(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag26(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper26(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 26 + (k shl 1)
    return acc
}

fun fold26(xs: List<Int>): Int {
    val scale = 26
    return xs.fold(0) { acc, v -> acc + v * scale + helper26(v and 3) }
}

fun map26(xs: List<Int>): List<String> =
    xs.map { it * 26 }.filter { it >= 0 }.map { tag26(it) }

fun work26(prev: M25): M26 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(26)
        1 -> Op.Mul(pickLarger(1, 26))
        2 -> Op.Shift((26 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold26(prev.c)
    val mapped = map26(prev.c)
    val label = describe(container) + "-26/" + mapped.size
    return M26(applied + folded, label, prev.c + folded + 26)
}


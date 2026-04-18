package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M6(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag6(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper6(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 6 + (k shl 1)
    return acc
}

fun fold6(xs: List<Int>): Int {
    val scale = 6
    return xs.fold(0) { acc, v -> acc + v * scale + helper6(v and 3) }
}

fun map6(xs: List<Int>): List<String> =
    xs.map { it * 6 }.filter { it >= 0 }.map { tag6(it) }

fun work6(prev: M5): M6 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(6)
        1 -> Op.Mul(pickLarger(1, 6))
        2 -> Op.Shift((6 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold6(prev.c)
    val mapped = map6(prev.c)
    val label = describe(container) + "-6/" + mapped.size
    return M6(applied + folded, label, prev.c + folded + 6)
}


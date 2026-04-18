package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M47(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag47(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper47(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 47 + (k shl 1)
    return acc
}

fun fold47(xs: List<Int>): Int {
    val scale = 47
    return xs.fold(0) { acc, v -> acc + v * scale + helper47(v and 3) }
}

fun map47(xs: List<Int>): List<String> =
    xs.map { it * 47 }.filter { it >= 0 }.map { tag47(it) }

fun work47(prev: M46): M47 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(47)
        1 -> Op.Mul(pickLarger(1, 47))
        2 -> Op.Shift((47 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold47(prev.c)
    val mapped = map47(prev.c)
    val label = describe(container) + "-47/" + mapped.size
    return M47(applied + folded, label, prev.c + folded + 47)
}


package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M4(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag4(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper4(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 4 + (k shl 1)
    return acc
}

fun fold4(xs: List<Int>): Int {
    val scale = 4
    return xs.fold(0) { acc, v -> acc + v * scale + helper4(v and 3) }
}

fun map4(xs: List<Int>): List<String> =
    xs.map { it * 4 }.filter { it >= 0 }.map { tag4(it) }

fun work4(prev: M3): M4 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(4)
        1 -> Op.Mul(pickLarger(1, 4))
        2 -> Op.Shift((4 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold4(prev.c)
    val mapped = map4(prev.c)
    val label = describe(container) + "-4/" + mapped.size
    return M4(applied + folded, label, prev.c + folded + 4)
}


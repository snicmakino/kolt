package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M25(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag25(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper25(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 25 + (k shl 1)
    return acc
}

fun fold25(xs: List<Int>): Int {
    val scale = 25
    return xs.fold(0) { acc, v -> acc + v * scale + helper25(v and 3) }
}

fun map25(xs: List<Int>): List<String> =
    xs.map { it * 25 }.filter { it >= 0 }.map { tag25(it) }

fun work25(prev: M24): M25 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(25)
        1 -> Op.Mul(pickLarger(1, 25))
        2 -> Op.Shift((25 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold25(prev.c)
    val mapped = map25(prev.c)
    val label = describe(container) + "-25/" + mapped.size
    return M25(applied + folded, label, prev.c + folded + 25)
}


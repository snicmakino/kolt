package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M17(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag17(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper17(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 17 + (k shl 1)
    return acc
}

fun fold17(xs: List<Int>): Int {
    val scale = 17
    return xs.fold(0) { acc, v -> acc + v * scale + helper17(v and 3) }
}

fun map17(xs: List<Int>): List<String> =
    xs.map { it * 17 }.filter { it >= 0 }.map { tag17(it) }

fun work17(prev: M16): M17 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(17)
        1 -> Op.Mul(pickLarger(1, 17))
        2 -> Op.Shift((17 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold17(prev.c)
    val mapped = map17(prev.c)
    val label = describe(container) + "-17/" + mapped.size
    return M17(applied + folded, label, prev.c + folded + 17)
}


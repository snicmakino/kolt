package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M19(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag19(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper19(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 19 + (k shl 1)
    return acc
}

fun fold19(xs: List<Int>): Int {
    val scale = 19
    return xs.fold(0) { acc, v -> acc + v * scale + helper19(v and 3) }
}

fun map19(xs: List<Int>): List<String> =
    xs.map { it * 19 }.filter { it >= 0 }.map { tag19(it) }

fun work19(prev: M18): M19 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(19)
        1 -> Op.Mul(pickLarger(1, 19))
        2 -> Op.Shift((19 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold19(prev.c)
    val mapped = map19(prev.c)
    val label = describe(container) + "-19/" + mapped.size
    return M19(applied + folded, label, prev.c + folded + 19)
}


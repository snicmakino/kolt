package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M27(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag27(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper27(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 27 + (k shl 1)
    return acc
}

fun fold27(xs: List<Int>): Int {
    val scale = 27
    return xs.fold(0) { acc, v -> acc + v * scale + helper27(v and 3) }
}

fun map27(xs: List<Int>): List<String> =
    xs.map { it * 27 }.filter { it >= 0 }.map { tag27(it) }

fun work27(prev: M26): M27 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(27)
        1 -> Op.Mul(pickLarger(1, 27))
        2 -> Op.Shift((27 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold27(prev.c)
    val mapped = map27(prev.c)
    val label = describe(container) + "-27/" + mapped.size
    return M27(applied + folded, label, prev.c + folded + 27)
}


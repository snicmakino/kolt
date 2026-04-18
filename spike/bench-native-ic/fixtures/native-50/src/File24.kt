package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M24(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag24(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper24(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 24 + (k shl 1)
    return acc
}

fun fold24(xs: List<Int>): Int {
    val scale = 24
    return xs.fold(0) { acc, v -> acc + v * scale + helper24(v and 3) }
}

fun map24(xs: List<Int>): List<String> =
    xs.map { it * 24 }.filter { it >= 0 }.map { tag24(it) }

fun work24(prev: M23): M24 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(24)
        1 -> Op.Mul(pickLarger(1, 24))
        2 -> Op.Shift((24 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold24(prev.c)
    val mapped = map24(prev.c)
    val label = describe(container) + "-24/" + mapped.size
    return M24(applied + folded, label, prev.c + folded + 24)
}


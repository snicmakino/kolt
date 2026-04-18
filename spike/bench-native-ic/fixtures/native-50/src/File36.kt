package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M36(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag36(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper36(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 36 + (k shl 1)
    return acc
}

fun fold36(xs: List<Int>): Int {
    val scale = 36
    return xs.fold(0) { acc, v -> acc + v * scale + helper36(v and 3) }
}

fun map36(xs: List<Int>): List<String> =
    xs.map { it * 36 }.filter { it >= 0 }.map { tag36(it) }

fun work36(prev: M35): M36 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(36)
        1 -> Op.Mul(pickLarger(1, 36))
        2 -> Op.Shift((36 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold36(prev.c)
    val mapped = map36(prev.c)
    val label = describe(container) + "-36/" + mapped.size
    return M36(applied + folded, label, prev.c + folded + 36)
}


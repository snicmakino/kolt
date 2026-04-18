package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M9(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag9(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper9(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 9 + (k shl 1)
    return acc
}

fun fold9(xs: List<Int>): Int {
    val scale = 9
    return xs.fold(0) { acc, v -> acc + v * scale + helper9(v and 3) }
}

fun map9(xs: List<Int>): List<String> =
    xs.map { it * 9 }.filter { it >= 0 }.map { tag9(it) }

fun work9(prev: M8): M9 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(9)
        1 -> Op.Mul(pickLarger(1, 9))
        2 -> Op.Shift((9 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold9(prev.c)
    val mapped = map9(prev.c)
    val label = describe(container) + "-9/" + mapped.size
    return M9(applied + folded, label, prev.c + folded + 9)
}


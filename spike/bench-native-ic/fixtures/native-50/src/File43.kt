package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M43(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag43(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper43(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 43 + (k shl 1)
    return acc
}

fun fold43(xs: List<Int>): Int {
    val scale = 43
    return xs.fold(0) { acc, v -> acc + v * scale + helper43(v and 3) }
}

fun map43(xs: List<Int>): List<String> =
    xs.map { it * 43 }.filter { it >= 0 }.map { tag43(it) }

fun work43(prev: M42): M43 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(43)
        1 -> Op.Mul(pickLarger(1, 43))
        2 -> Op.Shift((43 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold43(prev.c)
    val mapped = map43(prev.c)
    val label = describe(container) + "-43/" + mapped.size
    return M43(applied + folded, label, prev.c + folded + 43)
}


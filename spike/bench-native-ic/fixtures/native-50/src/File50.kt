package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M50(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag50(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper50(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 50 + (k shl 1)
    return acc
}

fun fold50(xs: List<Int>): Int {
    val scale = 50
    return xs.fold(0) { acc, v -> acc + v * scale + helper50(v and 3) }
}

fun map50(xs: List<Int>): List<String> =
    xs.map { it * 50 }.filter { it >= 0 }.map { tag50(it) }

fun work50(prev: M49): M50 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(50)
        1 -> Op.Mul(pickLarger(1, 50))
        2 -> Op.Shift((50 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold50(prev.c)
    val mapped = map50(prev.c)
    val label = describe(container) + "-50/" + mapped.size
    return M50(applied + folded, label, prev.c + folded + 50)
}


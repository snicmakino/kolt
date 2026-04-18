package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M49(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag49(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper49(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 49 + (k shl 1)
    return acc
}

fun fold49(xs: List<Int>): Int {
    val scale = 49
    return xs.fold(0) { acc, v -> acc + v * scale + helper49(v and 3) }
}

fun map49(xs: List<Int>): List<String> =
    xs.map { it * 49 }.filter { it >= 0 }.map { tag49(it) }

fun work49(prev: M48): M49 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(49)
        1 -> Op.Mul(pickLarger(1, 49))
        2 -> Op.Shift((49 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold49(prev.c)
    val mapped = map49(prev.c)
    val label = describe(container) + "-49/" + mapped.size
    return M49(applied + folded, label, prev.c + folded + 49)
}


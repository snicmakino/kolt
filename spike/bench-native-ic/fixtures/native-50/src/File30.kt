package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M30(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag30(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper30(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 30 + (k shl 1)
    return acc
}

fun fold30(xs: List<Int>): Int {
    val scale = 30
    return xs.fold(0) { acc, v -> acc + v * scale + helper30(v and 3) }
}

fun map30(xs: List<Int>): List<String> =
    xs.map { it * 30 }.filter { it >= 0 }.map { tag30(it) }

fun work30(prev: M29): M30 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(30)
        1 -> Op.Mul(pickLarger(1, 30))
        2 -> Op.Shift((30 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold30(prev.c)
    val mapped = map30(prev.c)
    val label = describe(container) + "-30/" + mapped.size
    return M30(applied + folded, label, prev.c + folded + 30)
}


package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M18(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag18(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper18(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 18 + (k shl 1)
    return acc
}

fun fold18(xs: List<Int>): Int {
    val scale = 18
    return xs.fold(0) { acc, v -> acc + v * scale + helper18(v and 3) }
}

fun map18(xs: List<Int>): List<String> =
    xs.map { it * 18 }.filter { it >= 0 }.map { tag18(it) }

fun work18(prev: M17): M18 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(18)
        1 -> Op.Mul(pickLarger(1, 18))
        2 -> Op.Shift((18 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold18(prev.c)
    val mapped = map18(prev.c)
    val label = describe(container) + "-18/" + mapped.size
    return M18(applied + folded, label, prev.c + folded + 18)
}


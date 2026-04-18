package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M38(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag38(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper38(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 38 + (k shl 1)
    return acc
}

fun fold38(xs: List<Int>): Int {
    val scale = 38
    return xs.fold(0) { acc, v -> acc + v * scale + helper38(v and 3) }
}

fun map38(xs: List<Int>): List<String> =
    xs.map { it * 38 }.filter { it >= 0 }.map { tag38(it) }

fun work38(prev: M37): M38 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(38)
        1 -> Op.Mul(pickLarger(1, 38))
        2 -> Op.Shift((38 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold38(prev.c)
    val mapped = map38(prev.c)
    val label = describe(container) + "-38/" + mapped.size
    return M38(applied + folded, label, prev.c + folded + 38)
}


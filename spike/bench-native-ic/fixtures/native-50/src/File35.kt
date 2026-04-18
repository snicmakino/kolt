package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M35(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag35(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper35(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 35 + (k shl 1)
    return acc
}

fun fold35(xs: List<Int>): Int {
    val scale = 35
    return xs.fold(0) { acc, v -> acc + v * scale + helper35(v and 3) }
}

fun map35(xs: List<Int>): List<String> =
    xs.map { it * 35 }.filter { it >= 0 }.map { tag35(it) }

fun work35(prev: M34): M35 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(35)
        1 -> Op.Mul(pickLarger(1, 35))
        2 -> Op.Shift((35 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold35(prev.c)
    val mapped = map35(prev.c)
    val label = describe(container) + "-35/" + mapped.size
    return M35(applied + folded, label, prev.c + folded + 35)
}


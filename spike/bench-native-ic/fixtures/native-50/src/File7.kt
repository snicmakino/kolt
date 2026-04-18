package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M7(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag7(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper7(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 7 + (k shl 1)
    return acc
}

fun fold7(xs: List<Int>): Int {
    val scale = 7
    return xs.fold(0) { acc, v -> acc + v * scale + helper7(v and 3) }
}

fun map7(xs: List<Int>): List<String> =
    xs.map { it * 7 }.filter { it >= 0 }.map { tag7(it) }

fun work7(prev: M6): M7 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(7)
        1 -> Op.Mul(pickLarger(1, 7))
        2 -> Op.Shift((7 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold7(prev.c)
    val mapped = map7(prev.c)
    val label = describe(container) + "-7/" + mapped.size
    return M7(applied + folded, label, prev.c + folded + 7)
}


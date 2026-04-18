package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M2(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag2(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper2(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 2 + (k shl 1)
    return acc
}

fun fold2(xs: List<Int>): Int {
    val scale = 2
    return xs.fold(0) { acc, v -> acc + v * scale + helper2(v and 3) }
}

fun map2(xs: List<Int>): List<String> =
    xs.map { it * 2 }.filter { it >= 0 }.map { tag2(it) }

fun work2(prev: M1): M2 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(2)
        1 -> Op.Mul(pickLarger(1, 2))
        2 -> Op.Shift((2 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold2(prev.c)
    val mapped = map2(prev.c)
    val label = describe(container) + "-2/" + mapped.size
    return M2(applied + folded, label, prev.c + folded + 2)
}


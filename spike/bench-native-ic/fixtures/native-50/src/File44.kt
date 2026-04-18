package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M44(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag44(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper44(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 44 + (k shl 1)
    return acc
}

fun fold44(xs: List<Int>): Int {
    val scale = 44
    return xs.fold(0) { acc, v -> acc + v * scale + helper44(v and 3) }
}

fun map44(xs: List<Int>): List<String> =
    xs.map { it * 44 }.filter { it >= 0 }.map { tag44(it) }

fun work44(prev: M43): M44 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(44)
        1 -> Op.Mul(pickLarger(1, 44))
        2 -> Op.Shift((44 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold44(prev.c)
    val mapped = map44(prev.c)
    val label = describe(container) + "-44/" + mapped.size
    return M44(applied + folded, label, prev.c + folded + 44)
}


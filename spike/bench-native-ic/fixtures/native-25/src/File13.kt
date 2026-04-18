package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M13(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag13(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper13(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 13 + (k shl 1)
    return acc
}

fun fold13(xs: List<Int>): Int {
    val scale = 13
    return xs.fold(0) { acc, v -> acc + v * scale + helper13(v and 3) }
}

fun map13(xs: List<Int>): List<String> =
    xs.map { it * 13 }.filter { it >= 0 }.map { tag13(it) }

fun work13(prev: M12): M13 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(13)
        1 -> Op.Mul(pickLarger(1, 13))
        2 -> Op.Shift((13 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold13(prev.c)
    val mapped = map13(prev.c)
    val label = describe(container) + "-13/" + mapped.size
    return M13(applied + folded, label, prev.c + folded + 13)
}


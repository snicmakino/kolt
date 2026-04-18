package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M3(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag3(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper3(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 3 + (k shl 1)
    return acc
}

fun fold3(xs: List<Int>): Int {
    val scale = 3
    return xs.fold(0) { acc, v -> acc + v * scale + helper3(v and 3) }
}

fun map3(xs: List<Int>): List<String> =
    xs.map { it * 3 }.filter { it >= 0 }.map { tag3(it) }

fun work3(prev: M2): M3 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(3)
        1 -> Op.Mul(pickLarger(1, 3))
        2 -> Op.Shift((3 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold3(prev.c)
    val mapped = map3(prev.c)
    val label = describe(container) + "-3/" + mapped.size
    return M3(applied + folded, label, prev.c + folded + 3)
}


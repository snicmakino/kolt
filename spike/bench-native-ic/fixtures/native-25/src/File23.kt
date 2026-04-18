package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M23(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag23(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper23(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 23 + (k shl 1)
    return acc
}

fun fold23(xs: List<Int>): Int {
    val scale = 23
    return xs.fold(0) { acc, v -> acc + v * scale + helper23(v and 3) }
}

fun map23(xs: List<Int>): List<String> =
    xs.map { it * 23 }.filter { it >= 0 }.map { tag23(it) }

fun work23(prev: M22): M23 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(23)
        1 -> Op.Mul(pickLarger(1, 23))
        2 -> Op.Shift((23 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold23(prev.c)
    val mapped = map23(prev.c)
    val label = describe(container) + "-23/" + mapped.size
    return M23(applied + folded, label, prev.c + folded + 23)
}


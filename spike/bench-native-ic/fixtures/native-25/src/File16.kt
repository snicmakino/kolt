package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M16(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag16(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper16(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 16 + (k shl 1)
    return acc
}

fun fold16(xs: List<Int>): Int {
    val scale = 16
    return xs.fold(0) { acc, v -> acc + v * scale + helper16(v and 3) }
}

fun map16(xs: List<Int>): List<String> =
    xs.map { it * 16 }.filter { it >= 0 }.map { tag16(it) }

fun work16(prev: M15): M16 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(16)
        1 -> Op.Mul(pickLarger(1, 16))
        2 -> Op.Shift((16 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold16(prev.c)
    val mapped = map16(prev.c)
    val label = describe(container) + "-16/" + mapped.size
    return M16(applied + folded, label, prev.c + folded + 16)
}


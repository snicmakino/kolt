package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M42(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag42(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper42(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 42 + (k shl 1)
    return acc
}

fun fold42(xs: List<Int>): Int {
    val scale = 42
    return xs.fold(0) { acc, v -> acc + v * scale + helper42(v and 3) }
}

fun map42(xs: List<Int>): List<String> =
    xs.map { it * 42 }.filter { it >= 0 }.map { tag42(it) }

fun work42(prev: M41): M42 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(42)
        1 -> Op.Mul(pickLarger(1, 42))
        2 -> Op.Shift((42 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold42(prev.c)
    val mapped = map42(prev.c)
    val label = describe(container) + "-42/" + mapped.size
    return M42(applied + folded, label, prev.c + folded + 42)
}


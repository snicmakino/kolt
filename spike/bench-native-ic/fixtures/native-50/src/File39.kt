package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M39(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag39(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper39(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 39 + (k shl 1)
    return acc
}

fun fold39(xs: List<Int>): Int {
    val scale = 39
    return xs.fold(0) { acc, v -> acc + v * scale + helper39(v and 3) }
}

fun map39(xs: List<Int>): List<String> =
    xs.map { it * 39 }.filter { it >= 0 }.map { tag39(it) }

fun work39(prev: M38): M39 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(39)
        1 -> Op.Mul(pickLarger(1, 39))
        2 -> Op.Shift((39 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold39(prev.c)
    val mapped = map39(prev.c)
    val label = describe(container) + "-39/" + mapped.size
    return M39(applied + folded, label, prev.c + folded + 39)
}


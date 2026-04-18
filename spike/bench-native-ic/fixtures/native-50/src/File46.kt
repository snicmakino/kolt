package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M46(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag46(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper46(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 46 + (k shl 1)
    return acc
}

fun fold46(xs: List<Int>): Int {
    val scale = 46
    return xs.fold(0) { acc, v -> acc + v * scale + helper46(v and 3) }
}

fun map46(xs: List<Int>): List<String> =
    xs.map { it * 46 }.filter { it >= 0 }.map { tag46(it) }

fun work46(prev: M45): M46 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(46)
        1 -> Op.Mul(pickLarger(1, 46))
        2 -> Op.Shift((46 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold46(prev.c)
    val mapped = map46(prev.c)
    val label = describe(container) + "-46/" + mapped.size
    return M46(applied + folded, label, prev.c + folded + 46)
}


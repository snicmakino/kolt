package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M29(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag29(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper29(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 29 + (k shl 1)
    return acc
}

fun fold29(xs: List<Int>): Int {
    val scale = 29
    return xs.fold(0) { acc, v -> acc + v * scale + helper29(v and 3) }
}

fun map29(xs: List<Int>): List<String> =
    xs.map { it * 29 }.filter { it >= 0 }.map { tag29(it) }

fun work29(prev: M28): M29 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(29)
        1 -> Op.Mul(pickLarger(1, 29))
        2 -> Op.Shift((29 and 7))
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold29(prev.c)
    val mapped = map29(prev.c)
    val label = describe(container) + "-29/" + mapped.size
    return M29(applied + folded, label, prev.c + folded + 29)
}


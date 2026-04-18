package bench

import bench.util.Container
import bench.util.Op
import bench.util.applyOp
import bench.util.boxed
import bench.util.describe
import bench.util.pickLarger

data class M1(val a: Int, val b: String, val c: List<Int>)

inline fun <reified T> tag1(value: T): String =
    "${T::class.simpleName}:${value}"

private fun helper1(n: Int): Int {
    var acc = 0
    for (k in 0..n) acc += k * 1 + (k shl 1)
    return acc
}

fun fold1(xs: List<Int>): Int {
    val scale = 1
    return xs.fold(0) { acc, v -> acc + v * scale + helper1(v and 3) }
}

fun map1(xs: List<Int>): List<String> =
    xs.map { it * 1 }.filter { it >= 0 }.map { tag1(it) }

fun seed(): M1 = M1(1, "seed", listOf(1, 2, 3, 4, 5))

fun process1(prev: M1): M1 {
    val container: Container<String> = boxed(prev.b)
    val op: Op = when (prev.a and 3) {
        0 -> Op.Add(prev.a + 1)
        1 -> Op.Mul(pickLarger(1, prev.a))
        2 -> Op.Shift(prev.a and 7)
        else -> Op.Noop
    }
    val applied = applyOp(container.value, op)
    val folded = fold1(prev.c)
    val mapped = map1(prev.c)
    val label = describe(container)
    return M1(applied + folded, label + "|" + mapped.size, prev.c + folded)
}

fun main() {
    val m: M1 = process1(seed())
    val m2 = work2(m)
    val m3 = work3(m2)
    val m4 = work4(m3)
    val m5 = work5(m4)
    val m6 = work6(m5)
    val m7 = work7(m6)
    val m8 = work8(m7)
    val m9 = work9(m8)
    val m10 = work10(m9)
    val m11 = work11(m10)
    val m12 = work12(m11)
    val m13 = work13(m12)
    val m14 = work14(m13)
    val m15 = work15(m14)
    val m16 = work16(m15)
    val m17 = work17(m16)
    val m18 = work18(m17)
    val m19 = work19(m18)
    val m20 = work20(m19)
    val m21 = work21(m20)
    val m22 = work22(m21)
    val m23 = work23(m22)
    val m24 = work24(m23)
    val m25 = work25(m24)
    val finalFold = fold25(m25.c)
    val finalMap  = map25(m25.c).size
    println("bench ${m25.a} ${m25.b.length} $finalFold $finalMap")
}

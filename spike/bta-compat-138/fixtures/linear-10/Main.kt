package fixture

class M1(val v: Int)

fun main() {
    val result = work10(work9(work8(work7(work6(work5(work4(work3(work2(M1(1)))))))))).v
    println(result)
}

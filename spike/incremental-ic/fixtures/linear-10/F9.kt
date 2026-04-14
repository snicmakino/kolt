package fixture

class M9(val v: Int)

fun work9(prev: M8): M9 = M9(prev.v + 9)

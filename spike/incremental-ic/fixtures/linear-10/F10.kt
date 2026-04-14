package fixture

class M10(val v: Int)

fun work10(prev: M9): M10 = M10(prev.v + 10)

package fixture

class M8(val v: Int)

fun work8(prev: M7): M8 = M8(prev.v + 8)

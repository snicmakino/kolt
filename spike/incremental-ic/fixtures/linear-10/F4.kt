package fixture

class M4(val v: Int)

fun work4(prev: M3): M4 = M4(prev.v + 4)

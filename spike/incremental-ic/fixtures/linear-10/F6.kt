package fixture

class M6(val v: Int)

fun work6(prev: M5): M6 = M6(prev.v + 6)

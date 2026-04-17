package fixture

class M3(val v: Int)

fun work3(prev: M2): M3 = M3(prev.v + 3)

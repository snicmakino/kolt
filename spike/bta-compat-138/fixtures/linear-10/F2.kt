package fixture

class M2(val v: Int)

fun work2(prev: M1): M2 = M2(prev.v + 2)

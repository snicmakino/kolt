package fixture

class M5(val v: Int)

fun work5(prev: M4): M5 = M5(prev.v + 5)

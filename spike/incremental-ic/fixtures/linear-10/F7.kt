package fixture

class M7(val v: Int)

fun work7(prev: M6): M7 = M7(prev.v + 7)

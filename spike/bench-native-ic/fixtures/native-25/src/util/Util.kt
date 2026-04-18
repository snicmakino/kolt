package bench.util

data class Container<T>(val value: Int, val label: T)

sealed class Op {
    data class Add(val amount: Int) : Op()
    data class Mul(val factor: Int) : Op()
    data class Shift(val bits: Int) : Op()
    data object Noop : Op()
}

inline fun <reified T> describe(container: Container<T>): String =
    "${T::class.simpleName}:${container.value}/${container.label}"

inline fun <reified T> boxed(value: T): Container<T> =
    Container(value.hashCode(), value)

fun applyOp(value: Int, op: Op): Int = when (op) {
    is Op.Add -> value + op.amount
    is Op.Mul -> value * op.factor
    is Op.Shift -> value shl (op.bits and 31)
    Op.Noop -> value
}

fun <T : Comparable<T>> pickLarger(a: T, b: T): T = if (a >= b) a else b

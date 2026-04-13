package bench

sealed class CompileResult {
    object Ok : CompileResult()
    data class Failed(val exitCode: String, val messages: List<String>) : CompileResult()
}

package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {

    // --- jvmMainClass ---

    @Test
    fun jvmMainClassForRootPackage() {
        assertEquals("MainKt", jvmMainClass("main"))
    }

    @Test
    fun jvmMainClassForSingleSegmentPackage() {
        assertEquals("com.MainKt", jvmMainClass("com.main"))
    }

    @Test
    fun jvmMainClassForDeepPackage() {
        assertEquals("com.example.app.MainKt", jvmMainClass("com.example.app.main"))
    }

    // --- validateMainFqn: accepts ---

    @Test
    fun validateMainFqnAcceptsRootMain() {
        assertNull(validateMainFqn("main").getError())
    }

    @Test
    fun validateMainFqnAcceptsPackagedMain() {
        assertNull(validateMainFqn("com.example.main").getError())
    }

    @Test
    fun validateMainFqnAcceptsDeeplyPackagedMain() {
        assertNull(validateMainFqn("foo.bar.baz.main").getError())
    }

    // --- validateMainFqn: rejects JVM class style with the migration hint ---

    @Test
    fun validateMainFqnRejectsRootMainKtWithHint() {
        val err = assertNotNull(validateMainFqn("MainKt").getError())
        val msg = err.message
        assertTrue(msg.contains("\"MainKt\""), msg)
        assertTrue(msg.contains("JVM class name"), msg)
        assertTrue(msg.contains("main = \"main\""), msg)
    }

    @Test
    fun validateMainFqnRejectsPackagedMainKtWithHint() {
        val err = assertNotNull(validateMainFqn("com.example.MainKt").getError())
        val msg = err.message
        assertTrue(msg.contains("\"com.example.MainKt\""), msg)
        assertTrue(msg.contains("main = \"com.example.main\""), msg)
    }

    @Test
    fun validateMainFqnRejectsArbitraryKtFacade() {
        // Non-"MainKt" Kt-suffixed class names (e.g. from an App.kt file) are
        // also rejected — they're still JVM class names, not Kotlin FQNs.
        val err = assertNotNull(validateMainFqn("com.example.AppKt").getError())
        assertTrue(err.message.contains("JVM class name"))
    }

    // --- validateMainFqn: rejects other malformed values ---

    @Test
    fun validateMainFqnRejectsEmpty() {
        assertNotNull(validateMainFqn("").getError())
    }

    @Test
    fun validateMainFqnRejectsCapitalizedNonKt() {
        // "Main" looks like a JVM class but doesn't end with Kt. Still invalid
        // because it's neither "main" nor "<pkg>.main".
        val err = assertNotNull(validateMainFqn("Main").getError())
        assertTrue(err.message.contains("Kotlin function FQN"))
    }

    @Test
    fun validateMainFqnRejectsNonMainSuffix() {
        // Ends with a different function name.
        assertNotNull(validateMainFqn("com.example.run").getError())
    }

    @Test
    fun validateMainFqnRejectsEndingInRemain() {
        // "remain" ends in "main" as a substring but not as a dotted segment.
        assertNotNull(validateMainFqn("remain").getError())
    }
}

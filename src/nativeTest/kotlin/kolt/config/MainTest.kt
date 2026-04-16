package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {

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
        val err = assertNotNull(validateMainFqn("com.example.AppKt").getError())
        assertTrue(err.message.contains("JVM class name"))
    }

    @Test
    fun validateMainFqnRejectsEmpty() {
        assertNotNull(validateMainFqn("").getError())
    }

    @Test
    fun validateMainFqnRejectsCapitalizedNonKt() {
        val err = assertNotNull(validateMainFqn("Main").getError())
        assertTrue(err.message.contains("Kotlin function FQN"))
    }

    @Test
    fun validateMainFqnRejectsNonMainSuffix() {
        assertNotNull(validateMainFqn("com.example.run").getError())
    }

    @Test
    fun validateMainFqnRejectsEndingInRemain() {
        assertNotNull(validateMainFqn("remain").getError())
    }
}

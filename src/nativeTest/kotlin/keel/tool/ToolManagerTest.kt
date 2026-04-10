package keel.tool

import keel.config.MAVEN_CENTRAL_BASE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolManagerTest {

    @Test
    fun toolSpecBuildDownloadUrl() {
        val spec = ToolSpec(
            group = "com.facebook",
            artifact = "ktfmt",
            version = "0.54",
            fileName = "ktfmt-0.54-jar-with-dependencies.jar"
        )
        val url = spec.downloadUrl()
        assertEquals("$MAVEN_CENTRAL_BASE/com/facebook/ktfmt/0.54/ktfmt-0.54-jar-with-dependencies.jar", url)
    }

    @Test
    fun toolSpecBuildDownloadUrlForJunitConsole() {
        val spec = ToolSpec(
            group = "org.junit.platform",
            artifact = "junit-platform-console-standalone",
            version = "1.11.4",
            fileName = "junit-platform-console-standalone-1.11.4.jar"
        )
        val url = spec.downloadUrl()
        assertEquals(
            "$MAVEN_CENTRAL_BASE/org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar",
            url
        )
    }

    @Test
    fun toolSpecToolPath() {
        val spec = ToolSpec(
            group = "com.facebook",
            artifact = "ktfmt",
            version = "0.54",
            fileName = "ktfmt-0.54-jar-with-dependencies.jar"
        )
        val path = spec.toolPath("/home/user/.keel/tools")
        assertEquals("/home/user/.keel/tools/ktfmt-0.54-jar-with-dependencies.jar", path)
    }

    @Test
    fun ktfmtSpecHasCorrectValues() {
        assertEquals("com.facebook", KTFMT_SPEC.group)
        assertEquals("ktfmt", KTFMT_SPEC.artifact)
        assertTrue(KTFMT_SPEC.fileName.contains("jar-with-dependencies"))
    }

    @Test
    fun consoleLauncherSpecHasCorrectValues() {
        assertEquals("org.junit.platform", CONSOLE_LAUNCHER_SPEC.group)
        assertEquals("junit-platform-console-standalone", CONSOLE_LAUNCHER_SPEC.artifact)
    }
}

package kolt.cli

import kolt.build.daemon.KOTLIN_VERSION_FLOOR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoCommandTest {
    private val withProject = InfoSnapshot(
        koltVersion = "0.12.0",
        koltPath = "/usr/local/bin/kolt",
        koltHomeDisplay = "~/.kolt",
        koltHomeBytes = 142L * 1024 * 1024,
        kotlin = KotlinInfo("2.3.20", "daemon", "~/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc"),
        jdk = JdkInfo("21", "~/.kolt/toolchains/jdk/21/bin/java"),
        host = "linux-x86_64",
        project = ProjectInfo("my-app", "0.1.0", "app", "jvm")
    )

    @Test
    fun formatsAllFieldsWhenInsideProject() {
        val lines = formatInfo(withProject).lines()

        assertEquals("kolt        v0.12.0 (/usr/local/bin/kolt)", lines[0])
        assertEquals("kolt home   ~/.kolt (142.0 MB)", lines[1])
        assertEquals("kotlin      2.3.20 (daemon, ~/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc)", lines[2])
        assertEquals("jdk         21 (~/.kolt/toolchains/jdk/21/bin/java)", lines[3])
        assertEquals("host        linux-x86_64", lines[4])
        assertEquals("", lines[5])
        assertEquals("project     my-app v0.1.0", lines[6])
        assertEquals("kind        app", lines[7])
        assertEquals("target      jvm", lines[8])
    }

    @Test
    fun showsOutsideProjectHintInsteadOfProjectSection() {
        val snap = withProject.copy(kotlin = null, jdk = null, project = null)
        val lines = formatInfo(snap).lines()

        assertTrue(lines[0].startsWith("kolt        v0.12.0"))
        assertTrue(lines.any { it.startsWith("host") })
        assertFalse(lines.any { it.startsWith("kotlin") })
        assertFalse(lines.any { it.startsWith("project") })
        assertTrue(
            lines.any { it.contains("not in a kolt project") },
            "must tell the user why the project section is absent"
        )
    }

    @Test
    fun hidesHomeSizeWhenKoltHomeMissing() {
        val snap = withProject.copy(koltHomeBytes = null)
        val line = formatInfo(snap).lines()[1]
        assertEquals("kolt home   ~/.kolt", line)
    }

    @Test
    fun kotlinLineShowsFloorHintWhenSubprocess() {
        // Anchor on the live KOTLIN_VERSION_FLOOR so this test follows the
        // floor when the daemon family bumps, instead of going stale silently.
        val snap = withProject.copy(
            kotlin = KotlinInfo("2.2.0", "subprocess [<$KOTLIN_VERSION_FLOOR]", "~/.kolt/toolchains/kotlinc/2.2.0/bin/kotlinc")
        )
        val kotlinLine = formatInfo(snap).lines().first { it.startsWith("kotlin") }
        assertTrue(
            kotlinLine.contains("<$KOTLIN_VERSION_FLOOR"),
            "subprocess mode must disclose the daemon floor: $kotlinLine"
        )
    }

    @Test
    fun abbreviateHomePathReplacesHomeWithTilde() {
        assertEquals("~/.kolt", abbreviateHomePath("/home/alice/.kolt", "/home/alice"))
        assertEquals("/etc/kolt", abbreviateHomePath("/etc/kolt", "/home/alice"))
        assertEquals("~", abbreviateHomePath("/home/alice", "/home/alice"))
    }

    @Test
    fun abbreviateHomePathLeavesPathAloneWhenHomeIsEmpty() {
        // Guards against a bug where empty home would turn prefix into "/"
        // and mangle every absolute path into "~/..." form.
        assertEquals("/usr/local/bin/kolt", abbreviateHomePath("/usr/local/bin/kolt", ""))
        assertEquals("/home/alice/.kolt", abbreviateHomePath("/home/alice/.kolt", ""))
    }
}

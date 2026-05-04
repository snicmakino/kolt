package kolt.daemon.ic

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IcStateLayoutTest {

  @Test
  fun `projectIdFor returns 32 char lowercase hex`() {
    val id = IcStateLayout.projectIdFor(Path.of("/home/alice/projects/kolt"))

    assertEquals(32, id.length)
    assertTrue(id.all { it.isDigit() || it in 'a'..'f' }, "id must be lowercase hex, got: $id")
  }

  @Test
  fun `projectIdFor is deterministic for the same input`() {
    val a = IcStateLayout.projectIdFor(Path.of("/w/x/y"))
    val b = IcStateLayout.projectIdFor(Path.of("/w/x/y"))

    assertEquals(a, b)
  }

  @Test
  fun `projectIdFor distinguishes different project roots`() {
    val a = IcStateLayout.projectIdFor(Path.of("/w/x/y"))
    val b = IcStateLayout.projectIdFor(Path.of("/w/x/z"))

    assertNotEquals(a, b)
  }

  @Test
  fun `projectIdFor uses the absolute path string verbatim`() {
    // Carryover #6: IcStateLayout is the one place that defines the
    // hash input shape. If a future refactor changes the algorithm
    // (e.g. canonicalising symlinks), this test will pin the break.
    val explicit = IcStateLayout.projectIdFor(Path.of("/abs/path"))

    val md = java.security.MessageDigest.getInstance("SHA-256")
    val expected =
      md.digest("/abs/path".toByteArray(Charsets.UTF_8)).take(16).joinToString("") {
        "%02x".format(it)
      }

    assertEquals(expected, explicit)
  }

  @Test
  fun `workingDirFor composes icRoot kotlinVersion projectId and scope`() {
    val icRoot = Path.of("/home/alice/.kolt/daemon/ic")
    val projectRoot = Path.of("/work/proj")
    val projectId = IcStateLayout.projectIdFor(projectRoot)

    val workingDir = IcStateLayout.workingDirFor(icRoot, "2.3.20", projectRoot, CompileScope.Main)

    assertEquals(icRoot.resolve("2.3.20").resolve(projectId).resolve("main"), workingDir)
  }

  @Test
  fun `workingDirFor separates by kotlin version`() {
    val icRoot = Path.of("/ic")
    val projectRoot = Path.of("/w")

    val v1 = IcStateLayout.workingDirFor(icRoot, "2.3.20", projectRoot, CompileScope.Main)
    val v2 = IcStateLayout.workingDirFor(icRoot, "2.4.0", projectRoot, CompileScope.Main)

    assertNotEquals(v1, v2)
    assertEquals(icRoot.resolve("2.3.20"), v1.parent.parent)
    assertEquals(icRoot.resolve("2.4.0"), v2.parent.parent)
  }

  @Test
  fun `workingDirFor separates by project root`() {
    val icRoot = Path.of("/ic")

    val a = IcStateLayout.workingDirFor(icRoot, "2.3.20", Path.of("/w/a"), CompileScope.Main)
    val b = IcStateLayout.workingDirFor(icRoot, "2.3.20", Path.of("/w/b"), CompileScope.Main)

    assertNotEquals(a, b)
    assertEquals(a.parent.parent, b.parent.parent, "same kotlinVersion directory hosts both")
  }

  // Issue #376: main and test compile in the same project must NOT share
  // BTA's workingDirectory. BTA's inputsCache lives under
  // `workingDir/inputs/` (per `IncrementalCompilerRunner`), so two compiles
  // sharing a workingDir cross-contaminate: the second compile sees the
  // first compile's sources as "removed" and removes their .class outputs
  // via `removeOutputForSourceFiles`. The fix is a per-scope segment so
  // each (project, scope) gets its own inputsCache.
  @Test
  fun `workingDirFor separates main and test under the same project`() {
    val icRoot = Path.of("/ic")
    val projectRoot = Path.of("/w/proj")

    val main = IcStateLayout.workingDirFor(icRoot, "2.3.20", projectRoot, CompileScope.Main)
    val test = IcStateLayout.workingDirFor(icRoot, "2.3.20", projectRoot, CompileScope.Test)

    assertNotEquals(main, test)
    assertEquals(main.parent, test.parent, "same projectId directory hosts both scopes")
  }
}

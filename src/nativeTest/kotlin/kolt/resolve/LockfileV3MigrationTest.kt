package kolt.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LockfileV3MigrationTest {

  private val v3Json =
    """
    {
      "version": 3,
      "kotlin": "2.3.20",
      "jvm_target": "25",
      "dependencies": {}
    }
    """
      .trimIndent()

  private val v4Json =
    """
    {
      "version": 4,
      "kotlin": "2.3.20",
      "jvm_target": "25",
      "dependencies": {}
    }
    """
      .trimIndent()

  @Test
  fun v3WithMigrationAllowedReturnsAccepted() {
    val outcome = classifyLockfileLoad(v3Json, allowMigration = true)
    val accepted = assertIs<LockfileLoadResult.UnsupportedAndMigrationAllowed>(outcome)
    assertEquals(3, accepted.version)
  }

  @Test
  fun v3WithMigrationDeniedReturnsDenied() {
    val outcome = classifyLockfileLoad(v3Json, allowMigration = false)
    val denied = assertIs<LockfileLoadResult.UnsupportedAndMigrationDenied>(outcome)
    assertEquals(3, denied.version)
  }

  @Test
  fun v4ReturnsLoadedRegardlessOfMigrationFlag() {
    val outcomeMigrate = classifyLockfileLoad(v4Json, allowMigration = true)
    val loadedMigrate = assertIs<LockfileLoadResult.Loaded>(outcomeMigrate)
    assertEquals(4, loadedMigrate.lockfile.version)

    val outcomeDeny = classifyLockfileLoad(v4Json, allowMigration = false)
    val loadedDeny = assertIs<LockfileLoadResult.Loaded>(outcomeDeny)
    assertEquals(4, loadedDeny.lockfile.version)
  }

  @Test
  fun nullJsonReturnsAbsent() {
    val outcome = classifyLockfileLoad(jsonString = null, allowMigration = false)
    assertIs<LockfileLoadResult.Absent>(outcome)
  }

  @Test
  fun corruptJsonReturnsCorrupt() {
    val outcome = classifyLockfileLoad("{ not valid json", allowMigration = false)
    val corrupt = assertIs<LockfileLoadResult.Corrupt>(outcome)
    assertEquals(true, corrupt.message.isNotEmpty())
  }
}

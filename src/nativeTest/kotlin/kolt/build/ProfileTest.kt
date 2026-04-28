package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileTest {

  @Test
  fun debugDirNameIsDebug() {
    assertEquals("debug", Profile.Debug.dirName)
  }

  @Test
  fun releaseDirNameIsRelease() {
    assertEquals("release", Profile.Release.dirName)
  }

  @Test
  fun profileHasExactlyTwoEntries() {
    assertEquals(2, Profile.entries.size)
  }
}

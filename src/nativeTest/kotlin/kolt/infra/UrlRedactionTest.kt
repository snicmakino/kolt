package kolt.infra

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlRedactionTest {

  @Test
  fun plainUrlWithoutUserinfoIsUnchanged() {
    assertEquals("https://host/path", redactUrlUserinfo("https://host/path"))
  }

  @Test
  fun userinfoWithUserAndPasswordIsRemoved() {
    assertEquals("https://host/path", redactUrlUserinfo("https://u:p@host/path"))
  }

  @Test
  fun userinfoWithUserOnlyIsRemoved() {
    assertEquals("https://host/path", redactUrlUserinfo("https://u@host/path"))
  }

  @Test
  fun atSignInsidePathIsPreserved() {
    assertEquals("https://host/foo@bar", redactUrlUserinfo("https://host/foo@bar"))
  }

  @Test
  fun nonUrlInputIsUnchanged() {
    assertEquals("not-a-url-at-all", redactUrlUserinfo("not-a-url-at-all"))
  }
}

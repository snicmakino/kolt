package kolt.build

import kolt.config.SysPropValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SysPropResolverTest {

  private val projectRoot = "/home/user/proj"

  @Test
  fun resolvesLiteralVerbatim() {
    val sysProps = linkedMapOf<String, SysPropValue>("k" to SysPropValue.Literal("hello"))
    val resolved = resolveSysProps(sysProps, projectRoot, emptyMap())
    assertEquals(listOf("k" to "hello"), resolved)
  }

  @Test
  fun resolvesClasspathRefViaBundleLookup() {
    val sysProps = linkedMapOf<String, SysPropValue>("cp" to SysPropValue.ClasspathRef("foo"))
    val bundles = mapOf("foo" to "/cache/a.jar:/cache/b.jar")
    val resolved = resolveSysProps(sysProps, projectRoot, bundles)
    assertEquals(listOf("cp" to "/cache/a.jar:/cache/b.jar"), resolved)
  }

  @Test
  fun resolvesProjectDirByJoiningWithProjectRoot() {
    val sysProps =
      linkedMapOf<String, SysPropValue>("d" to SysPropValue.ProjectDir("src/main/kotlin"))
    val resolved = resolveSysProps(sysProps, projectRoot, emptyMap())
    assertEquals(listOf("d" to "/home/user/proj/src/main/kotlin"), resolved)
  }

  @Test
  fun preservesDeclarationOrderAcrossMixedValueTypes() {
    val sysProps =
      linkedMapOf<String, SysPropValue>(
        "first" to SysPropValue.Literal("L"),
        "second" to SysPropValue.ClasspathRef("foo"),
        "third" to SysPropValue.ProjectDir("res"),
        "fourth" to SysPropValue.Literal("M"),
      )
    val bundles = mapOf("foo" to "/cache/x.jar")
    val resolved = resolveSysProps(sysProps, projectRoot, bundles)
    assertEquals(
      listOf(
        "first" to "L",
        "second" to "/cache/x.jar",
        "third" to "/home/user/proj/res",
        "fourth" to "M",
      ),
      resolved,
    )
  }

  @Test
  fun doesNotExpandEnvironmentVariablesInLiteral() {
    val raw = "\$HOME/sub:\${FOO}/bar"
    val sysProps = linkedMapOf<String, SysPropValue>("k" to SysPropValue.Literal(raw))
    val resolved = resolveSysProps(sysProps, projectRoot, emptyMap())
    assertEquals(listOf("k" to raw), resolved)
  }

  @Test
  fun projectDirDotResolvesToProjectRootWithoutTrailingSlash() {
    val sysProps = linkedMapOf<String, SysPropValue>("d" to SysPropValue.ProjectDir("."))
    val resolved = resolveSysProps(sysProps, projectRoot, emptyMap())
    assertEquals(listOf("d" to "/home/user/proj"), resolved)
  }

  @Test
  fun projectDirPreservesTrailingSlash() {
    val sysProps =
      linkedMapOf<String, SysPropValue>("d" to SysPropValue.ProjectDir("src/main/kotlin/"))
    val resolved = resolveSysProps(sysProps, projectRoot, emptyMap())
    assertEquals(listOf("d" to "/home/user/proj/src/main/kotlin/"), resolved)
  }

  @Test
  fun projectDirDoesNotCheckExistence() {
    val sysProps =
      linkedMapOf<String, SysPropValue>("d" to SysPropValue.ProjectDir("does/not/exist"))
    val resolved = resolveSysProps(sysProps, projectRoot, emptyMap())
    assertEquals(listOf("d" to "/home/user/proj/does/not/exist"), resolved)
  }

  @Test
  fun emptySysPropsReturnsEmptyList() {
    val resolved = resolveSysProps(emptyMap(), projectRoot, emptyMap())
    assertEquals(emptyList(), resolved)
  }

  @Test
  fun missingBundleClasspathThrowsInvariantError() {
    val sysProps = linkedMapOf<String, SysPropValue>("cp" to SysPropValue.ClasspathRef("missing"))
    assertFailsWith<IllegalStateException> { resolveSysProps(sysProps, projectRoot, emptyMap()) }
  }
}

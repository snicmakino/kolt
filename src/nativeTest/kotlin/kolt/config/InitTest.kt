package kolt.config

import com.github.michaelbull.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitTest {
  @Test
  fun generateTomlUsesProjectName() {
    val toml = generateKoltToml("my-app")
    assertTrue(toml.contains("name = \"my-app\""))
  }

  @Test
  fun generateTomlContainsRequiredFields() {
    val toml = generateKoltToml("hello")
    assertTrue(toml.contains("version = "))
    assertTrue(toml.contains("[kotlin]"))
    assertTrue(toml.contains("[build]"))
    assertTrue(toml.contains("target = \"jvm\""))
    assertTrue(toml.contains("main = "))
    assertTrue(toml.contains("sources = "))
  }

  @Test
  fun generateTomlUsesKotlinFunctionFqnForMain() {
    // ADR 0015: bare function FQN, not JVM facade class name.
    val toml = generateKoltToml("my-app")
    assertTrue(toml.contains("main = \"main\""))
  }

  @Test
  fun generateMainKtContainsMainFunction() {
    val source = generateMainKt()
    assertTrue(source.contains("fun main()"))
  }

  @Test
  fun projectNameFromDirName() {
    assertEquals("my-app", inferProjectName("/home/user/projects/my-app"))
    assertEquals("hello", inferProjectName("/tmp/hello"))
  }

  @Test
  fun projectNameFromRootFallsBackToDefault() {
    assertEquals("project", inferProjectName("/"))
  }

  @Test
  fun projectNameFromEmptyStringFallsBackToDefault() {
    assertEquals("project", inferProjectName(""))
  }

  @Test
  fun validProjectNames() {
    assertTrue(isValidProjectName("my-app"))
    assertTrue(isValidProjectName("hello"))
    assertTrue(isValidProjectName("app_v2"))
    assertTrue(isValidProjectName("My.Project"))
  }

  @Test
  fun generateTomlDoesNotContainTestDependencies() {
    val toml = generateKoltToml("my-app")
    assertFalse(toml.contains("[test-dependencies]"))
    assertFalse(toml.contains("kotlin-test-junit5"))
  }

  @Test
  fun generateTestKtContainsTestAnnotation() {
    val source = generateTestKt()
    assertTrue(source.contains("@Test"))
    assertTrue(source.contains("import kotlin.test.Test"))
  }

  @Test
  fun generateGitignoreContainsBuildDir() {
    assertEquals("build/\n", generateGitignore())
  }

  @Test
  fun invalidProjectNames() {
    assertFalse(isValidProjectName(""))
    assertFalse(isValidProjectName("my\"app"))
    assertFalse(isValidProjectName("my app"))
    assertFalse(isValidProjectName("-start-with-dash"))
    assertFalse(isValidProjectName(".hidden"))
  }

  @Test
  fun generateTomlForLibUsesKindLib() {
    val toml = generateKoltToml("mylib", ScaffoldKind.LIB)
    assertTrue(toml.contains("kind = \"lib\""))
  }

  @Test
  fun generateTomlForLibOmitsMain() {
    val toml = generateKoltToml("mylib", ScaffoldKind.LIB)
    assertFalse(toml.contains("main = "), "library kolt.toml must not declare main")
  }

  @Test
  fun generateTomlForAppOmitsKindLine() {
    val toml = generateKoltToml("myapp", ScaffoldKind.APP)
    assertFalse(toml.contains("kind = "), "app kolt.toml should rely on default kind")
  }

  @Test
  fun generateLibKtContainsGreetFunction() {
    val source = generateLibKt()
    assertTrue(source.contains("fun greet()"))
    assertTrue(source.contains("Hello, world!"))
  }

  @Test
  fun generateLibTestKtCallsGreet() {
    val source = generateLibTestKt()
    assertTrue(source.contains("@Test"))
    assertTrue(source.contains("greet()"))
  }

  @Test
  fun generateTomlForJvmTargetIncludesJvmTarget() {
    val toml = generateKoltToml("myapp", target = "jvm")
    assertTrue(toml.contains("target = \"jvm\""))
    assertTrue(toml.contains("jvm_target = "))
  }

  @Test
  fun generateTomlForLinuxX64TargetWritesNativeTarget() {
    val toml = generateKoltToml("myapp", target = "linuxX64")
    assertTrue(toml.contains("target = \"linuxX64\""))
  }

  @Test
  fun generateTomlForNativeTargetOmitsJvmTarget() {
    val toml = generateKoltToml("myapp", target = "linuxX64")
    assertFalse(
      toml.lineSequence().any { it.trimStart().startsWith("jvm_target") },
      "jvm_target must be omitted for non-jvm targets",
    )
  }

  @Test
  fun generateTomlAppWithGroupWritesFqMain() {
    val toml = generateKoltToml("myapp", kind = ScaffoldKind.APP, group = "com.example")
    assertTrue(toml.contains("main = \"com.example.myapp.main\""))
  }

  @Test
  fun generateTomlLibWithGroupStillOmitsMain() {
    val toml = generateKoltToml("mylib", kind = ScaffoldKind.LIB, group = "com.example")
    assertFalse(toml.lineSequence().any { it.trimStart().startsWith("main") })
  }

  @Test
  fun generateTomlAppWithGroupSanitizesHyphenInName() {
    val toml = generateKoltToml("my-app", kind = ScaffoldKind.APP, group = "com.example")
    assertTrue(toml.contains("main = \"com.example.my_app.main\""))
  }

  @Test
  fun generateMainKtWithPackageDeclaresPackage() {
    val source = generateMainKt(packageDecl = "com.example.myapp")
    assertTrue(source.startsWith("package com.example.myapp\n"))
    assertTrue(source.contains("fun main()"))
  }

  @Test
  fun generateLibKtWithPackageDeclaresPackage() {
    val source = generateLibKt(packageDecl = "com.example.mylib")
    assertTrue(source.startsWith("package com.example.mylib\n"))
    assertTrue(source.contains("fun greet()"))
  }

  @Test
  fun generateTestKtWithPackageDeclaresPackage() {
    val source = generateTestKt(packageDecl = "com.example.myapp")
    assertTrue(source.startsWith("package com.example.myapp\n"))
    assertTrue(source.contains("@Test"))
  }

  @Test
  fun generateLibTestKtWithPackageDeclaresPackage() {
    val source = generateLibTestKt(packageDecl = "com.example.mylib")
    assertTrue(source.startsWith("package com.example.mylib\n"))
    assertTrue(source.contains("greet()"))
  }

  @Test
  fun projectNameToPackageSegmentReplacesHyphenAndDot() {
    assertEquals("my_app", projectNameToPackageSegment("my-app"))
    assertEquals("my_app", projectNameToPackageSegment("my.app"))
    assertEquals("a_b_c", projectNameToPackageSegment("a-b.c"))
  }

  @Test
  fun projectNameToPackageSegmentPrefixesDigitStart() {
    assertEquals("_1foo", projectNameToPackageSegment("1foo"))
    assertEquals("_9", projectNameToPackageSegment("9"))
  }

  @Test
  fun projectNameToPackageSegmentLeavesPureNamesUntouched() {
    assertEquals("myapp", projectNameToPackageSegment("myapp"))
    assertEquals("Foo_Bar", projectNameToPackageSegment("Foo_Bar"))
  }

  @Test
  fun isValidGroupAcceptsDottedSegments() {
    assertTrue(isValidGroup("com"))
    assertTrue(isValidGroup("com.example"))
    assertTrue(isValidGroup("com.example.foo"))
    assertTrue(isValidGroup("_priv.x"))
  }

  @Test
  fun isValidGroupRejectsMalformed() {
    assertFalse(isValidGroup(""))
    assertFalse(isValidGroup("com."))
    assertFalse(isValidGroup(".com"))
    assertFalse(isValidGroup("com..example"))
    assertFalse(isValidGroup("9com"))
    assertFalse(isValidGroup("com.9example"))
    assertFalse(isValidGroup("com-example"))
    assertFalse(isValidGroup("com example"))
  }

  @Test
  fun isValidGroupRejectsKotlinHardKeywords() {
    assertFalse(isValidGroup("is"))
    assertFalse(isValidGroup("com.is.example"))
    assertFalse(isValidGroup("com.in"))
    assertFalse(isValidGroup("com.if"))
    assertFalse(isValidGroup("class"))
    assertFalse(isValidGroup("package"))
  }

  @Test
  fun generatedTomlForAppWithGroupParsesAndKeepsFqMain() {
    val toml = generateKoltToml("myapp", kind = ScaffoldKind.APP, group = "com.example")
    val parsed = parseConfig(toml).getOrElse { error("parseConfig failed: $it") }
    assertEquals("myapp", parsed.name)
    assertEquals("app", parsed.kind)
    assertEquals("com.example.myapp.main", parsed.build.main)
  }

  @Test
  fun generatedTomlForLibWithGroupParsesAndOmitsMain() {
    val toml = generateKoltToml("mylib", kind = ScaffoldKind.LIB, group = "com.example")
    val parsed = parseConfig(toml).getOrElse { error("parseConfig failed: $it") }
    assertEquals("lib", parsed.kind)
    assertNull(parsed.build.main)
  }

  @Test
  fun generatedTomlForNativeTargetParses() {
    val toml = generateKoltToml("myapp", target = "linuxX64")
    val parsed = parseConfig(toml).getOrElse { error("parseConfig failed: $it") }
    assertEquals("linuxX64", parsed.build.target)
  }
}

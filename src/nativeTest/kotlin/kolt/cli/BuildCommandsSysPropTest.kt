package kolt.cli

import kolt.build.Profile
import kolt.config.RunSection
import kolt.config.SysPropValue
import kolt.config.TestSection
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Req 3.1 / 3.2: doTestInner and doRunInner must thread declared
// `[test.sys_props]` / `[run.sys_props]` through to the JVM argv. The wiring
// is exercised at the boundary between BuildCommands and the runner: each
// call selects the correct sysProps source (testSection vs runSection),
// passes the project root and bundleClasspaths to resolveSysProps, and
// hands the resolved (k, v) list to testRunCommand / runCommand.
//
// `jvmTestArgv` / `jvmRunArgv` are the testable extracts of that wiring.
class BuildCommandsSysPropTest {

  // Req 3.1 / 3.5: literal sys_props in `[test.sys_props]` reach the test
  // JVM verbatim, in declaration order, immediately after `java`.
  @Test
  fun jvmTestArgvThreadsLiteralSysPropsInDeclarationOrder() {
    val config =
      testConfig()
        .copy(
          testSection =
            TestSection(
              sysProps =
                linkedMapOf(
                  "first" to SysPropValue.Literal("one"),
                  "second" to SysPropValue.Literal("two"),
                  "third" to SysPropValue.Literal("three"),
                )
            )
        )

    val args =
      jvmTestArgv(
        config = config,
        projectRoot = "/proj",
        bundleClasspaths = emptyMap(),
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testResourceDirs = emptyList(),
        testClasspath = null,
        testArgs = emptyList(),
        javaPath = null,
      )

    assertEquals("java", args[0])
    assertEquals("-Dfirst=one", args[1])
    assertEquals("-Dsecond=two", args[2])
    assertEquals("-Dthird=three", args[3])
    assertEquals("-jar", args[4])
  }

  // Req 3.1 / 3.3 / 3.4: a mix of literal / classpath / project_dir values
  // round-trips correctly through the wiring. Classpath references resolve
  // against `bundleClasspaths` (Req 3.3, colon-joined absolute paths);
  // project_dir resolves against `projectRoot` (Req 3.4).
  @Test
  fun jvmTestArgvResolvesAllThreeShapesAgainstProjectRootAndBundles() {
    val config =
      testConfig()
        .copy(
          testSection =
            TestSection(
              sysProps =
                linkedMapOf(
                  "kolt.literal" to SysPropValue.Literal("hello"),
                  "kolt.tools" to SysPropValue.ClasspathRef("tools"),
                  "kolt.fixture" to SysPropValue.ProjectDir("test/fixtures"),
                )
            )
        )

    val args =
      jvmTestArgv(
        config = config,
        projectRoot = "/abs/proj",
        bundleClasspaths = mapOf("tools" to "/cache/a.jar:/cache/b.jar"),
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testResourceDirs = emptyList(),
        testClasspath = null,
        testArgs = emptyList(),
        javaPath = null,
      )

    assertEquals(
      listOf(
        "java",
        "-Dkolt.literal=hello",
        "-Dkolt.tools=/cache/a.jar:/cache/b.jar",
        "-Dkolt.fixture=/abs/proj/test/fixtures",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--scan-class-path",
      ),
      args,
    )
  }

  // Req 7.2: when `[test.sys_props]` is absent, the test JVM argv is
  // byte-identical to the pre-feature shape — no `-D` flags appear.
  @Test
  fun jvmTestArgvProducesPreFeatureArgvWhenTestSysPropsEmpty() {
    val config = testConfig()

    val args =
      jvmTestArgv(
        config = config,
        projectRoot = "/proj",
        bundleClasspaths = emptyMap(),
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testResourceDirs = emptyList(),
        testClasspath = null,
        testArgs = emptyList(),
        javaPath = null,
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--scan-class-path",
      ),
      args,
    )
    assertTrue(args.none { it.startsWith("-D") }, "no -D flags expected when test.sys_props empty")
  }

  // Req 3.1 vs 3.2: doTestInner must read `testSection.sysProps`, not
  // `runSection.sysProps`. Pinning this independently catches a wiring
  // swap that would otherwise be silent for projects that declare both.
  @Test
  fun jvmTestArgvDoesNotConsumeRunSectionSysProps() {
    val config =
      testConfig()
        .copy(
          testSection =
            TestSection(sysProps = linkedMapOf("test.key" to SysPropValue.Literal("t"))),
          runSection = RunSection(sysProps = linkedMapOf("run.key" to SysPropValue.Literal("r"))),
        )

    val args =
      jvmTestArgv(
        config = config,
        projectRoot = "/proj",
        bundleClasspaths = emptyMap(),
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testResourceDirs = emptyList(),
        testClasspath = null,
        testArgs = emptyList(),
        javaPath = null,
      )

    assertTrue(args.contains("-Dtest.key=t"), "test.sys_props value must reach test JVM: $args")
    assertTrue(
      args.none { it == "-Drun.key=r" },
      "run.sys_props value must not leak into test JVM: $args",
    )
  }

  // Req 3.2 / 3.5: literal sys_props in `[run.sys_props]` reach the
  // application JVM verbatim, in declaration order, immediately after
  // `java`. doRun's argv has -cp/main rather than -jar/launcher; the -D
  // flags still occupy the slots immediately after `java`.
  @Test
  fun jvmRunArgvThreadsLiteralSysPropsInDeclarationOrder() {
    val config =
      testConfig()
        .copy(
          runSection =
            RunSection(
              sysProps =
                linkedMapOf(
                  "alpha" to SysPropValue.Literal("a"),
                  "beta" to SysPropValue.Literal("b"),
                )
            )
        )

    val args =
      jvmRunArgv(
        config = config,
        projectRoot = "/proj",
        bundleClasspaths = emptyMap(),
        classpath = null,
        appArgs = emptyList(),
        javaPath = null,
        profile = Profile.Debug,
      )

    assertEquals("java", args[0])
    assertEquals("-Dalpha=a", args[1])
    assertEquals("-Dbeta=b", args[2])
    assertEquals("-cp", args[3])
  }

  // Req 3.2 / 3.3 / 3.4: run-side mix of literal / classpath / project_dir
  // resolves against `bundleClasspaths` and `projectRoot` analogously.
  @Test
  fun jvmRunArgvResolvesAllThreeShapesAgainstProjectRootAndBundles() {
    val config =
      testConfig()
        .copy(
          runSection =
            RunSection(
              sysProps =
                linkedMapOf(
                  "app.lit" to SysPropValue.Literal("x"),
                  "app.plug" to SysPropValue.ClasspathRef("plugins"),
                  "app.work" to SysPropValue.ProjectDir("workdir"),
                )
            )
        )

    val args =
      jvmRunArgv(
        config = config,
        projectRoot = "/abs/proj",
        bundleClasspaths = mapOf("plugins" to "/cache/p1.jar:/cache/p2.jar"),
        classpath = null,
        appArgs = emptyList(),
        javaPath = null,
        profile = Profile.Debug,
      )

    assertTrue(args.contains("-Dapp.lit=x"))
    assertTrue(args.contains("-Dapp.plug=/cache/p1.jar:/cache/p2.jar"))
    assertTrue(args.contains("-Dapp.work=/abs/proj/workdir"))
    // Order: java, -Dapp.lit=x, -Dapp.plug=..., -Dapp.work=..., -cp, ...
    assertEquals(
      listOf(
        "-Dapp.lit=x",
        "-Dapp.plug=/cache/p1.jar:/cache/p2.jar",
        "-Dapp.work=/abs/proj/workdir",
      ),
      args.subList(1, 4),
    )
  }

  // Req 7.3: when `[run.sys_props]` is absent, the run JVM argv is
  // byte-identical to the pre-feature shape — no `-D` flags appear.
  @Test
  fun jvmRunArgvProducesPreFeatureArgvWhenRunSysPropsEmpty() {
    val config = testConfig()

    val args =
      jvmRunArgv(
        config = config,
        projectRoot = "/proj",
        bundleClasspaths = emptyMap(),
        classpath = null,
        appArgs = emptyList(),
        javaPath = null,
        profile = Profile.Debug,
      )

    assertTrue(args.none { it.startsWith("-D") }, "no -D flags expected when run.sys_props empty")
    assertEquals("java", args[0])
    assertEquals("-cp", args[1])
  }

  // Req 3.2 vs 3.1: doRunInner must read `runSection.sysProps`, not
  // `testSection.sysProps`.
  @Test
  fun jvmRunArgvDoesNotConsumeTestSectionSysProps() {
    val config =
      testConfig()
        .copy(
          testSection =
            TestSection(sysProps = linkedMapOf("test.key" to SysPropValue.Literal("t"))),
          runSection = RunSection(sysProps = linkedMapOf("run.key" to SysPropValue.Literal("r"))),
        )

    val args =
      jvmRunArgv(
        config = config,
        projectRoot = "/proj",
        bundleClasspaths = emptyMap(),
        classpath = null,
        appArgs = emptyList(),
        javaPath = null,
        profile = Profile.Debug,
      )

    assertTrue(args.contains("-Drun.key=r"), "run.sys_props value must reach app JVM: $args")
    assertTrue(
      args.none { it == "-Dtest.key=t" },
      "test.sys_props value must not leak into app JVM: $args",
    )
  }
}

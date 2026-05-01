package kolt.cli

import com.github.michaelbull.result.getError
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

  // `kolt run --watch` shares this argv-build helper with the one-shot
  // path so `[run.sys_props]` resolution cannot diverge between modes.
  // The helper unpacks BuildResult into the same jvmRunArgv call the
  // one-shot path makes; this test pins that the bundle-classpath
  // threading survives the BuildResult round-trip.
  @Test
  fun runJvmCommandForUnpacksBuildResultThroughJvmRunArgv() {
    val config =
      testConfig()
        .copy(
          runSection =
            RunSection(
              sysProps =
                linkedMapOf(
                  "run.lit" to SysPropValue.Literal("v"),
                  "run.plug" to SysPropValue.ClasspathRef("plugins"),
                )
            )
        )
    val buildResult =
      BuildResult(
        config = config,
        classpath = "/cache/dep.jar",
        javaPath = "/jdk/bin/java",
        bundleClasspaths = mapOf("plugins" to "/cache/p1.jar:/cache/p2.jar"),
      )

    val cmd = runJvmCommandFor(buildResult, "/abs/proj", listOf("--app-arg"), Profile.Debug)

    assertEquals("/jdk/bin/java", cmd.args[0])
    assertEquals("-Drun.lit=v", cmd.args[1])
    assertEquals("-Drun.plug=/cache/p1.jar:/cache/p2.jar", cmd.args[2])
    assertEquals("-cp", cmd.args[3])
    assertEquals("--app-arg", cmd.args.last())
  }

  // A BuildResult without `[run.sys_props]` must produce argv with no
  // `-D` slots, byte-identical to the no-sysprops shape. Guards against
  // a future helper edit accidentally inserting an empty-key sysprop or
  // a default `-D` flag.
  @Test
  fun runJvmCommandForOmitsSysPropsWhenRunSectionEmpty() {
    val buildResult = BuildResult(config = testConfig(), classpath = null, javaPath = null)

    val cmd = runJvmCommandFor(buildResult, "/proj", emptyList(), Profile.Debug)

    assertTrue(cmd.args.none { it.startsWith("-D") }, "no -D flags expected: ${cmd.args}")
    assertEquals("java", cmd.args[0])
    assertEquals("-cp", cmd.args[1])
  }

  // #319: CLI -D flags appended after toml-declared sysprops, in
  // command-line order, when there are no key collisions.
  @Test
  fun jvmTestArgvAppendsCliSysPropsAfterTomlSysProps() {
    val config =
      testConfig()
        .copy(
          testSection =
            TestSection(sysProps = linkedMapOf("toml.first" to SysPropValue.Literal("a")))
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
        cliSysProps = listOf("cli.first" to "b", "cli.second" to "c"),
      )

    assertEquals("java", args[0])
    assertEquals("-Dtoml.first=a", args[1])
    assertEquals("-Dcli.first=b", args[2])
    assertEquals("-Dcli.second=c", args[3])
    assertEquals("-jar", args[4])
  }

  // #319: a CLI -D flag with the same key as a `[test.sys_props]` entry
  // overlays it. Toml entry is dropped; CLI value appears at the CLI
  // position (after surviving toml entries).
  @Test
  fun jvmTestArgvCliOverlayWinsOnKeyCollision() {
    val config =
      testConfig()
        .copy(
          testSection =
            TestSection(
              sysProps =
                linkedMapOf(
                  "shared" to SysPropValue.Literal("toml-value"),
                  "toml.only" to SysPropValue.Literal("survives"),
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
        cliSysProps = listOf("shared" to "cli-value"),
      )

    assertTrue(
      args.none { it == "-Dshared=toml-value" },
      "toml entry must be dropped on collision: $args",
    )
    assertEquals(1, args.count { it == "-Dshared=cli-value" })
    assertEquals(1, args.count { it == "-Dtoml.only=survives" })
    assertTrue(
      args.indexOf("-Dtoml.only=survives") < args.indexOf("-Dshared=cli-value"),
      "non-colliding toml entries come before CLI overlay: $args",
    )
  }

  // #319: same shape as the test counterpart for jvmRunArgv (Req 3.2).
  @Test
  fun jvmRunArgvAppendsCliSysPropsAfterTomlSysProps() {
    val config =
      testConfig()
        .copy(
          runSection = RunSection(sysProps = linkedMapOf("run.toml" to SysPropValue.Literal("t")))
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
        cliSysProps = listOf("run.cli" to "c"),
      )

    assertEquals("java", args[0])
    assertEquals("-Drun.toml=t", args[1])
    assertEquals("-Drun.cli=c", args[2])
  }

  @Test
  fun jvmRunArgvCliOverlayWinsOnKeyCollision() {
    val config =
      testConfig()
        .copy(
          runSection =
            RunSection(
              sysProps =
                linkedMapOf(
                  "run.shared" to SysPropValue.Literal("toml-value"),
                  "run.only" to SysPropValue.Literal("survives"),
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
        cliSysProps = listOf("run.shared" to "cli-value"),
      )

    assertTrue(args.none { it == "-Drun.shared=toml-value" }, "toml entry dropped on collision")
    assertEquals(1, args.count { it == "-Drun.shared=cli-value" })
    assertEquals(1, args.count { it == "-Drun.only=survives" })
  }

  // #319 (watch): CLI -D flags must reach the watched JVM run path through
  // `runJvmCommandFor`, mirroring the one-shot `kolt run -D...` shape.
  @Test
  fun runJvmCommandForThreadsCliSysProps() {
    val buildResult = BuildResult(config = testConfig(), classpath = null, javaPath = null)

    val cmd =
      runJvmCommandFor(
        buildResult,
        "/proj",
        emptyList(),
        Profile.Debug,
        cliSysProps = listOf("foo" to "bar"),
      )

    assertEquals("java", cmd.args[0])
    assertEquals("-Dfoo=bar", cmd.args[1])
  }

  // #319: CLI duplicate keys pass through verbatim. JVM resolves
  // last-write-wins for `-Dfoo=a -Dfoo=b`; if a future edit introduces
  // CLI-side dedup the contract documented on `overlayCliSysProps` would
  // silently shift, so pin both occurrences in the argv.
  @Test
  fun jvmRunArgvCliDuplicateKeysPassThroughVerbatim() {
    val args =
      jvmRunArgv(
        config = testConfig(),
        projectRoot = "/proj",
        bundleClasspaths = emptyMap(),
        classpath = null,
        appArgs = emptyList(),
        javaPath = null,
        profile = Profile.Debug,
        cliSysProps = listOf("foo" to "a", "foo" to "b"),
      )

    val foos = args.filter { it.startsWith("-Dfoo=") }
    assertEquals(listOf("-Dfoo=a", "-Dfoo=b"), foos, "both CLI dup keys must reach the JVM argv")
  }

  // #319: CLI `-D` is JVM-only. Mirrors the parse-time native rejection
  // of `[run.sys_props]` / `[test.sys_props]` so the contract is symmetric:
  // declaring sysprops in kolt.toml on a native target errors at parse;
  // supplying them on the CLI errors here. Without this gate the flag is
  // silently dropped.
  @Test
  fun rejectCliSysPropsOnNativeReturnsConfigErrorWithCanonicalMessage() {
    val nativeConfig = testConfig(target = "linuxX64")
    val stderr = mutableListOf<String>()

    val exit =
      rejectCliSysPropsOnNative(nativeConfig, listOf("foo" to "bar"), eprint = { stderr.add(it) })
        .getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
    assertEquals(1, stderr.size)
    assertTrue(
      stderr[0].contains("-D<key>=<value> is JVM-only") &&
        stderr[0].contains("native target 'linuxX64'"),
      "stderr must mention JVM-only and the native target name, got: ${stderr[0]}",
    )
  }

  // Pass-through cases: no CLI flags, or JVM target. Either yields Ok with
  // no stderr; the gate must never spam.
  @Test
  fun rejectCliSysPropsOnNativeIsNoopForJvmTargetOrEmptyCliSysProps() {
    val stderr = mutableListOf<String>()

    val jvmWithCli =
      rejectCliSysPropsOnNative(
        testConfig(target = "jvm"),
        listOf("foo" to "bar"),
        eprint = { stderr.add(it) },
      )
    val nativeWithoutCli =
      rejectCliSysPropsOnNative(
        testConfig(target = "linuxX64"),
        emptyList(),
        eprint = { stderr.add(it) },
      )

    assertTrue(jvmWithCli.isOk, "JVM target with CLI flags must pass")
    assertTrue(nativeWithoutCli.isOk, "native target without CLI flags must pass")
    assertTrue(stderr.isEmpty(), "no stderr expected: $stderr")
  }
}

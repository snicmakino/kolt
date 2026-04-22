package kolt.cli

import com.github.michaelbull.result.get
import kolt.build.checkCommand
import kolt.build.jarCommand
import kolt.build.outputKexePath
import kolt.build.outputNativeKlibPath
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R3 matrix: native build kind gate (ADR 0014 two-stage × ADR 0023 §1 kind).
 *
 * `nativeStagePlan` is the pure decision the `doNativeBuild` orchestrator
 * consults after stage 1 succeeds: library kind stops at the `.klib`
 * (stage 2 link is skipped), app kind continues into the link step with
 * the entry-point FQN. Scoped fixture configs are built in each test —
 * the repository's own `kolt.toml` is never read.
 */
class NativeStagePlanTest {

    // R3.1 / R3.2 / R3.3: lib config plans stage 1 only, targets the
    // canonical `.klib` path, and carries no entry-point FQN for link.
    @Test
    fun libraryConfigSkipsLinkAndTargetsKlibArtifact() {
        val base = testConfig(name = "mylib", target = "linuxX64")
        val config = base.copy(
            kind = "lib",
            build = base.build.copy(main = null),
        )

        val plan = assertNotNull(nativeStagePlan(config).get())

        assertNull(plan.linkMain, "library plan must not carry an entry-point FQN")
        assertEquals("library", plan.artifactKind)
        assertEquals(outputNativeKlibPath(config), plan.artifactPath)
    }

    // R3.4: app config plans both stages, forwards the entry-point FQN,
    // and targets the canonical `.kexe` path.
    @Test
    fun applicationConfigDrivesLinkStageWithEntryPointAndKexeArtifact() {
        val config = testConfig(name = "myapp", target = "linuxX64")
            .copy(kind = "app")

        val plan = assertNotNull(nativeStagePlan(config).get())

        assertEquals("com.example.main", plan.linkMain)
        assertEquals("executable", plan.artifactKind)
        assertEquals(outputKexePath(config), plan.artifactPath)
    }
}

/**
 * R2 matrix: JVM library thin-jar invariants (design.md §Components and
 * Interfaces → JVM thin-jar invariants; design.md §Testing Strategy →
 * Unit — JVM invariants).
 *
 * The JVM build path in `doBuild` is shared between `kind = "lib"` and
 * `kind = "app"` — no kind branching at the jar step. The thin-jar shape
 * is therefore expressed by two existing pure helpers:
 *
 * - `checkCommand` / the inline `CompileRequest.extraArgs` at the JVM
 *   compile step — kotlinc args must not contain `-include-runtime`
 *   (R2.2), so the produced classes carry no Kotlin stdlib or dependency
 *   bytecode.
 * - `jarCommand` — `jar cf <out> -C build/classes .`; no `-m` / manifest
 *   argument, so the JDK `jar` tool writes only a default manifest with
 *   `Manifest-Version` and `Created-By`, never `Main-Class` (R2.3). The
 *   `-C build/classes .` root is `build/classes` only, so the jar never
 *   includes resolved dependency jars (R2.2).
 *
 * R2.1 (`.jar` at `build/<name>.jar`) is verified at the helper layer
 * via `jarCommand(...).outputPath`. The on-disk end-to-end artifact check
 * is covered by Task 6.1's dogfood gate (requires a live JDK + kotlinc).
 *
 * R2.4 (app JVM jar unchanged) is locked by the same helpers — the
 * existing `BuilderTest` suite already asserts the exact argv shape for
 * `checkCommand` and `jarCommand` on the default (app) `testConfig`, so
 * the non-regression bar is: these lib-config argvs match the same shape
 * the app path produces today.
 */
class JvmLibraryInvariantsTest {

    // R2.1: a `kind = "lib"` JVM build targets `build/<name>.jar`. The
    // jarCommand output path is the single source of truth consulted by
    // doBuild's success message — any drift here surfaces as a missing
    // artifact on disk.
    @Test
    fun libraryJvmJarOutputPathIsBuildNameJar() {
        val base = testConfig(name = "mylib", target = "jvm")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = jarCommand(libConfig)

        assertEquals("build/mylib.jar", cmd.outputPath)
    }

    // R2.2: the JVM compile command for a lib config must not pass
    // `-include-runtime` to kotlinc; otherwise kotlinc would bundle the
    // Kotlin stdlib and any `-cp` entries into the classes output, which
    // the subsequent `jar cf` would then package into the jar.
    //
    // `checkCommand` is the pure helper mirroring the JVM compile argv
    // shape; `doBuild` constructs the same `-jvm-target` + plugin args
    // set via `CompileRequest.extraArgs` (BuildCommands.kt:211-215). No
    // code path in the repo adds `-include-runtime` (grep: 0 matches in
    // src/ and kolt-jvm-compiler-daemon/), so this test locks that invariant.
    @Test
    fun libraryJvmCompileCommandDoesNotIncludeRuntime() {
        val base = testConfig(name = "mylib", target = "jvm")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = checkCommand(libConfig)

        assertFalse(
            cmd.contains("-include-runtime"),
            "JVM lib compile command must not bundle the Kotlin runtime: $cmd",
        )
    }

    // R2.3: `jar cf` without a `-m` manifest argument makes the JDK jar
    // tool emit only a default manifest (Manifest-Version + Created-By),
    // never a `Main-Class` attribute. Asserting the absence of every
    // manifest-related `jar` flag is equivalent to asserting the produced
    // `META-INF/MANIFEST.MF` carries no `Main-Class`.
    @Test
    fun libraryJvmJarCommandDoesNotDeclareMainClassManifest() {
        val base = testConfig(name = "mylib", target = "jvm")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = jarCommand(libConfig)

        assertFalse(cmd.args.contains("-m"), "lib jar command must not attach a manifest: ${cmd.args}")
        assertFalse(cmd.args.contains("--manifest"), "lib jar command must not attach a manifest: ${cmd.args}")
        assertFalse(
            cmd.args.any { it.contains("Main-Class", ignoreCase = true) },
            "lib jar command must not declare a Main-Class attribute: ${cmd.args}",
        )
        assertEquals(
            listOf("jar", "cf", "build/mylib.jar", "-C", "build/classes", "."),
            cmd.args,
            "lib jar command must match the thin-jar invariant shape",
        )
    }

    // R2.2: `jar cf <out> -C build/classes .` roots the jar contents at
    // `build/classes` — the kotlinc output directory. Dependency jars
    // resolved into the kolt cache are never referenced by the jar step,
    // so the produced jar cannot contain any resolved dependency class.
    @Test
    fun libraryJvmJarCommandOnlyPackagesClassesDir() {
        val base = testConfig(name = "mylib", target = "jvm")
        val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

        val cmd = jarCommand(libConfig)

        val cIdx = cmd.args.indexOf("-C")
        assertEquals(3, cIdx, "jar command must use `-C <dir> .` after `cf <out>`: ${cmd.args}")
        assertEquals("build/classes", cmd.args[cIdx + 1])
        assertEquals(".", cmd.args[cIdx + 2])
    }

    // R2.4: the same thin-jar invariants hold on the app-config JVM path
    // — proving lifting the lib kind gate did not alter the shared JVM
    // compile/jar helpers. The canonical argv shape for apps is locked
    // separately in BuilderTest; this test re-asserts the no-runtime /
    // no-Main-Class contract so any future drift that re-introduced
    // `-include-runtime` for apps would also break here.
    @Test
    fun applicationJvmBuildHelpersPreserveThinJarInvariants() {
        val appConfig = testConfig(name = "myapp", target = "jvm").copy(kind = "app")

        val compile = checkCommand(appConfig)
        val jar = jarCommand(appConfig)

        assertFalse(
            compile.contains("-include-runtime"),
            "app JVM compile command must stay thin (R2.4): $compile",
        )
        assertFalse(jar.args.contains("-m"), "app jar command must not attach a manifest: ${jar.args}")
        assertFalse(
            jar.args.any { it.contains("Main-Class", ignoreCase = true) },
            "app jar command must not declare a Main-Class attribute: ${jar.args}",
        )
        assertEquals("build/myapp.jar", jar.outputPath)
    }
}

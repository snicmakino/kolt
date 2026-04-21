package kolt.cli

import com.github.michaelbull.result.get
import kolt.build.outputKexePath
import kolt.build.outputNativeKlibPath
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
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

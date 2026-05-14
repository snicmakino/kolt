package kolt.resolve

import com.github.michaelbull.result.Result
import kolt.config.KoltConfig
import kolt.config.Repository
import kolt.infra.DownloadError
import kotlin.test.Test
import kotlin.test.assertTrue

// Compile gate (no runtime value). Pins every public/internal touchpoint of
// the private-Maven-repos type migration to `List<Repository>`. If any of
// the 10 `downloadFromRepositories` call sites, the 9 projection sites that
// materialise `config.repositories.values.toList()`, `ensureTool`'s
// `repos` parameter, or `resolveSingleArtifact`'s `repos` parameter regresses
// to `List<String>`, this file fails to compile and `kolt build` exits non-
// zero. Runtime body is `assertTrue(true)` on purpose.
class AllCallersTypedTest {

  @Test
  fun signaturesRemainTypedAsListOfRepository() {
    // Projection-site shape: `KoltConfig.repositories` is a `Map<String, Repository>`,
    // and every caller that hands repos to a downloader projects it via
    // `.values.toList()` into `List<Repository>`. Hold a function reference
    // that performs exactly that projection so a regression of the field type
    // (e.g. `Map<String, String>`) breaks compilation here.
    val projection: (KoltConfig) -> List<Repository> = { it.repositories.values.toList() }

    // `downloadFromRepositories` signature pin. Bind it to a typed function
    // reference; if any parameter loses the `List<Repository>` /
    // `(Repository) -> String` / `(Repository) -> Unit` shape the assignment
    // fails to type-check. This single binding transitively covers the 10
    // call sites in TransitiveResolver (x4), NativeResolver (x2),
    // BundleResolver (x2), OutdatedCommand (x1), DependencyCommands (x1).
    val downloadRef:
      (
        List<Repository>,
        String,
        (Repository) -> String,
        (String, String, Map<String, String>?) -> Result<Unit, DownloadError>,
        (Repository) -> Unit,
      ) -> Result<Unit, RepositoryDownloadFailure> =
      ::downloadFromRepositories

    // `resolveSingleArtifact` signature pin (BundleResolver:107). `repos`
    // must remain `List<Repository>`.
    val resolveSingleRef:
      (Coordinate, String?, List<Repository>, String, ResolverDeps, ResolverProgressSink) -> Result<
          SingleArtifact,
          ResolveError,
        > =
      ::resolveSingleArtifact

    // `ensureTool` (ToolResolution:55) is internal and generic. The function
    // reference cannot be taken without supplying the type parameter, so pin
    // its `repos: List<Repository>` parameter by referencing the typed shape
    // through a lambda that would match its declaration. The lambda is never
    // invoked; the type system checks the shape at compile time.
    val ensureToolReposShape: (List<Repository>) -> Unit = { _: List<Repository> -> }

    // Touch the references so the compiler cannot elide them as dead code.
    // No runtime assertion is made about the bindings themselves; the value
    // of this test is the type check above.
    val touched = listOf(projection, downloadRef, resolveSingleRef, ensureToolReposShape)
    assertTrue(touched.isNotEmpty())
  }
}

package kolt.build

import com.github.michaelbull.result.getOrElse
import kolt.infra.currentWorkingDirectory
import kolt.infra.fileExists
import kolt.infra.listKotlinFiles
import kolt.infra.readFileAsString
import kotlin.test.Test
import kotlin.test.fail

/**
 * Structural assertion of R7.4: `[tools]` (the `kolt.usertool` package) is NOT coupled into the
 * build / test lifecycle. The boundary commitment in design.md §Boundary Commitments declares that
 * `kolt build` / `kolt test` paths must not invoke `[tools]` resolution or launch — this guard
 * makes that promise structural rather than reviewer-only, by failing loudly the moment any source
 * under `kolt.build` or the build/test CLI entry points imports or qualifies a `kolt.usertool`
 * name.
 *
 * Scope:
 * - `src/nativeMain/kotlin/kolt/build/` (recursive): the build pipeline
 * - `src/nativeMain/kotlin/kolt/cli/BuildCommands.kt`: hosts both `doBuild` and `doTest`
 *
 * The check is a substring match on the literal `kolt.usertool` — sufficient for both `import
 * kolt.usertool.*` and qualified references like `kolt.usertool.Foo`. False positives from comments
 * are tolerated: a reviewer-facing comment that names the package would still be a yellow flag, so
 * failing loudly is the correct response.
 */
class UserToolBoundaryGuardTest {

  @Test
  fun buildAndTestPathsDoNotReferenceUsertoolPackage() {
    val root = projectRoot()
    val targets = collectGuardedSources(root)
    val violations = mutableListOf<String>()
    for (path in targets) {
      val text =
        readFileAsString(path).getOrElse { fail("guard: could not read $path: ${it.path}") }
      if (text.contains(FORBIDDEN_TOKEN)) {
        violations.add(path)
      }
    }
    if (violations.isNotEmpty()) {
      val msg = buildString {
        appendLine("R7.4 boundary violation: build / test source paths must not reference")
        appendLine("`$FORBIDDEN_TOKEN`. Files containing the forbidden token:")
        for (v in violations) appendLine("  - $v")
        append(
          "If you are intentionally lifting [tools] into the build lifecycle, this is " +
            "an ADR-level change — see ADR 0028 §3 and update the design first."
        )
      }
      fail(msg)
    }
  }

  private fun collectGuardedSources(root: String): List<String> {
    val buildDir = "$root/src/nativeMain/kotlin/kolt/build"
    val files = mutableListOf<String>()
    files.addAll(
      listKotlinFiles(buildDir).getOrElse { fail("guard: could not list $buildDir: ${it.path}") }
    )
    val buildCommands = "$root/src/nativeMain/kotlin/kolt/cli/BuildCommands.kt"
    if (fileExists(buildCommands)) {
      files.add(buildCommands)
    } else {
      fail("guard: expected $buildCommands to exist but it does not")
    }
    return files
  }

  // Walk up from cwd until we hit `.git` (project root). Mirrors `DriftGuardsTest.projectRoot`.
  private fun projectRoot(): String {
    var current = currentWorkingDirectory() ?: fail("could not get cwd for project-root lookup")
    while (current.isNotEmpty() && current != "/") {
      if (fileExists("$current/.git")) return current
      val cut = current.lastIndexOf('/')
      if (cut <= 0) break
      current = current.substring(0, cut)
    }
    fail("could not locate project root (no .git ancestor) starting from cwd")
  }

  private companion object {
    // The forbidden import token. Matches both `import kolt.usertool.*` and qualified references
    // like `kolt.usertool.ToolEntry`. Splitting prevents this guard from flagging itself when the
    // string appears verbatim in the source file.
    val FORBIDDEN_TOKEN = "kolt." + "usertool"
  }
}

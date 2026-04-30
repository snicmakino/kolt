package kolt.build

import kolt.config.SysPropValue
import kolt.infra.absolutise

// Resolves declared sys_props into (key, resolved-value) pairs in declaration
// order. Pure function: no I/O, no env-var expansion. Invariant violations
// (e.g., bundleClasspaths missing a name the parser said was valid) use
// error() for fail-fast rather than Result, per ADR 0001 — Result is for
// fallible operations; "should be impossible" states are programmer errors.
fun resolveSysProps(
  sysProps: Map<String, SysPropValue>,
  projectRoot: String,
  bundleClasspaths: Map<String, String>,
): List<Pair<String, String>> =
  sysProps.map { (key, value) ->
    key to
      when (value) {
        is SysPropValue.Literal -> value.value
        is SysPropValue.ClasspathRef ->
          bundleClasspaths[value.bundleName]
            ?: error(
              "invariant: bundle '${value.bundleName}' missing from bundleClasspaths " +
                "(should have been rejected at parse time)"
            )
        // "." is the declarative way to say "the project root itself".
        // absolutise would produce "<root>/." which is semantically equal
        // but textually surprising; design.md fixes the canonical form to
        // <projectRoot>. Trailing slashes on other inputs are preserved by
        // absolutise (it only trims the cwd's trailing slash, not the path's).
        is SysPropValue.ProjectDir ->
          if (value.relativePath == ".") projectRoot
          else absolutise(value.relativePath, projectRoot)
      }
  }

package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.BuildSection
import kolt.config.KoltConfig
import kolt.config.KotlinSection
import kolt.config.MAVEN_CENTRAL_BASE
import kolt.resolve.Lockfile
import kolt.resolve.ResolveError
import kolt.resolve.ResolveResult
import kolt.resolve.ResolverDeps
import kolt.resolve.resolveTransitive

// #138: per-version daemon. Fetches the kotlin-build-tools-impl classpath
// (impl jar + transitive deps) for a requested 2.3.x patch when the
// libexec-bundled set does not match. The resolver does the heavy lifting
// (POM walk, Maven Central GETs, sha256 stamping); this file is a thin
// shim that synthesises a one-dependency KoltConfig and unwraps the
// resulting cache paths.

sealed interface BtaImplFetchError {
    data class ResolveFailed(val version: String, val cause: ResolveError) : BtaImplFetchError
    // Defensive: malformed coords or a 404 the resolver swallows could leave
    // us with an empty list. Catching here lets the precondition layer route
    // it through the daemon-fail warning rail instead of letting an empty
    // classpath reach BtaIncrementalCompiler.create's require check.
    data class ResolvedEmpty(val version: String) : BtaImplFetchError
}

private const val BTA_IMPL_GROUP_ARTIFACT = "org.jetbrains.kotlin:kotlin-build-tools-impl"

internal fun ensureBtaImplJars(
    version: String,
    cacheBase: String,
    deps: ResolverDeps,
    resolver: (KoltConfig, Lockfile?, String, ResolverDeps) -> Result<ResolveResult, ResolveError> =
        ::resolveTransitive,
): Result<List<String>, BtaImplFetchError> {
    // KoltConfig has many irrelevant fields here (name, main, sources). We
    // pin them to safe sentinels: nothing in the resolver path inspects
    // them, and `target = "jvm"` keeps us on the JVM resolution branch.
    val syntheticConfig = KoltConfig(
        name = "kolt-bta-impl-fetch",
        version = version,
        kotlin = KotlinSection(version = version),
        build = BuildSection(
            target = "jvm",
            main = "unused.Main",
            sources = emptyList(),
        ),
        dependencies = mapOf(BTA_IMPL_GROUP_ARTIFACT to version),
        repositories = mapOf("central" to MAVEN_CENTRAL_BASE),
    )
    val result = resolver(syntheticConfig, null, cacheBase, deps).getOrElse {
        return Err(BtaImplFetchError.ResolveFailed(version, it))
    }
    if (result.deps.isEmpty()) return Err(BtaImplFetchError.ResolvedEmpty(version))
    return Ok(result.deps.map { it.cachePath })
}

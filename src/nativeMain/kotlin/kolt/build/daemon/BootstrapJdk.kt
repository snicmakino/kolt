package kolt.build.daemon

import kolt.config.KoltPaths
import kolt.infra.eprintln
import kolt.tool.ensureJdkBins
import kotlin.system.exitProcess

// Version of the JDK kolt ships for running the compiler daemon.
//
// Pinned deliberately: the daemon is a kolt internal, independent of
// whatever JDK the user has chosen for their project (kolt.toml
// [build] jdk). A project that pins JDK 11 must still be able to run
// the daemon, so the daemon has its own toolchain slot. Bumping this
// value is a kolt release decision — see ADR 0017 for the trade-offs
// around pinning, reproducibility, and upgrade cadence.
//
// The string is an Adoptium `latest/<feature>` key. Moving to a fully
// qualified version (`21.0.5+11`) requires hitting a different
// endpoint on the Adoptium API, which is follow-up work tracked in
// ADR 0017.
const val BOOTSTRAP_JDK_VERSION: String = "21"

// Download-if-missing wrapper for the bootstrap JDK. Exits on
// failure because this path is only reached from the daemon build
// pipeline, where failing fast with a clear message is the right
// default and the caller always has a subprocess fallback option
// sitting above it.
//
// The bootstrap JDK shares a namespace with user-requested JDKs
// under `~/.kolt/toolchains/jdk/<version>/` by design: a project that
// happens to pin the same version gets the same install for free
// instead of duplicating the download.
internal fun ensureBootstrapJavaBin(paths: KoltPaths, exitCode: Int): String {
    val bins = ensureJdkBins(BOOTSTRAP_JDK_VERSION, paths, exitCode)
    val javaBin = bins.java
    if (javaBin == null) {
        eprintln("error: bootstrap jdk $BOOTSTRAP_JDK_VERSION installed but java binary not found")
        exitProcess(exitCode)
    }
    return javaBin
}

// Hand-pinned sibling of `daemonKotlinVersion` (root build.gradle.kts) and
// `KOLT_DAEMON_KOTLIN_VERSION` (kolt-compiler-daemon Main.kt). All three,
// plus the four kotlinc/BTA artifact pins in kolt-compiler-daemon's build
// scripts, must move together per ADR 0019 §1. `verifyDaemonKotlinVersion`
// asserts all seven at build time. #138 will collapse the manual sync.
//
// Committed as source (rather than generated) so the self-host path
// (`kolt.kexe build` driven by `kolt.toml`) compiles without depending on a
// Gradle task having populated `build/generated/`.
package kolt.cli

internal const val BUNDLED_DAEMON_KOTLIN_VERSION: String = "2.3.20"

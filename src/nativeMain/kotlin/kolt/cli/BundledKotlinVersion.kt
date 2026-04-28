// Hand-pinned sibling of `KOLT_DAEMON_KOTLIN_VERSION` (kolt-jvm-compiler-daemon
// Main.kt), `KOLT_NATIVE_DAEMON_KOTLIN_VERSION` (kolt-native-compiler-daemon
// Main.kt), and the kotlinc/BTA artifact pins in the daemons' Gradle scripts.
// All sites must move together per ADR 0019 §1; `DriftGuardsTest` asserts the
// sync.
//
// Per ADR 0022 §8 this constant is the *tested default baseline*, not
// the only daemon-supported version: other 2.3.x patches resolve via
// `BtaImplFetcher`. The libexec bundle exists so a fresh kolt install
// pays no Maven Central round trip on the baseline version's first build.
//
// Committed as source (rather than generated) so the self-host path
// (`kolt.kexe build` driven by `kolt.toml`) compiles without depending on a
// Gradle task having populated `build/generated/`.
package kolt.cli

internal const val BUNDLED_DAEMON_KOTLIN_VERSION: String = "2.3.20"

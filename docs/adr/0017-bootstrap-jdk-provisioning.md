---
status: accepted
date: 2026-04-14
---

# ADR 0017: Bootstrap JDK provisioning for the compiler daemon

## Summary

- The compiler daemon runs on a dedicated kolt-owned JDK, pinned as `BOOTSTRAP_JDK_VERSION` in `kolt.build.daemon.BootstrapJdk`, independent of the user's `kolt.toml [build] jdk`. Phase A pins to `"21"`. (§1)
- `ensureBootstrapJavaBin(paths)` auto-installs the pinned JDK under `~/.kolt/toolchains/jdk/<BOOTSTRAP_JDK_VERSION>/` on first use, reusing the existing `installJdkToolchain` code path. (§2)
- Any install failure surfaces as `BootstrapJdkInstallFailed(jdkInstallDir, cause)` and the build degrades to the subprocess compile path with a one-line warning, honouring ADR 0016 §5. (§3)
- `BOOTSTRAP_JDK_VERSION` is not exposed in `kolt.toml`; users cannot override it per project. (§4)
- The current pin uses Adoptium's `latest/<feature>` endpoint rather than an exact version; tightening to an exact pin is deferred follow-up. (§5)

## Context and Problem Statement

`DaemonCompilerBackend` (ADR 0016) spawns a helper JVM with `<java> <daemon launch args> --socket <path> --compiler-jars <cp>`, where the launch args come from `DaemonJarResolver` (ADR 0018 §2) — either `@<argfile>` for installed layouts or `-cp <thin>:<deps> <MainClass>` for dev binaries. Before #14 Phase A, `kolt build` only needed a JDK for the final run step; compilation shelled out to `kotlinc`, which already bundled its own JRE. The daemon changes that: the daemon is a long-lived process kolt itself spawns, and a crash or refusal to start manifests as "slow builds" with no obvious root cause.

Three ownership options were considered for the daemon JDK: the project-pinned JDK from `kolt.toml`, the host system JDK from `PATH`, or a kolt-owned dedicated slot. The project JDK fails on any project pinned below JDK 11 (required from Kotlin 1.9) or JDK 17 (required from 2.x) and cold-boots the daemon on every `jdk` bump. The system JDK provides no version control, no integrity check, and no clean way to surface "too old". Bundling a JDK in the kolt release turns a ~10 MB binary into ~100 MB and couples the release cadence to JDK security patches. Auto-provisioning a pinned dedicated slot matches the pattern kolt already uses for `kotlinc` and `konanc` (ADR 0009) and costs a one-time download whose failure is safe because the daemon is never load-bearing for correctness.

The daemon's fallback-safe design (ADR 0016 §5) makes the offline-first-run drawback bearable: an offline first run falls back to the subprocess compile path silently and retries the install on the next online build.

## Decision Drivers

- Daemon JDK must satisfy Kotlin compiler requirements regardless of what `kolt.toml [build] jdk` specifies.
- Daemon bring-up failure must not break the build — ADR 0016 §5 invariant.
- Provisioning must reuse existing toolchain code paths; no parallel mechanism.
- Users must not need a separate install step for the daemon JDK.

## Decision Outcome

Chosen option: **auto-provision a dedicated bootstrap JDK slot**, because it decouples the daemon from the project JDK, satisfies daemon JDK requirements unconditionally, and fits within the existing toolchain install infrastructure.

### §1 Dedicated bootstrap JDK slot

kolt reserves `~/.kolt/toolchains/jdk/<BOOTSTRAP_JDK_VERSION>/` for the daemon. `BOOTSTRAP_JDK_VERSION` is a compile-time constant in `kolt.build.daemon.BootstrapJdk`, pinned to `"21"` in Phase A. The slot shares the namespace with user-requested JDKs, so a project that already pins JDK 21 shares the install for free. A kolt release bumps the constant once for all users.

### §2 Auto-install on first use

The daemon bring-up path calls `ensureBootstrapJavaBin(paths)`, which auto-installs the pinned JDK the first time it is needed, using the same `installJdkToolchain` code path as explicit `kolt toolchain install`. A read-only sibling `resolveBootstrapJavaBin(paths)` is provided for diagnostic callers that want to probe state without triggering a download. Users do not need a separate `kolt toolchain install` step for the bootstrap JDK.

Auto-install landed in issue #93: `installJdkToolchain`, `installKotlincToolchain`, `installKonancToolchain`, and their `ensure*` wrappers in `ToolchainManager.kt` were refactored to return `Result<_, ToolchainError>` instead of calling `exitProcess`, enabling `ensureBootstrapJavaBin` to install synchronously and downgrade any failure to a fallback warning.

### §3 Fallback on install failure

Any failure on the install path — download, checksum, extraction — surfaces as `DaemonPreconditionError.BootstrapJdkInstallFailed(jdkInstallDir, cause)`. `resolveCompilerBackend` catches this, skips the daemon wrapper, and returns a plain `SubprocessCompilerBackend` with a one-line warning. The ADR 0016 §5 invariant — daemon is never load-bearing for correctness — is preserved.

### §4 No `kolt.toml` knob

`BOOTSTRAP_JDK_VERSION` is not configurable per project. The daemon is a kolt internal; a project knob to downgrade the daemon's JDK invites a failure mode where a project pins a JDK too old for modern Kotlin compilers. An opt-in escape hatch (e.g. `kolt.toml [daemon] jdk`) can be added in a later ADR if a concrete use case emerges.

### §5 Feature-version pin, not exact-version pin

The current pin uses Adoptium's `latest/<feature>` endpoint (`latest/21`), which tracks "whatever 21 GA is today". Two machines installing on different days can end up with different JDK 21 point releases, but both satisfy daemon requirements identically. Tightening to an exact version (Adoptium `/v3/binary/version/...`) requires a second URL pattern that the existing `installJdkToolchain` does not know about; this is deferred follow-up.

### Consequences

**Positive**
- Daemon JDK is decoupled from the project JDK; a project pinned to JDK 11 still gets the warm-compiler speedup.
- Every machine running the same kolt version gets the same JDK family; a kolt release bumps the bootstrap for everyone.
- Provisioning uses the same `installJdkToolchain` code path as `kolt toolchain install`; no parallel mechanism.
- Shared cache: a project pinning JDK 21 and the bootstrap slot share a single install under `~/.kolt/toolchains/jdk/21/`.

**Negative**
- First `kolt build` on a clean machine is gated on a ~200 MB download. An offline first run silently degrades to the subprocess compile path and retries on the next online build.
- Release notes must state which JDK the daemon runs on for each kolt release. A bootstrap JDK behind on security patches affects all users downloading after it falls behind.
- Feature-version pin means "kolt version X uses JDK 21.0.5" is not a claim kolt can make without the exact-version endpoint work.

### Confirmation

`DaemonPreconditionError.BootstrapJdkInstallFailed` wiring verified in PR3 S7 review. `resolveCompilerBackend` fallback path exercised by the PR3 review checklist.

## Alternatives considered

1. **Use the project JDK.** Rejected — forces the daemon to inherit whatever the user pinned for their app, which may be too old for the Kotlin compiler.
2. **Use the host JDK on PATH.** Rejected — no version control, no integrity check; contradicts the kolt toolchain philosophy.
3. **Bundle a JDK in the kolt release.** Rejected — turns a ~10 MB binary into ~100 MB and couples the release cadence to JDK security patches.
4. **Exact-version pin from day one.** Rejected for PR3 scope — the Adoptium exact-version endpoint requires a second URL pattern not yet wired into `installJdkToolchain`. Tracked as follow-up under the daemon epic.
5. **Auto-install from day one (PR3).** Rejected for initial PR3 scope: at that point `installJdkToolchain` / `ensureJdkBins` called `exitProcess` on any failure, which would have broken the daemon-is-never-load-bearing invariant. Landed in follow-up issue #93 after refactoring those paths to return `Result<_, ToolchainError>`.

## Related

- #14 — parent issue (Kotlin Compiler Daemon integration)
- #93 — `ToolchainManager.kt` refactor to `Result`-returning `ensure*`
- ADR 0016 — warm-daemon architecture; §5 invariant this ADR preserves
- ADR 0009 — auto-install pattern that this ADR follows
- `BootstrapJdk.kt` (`kolt.build.daemon`) — the pin constant and `ensureBootstrapJavaBin`
- `DaemonCompilerBackend.kt` — receives the bootstrap `java` binary path in its `javaBin` constructor parameter

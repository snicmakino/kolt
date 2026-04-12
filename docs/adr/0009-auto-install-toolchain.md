# ADR 0009: Auto-install managed toolchains instead of falling back to system

## Status

Accepted (2026-04-11)

## Context

By the time Issue #40 was opened, keel already had two distinct ways
to find a kotlinc or a JDK at build time:

1. **System fallback**: look up `kotlinc` on `PATH` and trust
   whatever the user happens to have installed. The version declared
   in `keel.toml` was compared against the system version for a
   warning, but the build proceeded regardless.
2. **Managed toolchains**: download a specific kotlinc/JDK version
   to `~/.keel/toolchains/` via `keel toolchain install`. If found,
   use it. If not, fall through to the system.

The managed-toolchains path had landed in PR #42 and #44 (kotlinc
and JDK respectively). It worked, but only if the user remembered
to run `keel toolchain install` before their first `keel build`.
Without that step, keel silently picked up whatever `kotlinc` was
on `PATH`, which could be a wildly different version from what
`keel.toml` said.

The effect was that `keel.toml` had a `kotlinc_version = "2.3.20"`
field that **was not actually authoritative**. The real compiler
version depended on the user's environment. A project that said
2.3.20 might compile with 2.0.0 on one machine and 2.3.20 on
another, with no reliable way to catch the mismatch at build time.
The "warning" emitted by `checkVersion()` was noise that users
learned to ignore.

This defeats the main reason to have a toolchain version in the
config in the first place. If the declared version does not
control what actually runs, what does the declaration mean?

The other ergonomic problem: the first `keel build` of a freshly
cloned project failed with a cryptic "no kotlinc on PATH" error
unless the user knew to run the toolchain-install command first.
That is the worst possible first-run experience for a build tool.

## Decision

Replace the system-fallback strategy with **auto-install**. The
version declared in `keel.toml` is the single source of truth. If
the managed toolchain for that version is not installed, keel
downloads and installs it automatically before proceeding.

In `ToolchainManager.kt`:

- Add `ensureKotlincBin(version, paths, exitCode)`: check
  `resolveKotlincPath`; if missing, call `installKotlincToolchain`;
  re-resolve; hard-fail the process if the binary is still not
  found after install.
- Add `ensureJdkBins(version, paths, exitCode)`: same pattern for
  `java` and `jar` binaries, returning a `JdkBins(java, jar)`
  record.
- Both functions follow the existing `ensureTool` pattern used for
  ktfmt and the JUnit Console Launcher: **resolve → install →
  resolve**.

In `BuildCommands.kt`:

- `doBuild`, `doCheck`, `doTest`, and related entry points call
  `ensureKotlincBin` / `ensureJdkBinsFromConfig` instead of the old
  `resolveKotlincPath` / `resolveJdkBins`.
- Remove `checkVersion()`. There is no longer anything to warn
  about — the managed toolchain version is guaranteed to match
  `keel.toml` because it was chosen by the same string.
- Remove the "warning: jdk not installed, falling back to system"
  branch and its associated path-probing code.
- Move `JdkBins` out of `BuildCommands` and into `ToolchainManager`,
  where `ensureJdkBins` lives.

`keel toolchain install` is still available as an explicit
command, but it is no longer required for a first build. Running
`keel build` on a fresh checkout will block on the toolchain
download the first time and be a no-op on subsequent runs.

## Consequences

### Positive

- **`keel.toml` is authoritative**: whatever version the user
  writes is what runs. Different machines building the same
  checkout produce compiler-identical output. CI, local, and
  collaborators' machines stop diverging on "which kotlinc was on
  PATH".
- **First-run works**: `git clone && cd && keel build` succeeds
  on a machine that has never seen keel before, assuming network
  access. There is no separate "please run X first" step.
- **No silent mismatch**: the `checkVersion` warning is gone
  because there is no mismatch to warn about. The only way to get
  a different compiler is to edit `keel.toml`, which is an
  explicit change under version control.
- **Reuses the `ensureTool` pattern**: the resolve→install→resolve
  shape is already proven by ktfmt and the JUnit Console Launcher.
  Adding kotlinc and JDK to the same pattern means there is now
  one idiom for "make sure this tool is available", and it is
  uniformly applied.
- **SHA-verified downloads**: `installJdkToolchain` and
  `installKotlincToolchain` verify downloads before installation
  (per `4b3fe4f`). Auto-install therefore inherits the supply
  chain check that explicit `toolchain install` already had.

### Negative

- **First build blocks on a download**: an unseen kotlinc + JDK
  combination can be 150 MB. On a slow connection this is a
  visible, unavoidable wait on the first `keel build` — and it
  runs without the user having typed "install". We accept this
  as the price of removing the silent-mismatch failure mode.
- **Offline-first-run is impossible**: a user who clones a
  project without network access cannot build until they can
  reach GitHub (kotlinc) and the JDK mirror. Previously a
  system-installed kotlinc could rescue them. This is a real
  regression for air-gapped environments; the workaround is to
  pre-populate `~/.keel/toolchains/` from a machine with network
  access.
- **Hard-coded download sources**: `kotlincDownloadUrl` points at
  GitHub Releases; JDK downloads point at the upstream mirror. A
  network outage or URL change breaks every first build until
  the URLs are updated or the user pre-installs the toolchain.
  Retries and mirrors are future work.
- **Exit-code / process-kill on failure**: the `ensure*` helpers
  call `exitProcess(exitCode)` when a download or resolve fails
  after install, which means they side-step the Result-based
  error handling (ADR 0001) and abort the process directly. The
  rationale is that a missing compiler is unrecoverable at the
  call site — there is nothing for `BuildCommands.kt` to fall
  back to — but it is a carve-out from the general rule. A future
  refactor may thread a `Result<String, ToolchainError>` through
  `ensure*` and let the caller decide the exit code.
- **Dead-code cleanup was its own follow-up**: the `system-fallback`
  paths left behind several functions with nobody calling them.
  PR #47 had to come through afterwards to remove the dead code
  and tighten visibility. Mixing a strategy change with a cleanup
  pass produces that kind of trailing-edge work.

### Neutral

- **`keel toolchain install` still works as an explicit command**:
  users who want to pre-install toolchains before the first build
  (for predictable CI timing, or for offline use) can do so. The
  auto-install path is idempotent, so running
  `keel toolchain install` first is a no-op at build time.
- **Toolchain downloads are cached forever**: once `~/.keel/
  toolchains/<version>/` exists, auto-install is a single
  directory-existence check. There is no periodic refresh or
  garbage collection.

## Alternatives Considered

1. **Keep the system fallback and make the version warning a hard
   error** — rejected. Users running `keel build` with the wrong
   system version would get a confusing failure instead of a
   successful build with the right compiler. The fix for "I don't
   have the right kotlinc" should not be "install it yourself";
   keel already knows how to install it.
2. **Auto-install only when the version is missing entirely, but
   keep the fallback if *some* kotlinc is present** — rejected.
   This is the worst of both: a mismatched system kotlinc would
   still silently "succeed" on machines that happened to have any
   kotlinc, and the behaviour would differ by environment. The
   entire reason for this ADR is to make the outcome depend only
   on `keel.toml`, not on `PATH`.
3. **Prompt the user interactively before downloading** — rejected.
   It would block in non-interactive contexts (CI, scripts) and
   add a failure mode where the prompt gets missed. Downloading
   silently is the right default for a build tool; the download
   is gated by the version the user already wrote into the repo,
   so consent is implicit.
4. **Ship keel with a pre-bundled kotlinc/JDK** — rejected. It
   would inflate the keel binary by 150+ MB, bake in one specific
   compiler version, and undo the "whatever `keel.toml` says"
   principle. The managed-toolchain directory is the right place
   for compiler storage.

## Related

- `src/nativeMain/kotlin/keel/tool/ToolchainManager.kt` —
  `ensureKotlincBin`, `ensureJdkBins`, `installKotlincToolchain`,
  `installJdkToolchain`
- `src/nativeMain/kotlin/keel/tool/ToolManager.kt` — the
  `ensureTool` template used for external tools
- Commit `0daa432` / PR #46 (auto-install switch-over)
- Commit `7d544bb` / PR #47 (dead-code cleanup after the switch)
- Commit `4b3fe4f` (SHA256 verification for JDK downloads)
- ADR 0001 (Result type — the `ensure*` helpers sit slightly
  outside this rule by calling `exitProcess` on unrecoverable
  failures)

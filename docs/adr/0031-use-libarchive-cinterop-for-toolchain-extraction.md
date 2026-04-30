---
status: accepted
date: 2026-04-30
---

# ADR 0031: Use libarchive cinterop for toolchain archive extraction

## Summary

- `ToolchainManager` shelled out to `unzip` and `tar` to extract kotlinc.zip, JDK.tar.gz, and konanc.tar.gz; replace all three callsites with one `extractArchive(archivePath, destDir)` backed by libarchive cinterop. (§1)
- Bind libarchive's read API and disk writer through `src/nativeInterop/cinterop/libarchive.def` and let `archive_read_support_format_all` / `archive_read_support_filter_all` cover zip, tar, and gzip in one path. (§2)
- Delegate permission, mtime, symlink restoration, and path-traversal defence to `archive_write_disk_*` with `ARCHIVE_EXTRACT_PERM | _TIME | _SECURE_SYMLINKS | _SECURE_NODOTDOT | _SECURE_NOABSOLUTEPATHS`; do not duplicate path-string checks in kolt. (§3)
- Compensate for libarchive's `SECURE_SYMLINKS` gap (it does not validate the target string of a newly created symlink entry) with a kolt-side `symlinkTargetEscapes` helper run before `archive_write_header`. (§4)
- Run extraction under `chdir(destDir)` because `_SECURE_NOABSOLUTEPATHS` rejects rewritten absolute paths; safe under kolt's sequential bootstrap install but must be revisited if concurrent toolchain installs are introduced. (§5)
- Add `libarchive-dev` (build) and `libarchive13` (runtime) as Ubuntu-package dependencies in parallel to libcurl4; macOS remains out of scope under the existing linuxX64-only target. (§6)

## Context and Problem Statement

`ToolchainManager.kt` provisions the kotlinc, JDK, and konanc toolchains by downloading an archive, verifying its SHA-256, and extracting it into `~/.kolt/toolchains/`. The extraction step shells out to `unzip` for kotlinc.zip and to `tar xzf` for the two `.tar.gz` archives. Issue #43 calls for replacing those shell-outs so that kolt bootstraps on systems that ship without `unzip` or `tar` on `PATH`.

The three callsites are structurally identical (`download → checksum → extract → mv`) and differ only in archive format, so a single extraction primitive can replace all of them. The remaining question is *how* to perform native extraction without bringing in a heavy dependency or hand-rolling format parsers.

## Decision Drivers

- Eliminate `unzip` and `tar` from kolt's runtime requirements without reimplementing zip/tar/gzip in Kotlin.
- Preserve executable bits and symbolic links faithfully so that `java`, `kotlinc`, and `konanc` are runnable straight after extraction.
- Reject path traversal (`..`, absolute paths, escaping symlinks) structurally rather than via ad-hoc string checks.
- Keep the dependency surface parallel to the existing libcurl cinterop pattern (ADR 0006) — no new build/runtime category.
- Stay on Kotlin/Native (linuxX64); no JVM fallback or coroutine runtime introduced.

## Considered Options

- **Option A — libarchive cinterop.** Bind libarchive's C API through cinterop; let format detection, disk writing, perm/symlink restoration, and security flags live in libarchive.
- **Option B — Pure Kotlin zip/tar/gzip implementation.** Hand-write parsers for ZIP (with DEFLATE), POSIX tar, and gzip in Kotlin/Native.
- **Option C — zlib cinterop plus custom zip/tar.** Delegate gzip/DEFLATE to zlib via cinterop and hand-write the zip and tar containers.

## Decision Outcome

Chosen option: **libarchive cinterop**, because it collapses three callsites onto one API, hands the security-critical decoding work to a battle-tested library, and follows the same dependency pattern (`-dev` package at build, shared library at runtime) already established by libcurl in ADR 0006.

### §1 Single `extractArchive` for three callsites

`installKotlincToolchain`, `installJdkToolchain`, and `installKonancToolchain` all execute `download → checksum → extract → mv`. Replacing each `executeCommand(listOf("unzip"|"tar", ...))` line with `extractArchive(archivePath, extractTempDir)` keeps the surrounding directory preparation and cleanup unchanged. `formatProcessError(error, "unzip"|"tar")` becomes a small `formatExtractError(ExtractError, tool, version)` helper shared across the three callsites.

### §2 cinterop binding and format coverage

`src/nativeInterop/cinterop/libarchive.def` binds `archive.h` and `archive_entry.h`. Extraction uses `archive_read_new` → `archive_read_support_format_all` + `archive_read_support_filter_all` → `archive_read_open_filename` → an `archive_read_next_header` loop, paired with `archive_write_disk_new` + `archive_read_extract2`. libarchive auto-detects format and filter, so kolt has no zip-vs-tar branching.

### §3 Disk writer flags carry the safety contract

`archive_write_disk_set_options(writer, ARCHIVE_EXTRACT_PERM | ARCHIVE_EXTRACT_TIME | ARCHIVE_EXTRACT_SECURE_SYMLINKS | ARCHIVE_EXTRACT_SECURE_NODOTDOT | ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS)` covers Requirement 2 (perm/symlink fidelity) and Requirements 4.6/4.7 (path traversal). kolt does not inspect entry path strings; rejection comes back as `ARCHIVE_FAILED` from `archive_write_header` and maps to `ExtractError.SecurityViolation`.

### §4 Symlink target validation in kolt

`ARCHIVE_EXTRACT_SECURE_SYMLINKS` blocks writes that *traverse* a pre-existing symlink during extraction; it does not inspect the target string of a *new* symlink entry. A malicious archive can therefore create a symlink whose target escapes `destDir`, and libarchive will accept it. `extractArchive` runs `symlinkTargetEscapes(entryPath, target)` before `archive_write_header` for symlink entries: absolute targets are rejected outright, and relative targets are walked depth-aware from the entry's parent directory. A negative depth means the link escapes and the whole extraction fails with `SecurityViolation`.

### §5 chdir-based extraction

`ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS` rejects entries whose pathname (after any rewriting) is absolute, which makes it impossible to rewrite entry paths to absolute `destDir`-prefixed paths. `extractArchive` therefore captures the current working directory, `chdir`s into `destDir` for the duration of extraction, and restores the cwd in a `finally` block. This is process-global and not thread-safe, but kolt installs toolchains sequentially during bootstrap, so no race exists today. Concurrent toolchain installation would need a different design (per-process worker, or a libarchive helper that accepts a directory fd).

### §6 Build and runtime dependency surface

Build hosts (developers and CI runners) need `libarchive-dev`. End-user machines need the `libarchive13` shared library at runtime. CI gains a `sudo apt-get install -y --no-install-recommends libarchive-dev` step in `unit-tests.yml`, `release.yml`, and `self-host-smoke.yml`, placed alongside the existing libcurl install. Runtime impact is parallel to libcurl4 (ADR 0006). macOS is out of scope here; the `.def` reserves `linkerOpts.osx = -larchive` but the spec does not validate it.

### Consequences

**Positive**
- `unzip` and `tar` removed from kolt's runtime requirements; bootstrap works on minimal images.
- One extraction primitive replaces three shell-outs; no per-format branching in `ToolchainManager`.
- Path traversal, absolute paths, and dot-dot escapes are rejected by libarchive flags, not by kolt-side string parsing.
- Permission bits and symlinks restored faithfully via `ARCHIVE_EXTRACT_PERM` and the disk writer.
- Same dependency model as libcurl — no new operational category for developers, CI, or end users.

**Negative**
- Build-time dependency on `libarchive-dev` on every developer machine and CI host.
- Runtime dependency on `libarchive13` on every end-user machine; a missing shared library produces a loader error at kolt startup. Adding a check to `install.sh` is deferred to a separate issue.
- macOS is not yet supported; `linkerOpts.osx` is a reservation, not a tested code path.
- `ARCHIVE_EXTRACT_SECURE_SYMLINKS` does not validate symlink target strings, so kolt carries a `symlinkTargetEscapes` helper. A future libarchive upgrade could obsolete it; revisit when the upstream behaviour changes.
- `chdir(destDir)` is process-global. Safe under sequential bootstrap, unsafe under concurrent toolchain installs; revisit before introducing parallel installs.

### Confirmation

`ArchiveExtractionTest` covers happy paths (zip and tar.gz), security paths (path traversal, absolute paths, external symlinks), and failure paths (missing/corrupt archives). `ToolchainManagerTest` continues to pass without fixture changes. End-to-end confirmation runs in `self-host-smoke.yml`, which provisions all three toolchains on a fresh runner.

## Alternatives considered

1. **Pure Kotlin zip/tar/gzip implementation.** Rejected — DEFLATE alone is non-trivial to implement correctly, tar's POSIX/PAX/GNU dialect handling is a known footgun, and perm/symlink restoration still requires POSIX syscalls. The maintenance and CVE-tracking surface would exceed the cost of one cinterop binding.
2. **zlib cinterop plus custom zip/tar.** Rejected — gzip/DEFLATE handled by zlib still leaves zip and tar parsers in kolt's tree. Strictly worse than libarchive: same number of cinterops, more kolt-owned format code, and security flags must be reimplemented.

## Related

- Issue #43 — parent issue (toolchain extraction without external tools)
- ADR 0006 — libcurl cinterop, the parallel dependency pattern this ADR follows
- ADR 0001 — Result-type error handling, governing `ExtractError`
- `src/nativeInterop/cinterop/libarchive.def` — cinterop binding
- `src/nativeMain/kotlin/kolt/infra/ArchiveExtraction.kt` — `extractArchive` implementation
- `.kiro/specs/libarchive-extraction/` — feature spec (requirements / design / research / tasks)

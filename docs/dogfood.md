# kolt Dogfooding Log

Append-only log of usability findings, surprises, and friction surfaced
while using kolt to develop kolt itself. One line per entry — link to a
GitHub issue if filed, otherwise just record the observation. The aim
is to catch "noticed and forgot" before it rots.

## Format

`YYYY-MM-DD <observation> → <outcome>`

Outcomes: `#N` (issue filed), `fixed in #N` / `landed in #N`, `wontfix`,
`watching` (observed; not yet acted on).

Issues surfaced via this channel get the `dogfood` label so they're
distinguishable from external-user reports.

## 2026-04

- 2026-04-30 `kolt test` GTEST output is ~2k lines for the full kolt suite, drowning CI logs → fixed in #301 (`--ktest_logger=SILENT` + `::group::`).
- 2026-04-30 `doRun` / `doTest` / `doBuild` hold the project lock for the full pipeline including child-process lifetime; tests calling these in-process under self-host `kolt test` deadlock at the 30s timeout → #303.
- 2026-04-30 Bootstrap kolt placed at `$GITHUB_WORKSPACE/bootstrap-kolt` with no `libexec/` sibling makes `DaemonJarResolver` miss the daemon jars; bootstrap-driven CI steps fall back to subprocess compile, costing ~30–60s per run → #302.
- 2026-04-30 `KOLT_BOOTSTRAP_TAG` self-bootstrap means a broken pinned tag blocks the next release — pin must always trail HEAD by ≥ 1 published release → policy noted in workflow headers, no issue.
- 2026-04-30 JVM daemon's 3s spawn-to-connect retry budget is too short for cold JVM startup on CI; first build after a fresh boot falls back to subprocess even though the daemon spawn itself succeeds. Surfaced via #302's canary expecting "no fallback at all" — instead the canary had to be relaxed to "daemon code path was reached" → #310.
- 2026-04-30 `kolt.lock` v3 → v4 migration on the jvm-sys-props branch (#318): `kolt deps install` printed the warning and overwrote cleanly in both daemon subprojects. Root project has no `kolt.lock` (native target, deps via cinterop / native resolver), so only two files moved. Diff is just the `"version"` line — `classpath_bundles` defaults to empty and is omitted by the serializer. Builds and tests stayed green post-migration; the Option B "deps install only" policy from design.md held.

## 2026-05

- 2026-05-01 `kolt test -- --select-class=<FQCN>` fails: JUnit Platform Console Launcher 1.11.4 rejects `--scan-class-path` together with explicit selectors (`Scanning the classpath and using explicit selectors at the same time is not supported`). `testRunCommand` always inserts `--scan-class-path`, so trailing-arg passthrough cannot be used to narrow execution to a single class or package. Surfaced during the daemon-test-via-kolt (#315) Pre-flight Gate → #323 (fixed).
- 2026-05-03 `kolt add <group:artifact>` (no version) resolves to the latest *published* version including pre-releases — `kolt add org.jetbrains.kotlinx:kotlinx-coroutines-core` selected `1.11.0-rc02`. `kolt-usage` describes this path as "latest stable", so either the resolver should filter `-rc` / `-alpha` / `-beta` / `-M*` qualifiers (and probably any non-empty Maven qualifier) or the docs need to drop the "stable" promise → fixed in #364.
- 2026-05-03 `kolt add com.example:does-not-exist:1.0.0` writes to `kolt.toml` *before* the resolver checks fetchability. Once written, every subsequent `kolt build` / `test` / `fetch` exits 3 on the same download error until the user runs `kolt remove`. Cargo validates first, then writes — same order would prevent a one-typo lockout → fixed in #363.
- 2026-05-03 Resolver download-failure message is `error: failed to download <group:artifact>` with no repository URL, HTTP status, or attempted-URLs list. Distinguishing typo vs. private-repo auth vs. transient 5xx requires re-running under a debugger or `strace` → fixed in #365.
- 2026-05-03 JDK warning `workspace.json sdk homePath left unset — could not locate 'java' on PATH (install a JDK or set [build] jdk)` fires on every `add` / `fetch` / `build` even when kolt is successfully driving its auto-fetched bootstrap JDK. The message contradicts the observed success and primes new users to install a JDK they don't actually need → fixed in #369.
- 2026-05-03 `kolt test` leaks `WARNING: Delegated to the 'execute' command. This behaviour has been deprecated and will be removed in a future release. Please use the 'execute' command directly.` from JUnit Platform Console Launcher on every run. Switching the launcher invocation from the bare form to `execute` silences it without functional change → fixed in #371.
- 2026-05-03 `kolt fmt` / `kolt fmt --check` leaks four lines of `sun.misc.Unsafe` deprecation warnings from ktfmt 0.54 under JDK 25 on every invocation. Bump ktfmt, or filter the launcher stderr → fixed in #370.
- 2026-05-03 `compiling tests...` is printed immediately after `<name> is up to date (0.0s)` during `kolt test`, which reads as contradictory — the "up to date" claim is about the main artifact, but the test artifact still needs compilation. Either suppress the main-artifact line in the test pipeline, or rephrase so the scope is explicit → fixed in #372.
- 2026-05-03 Single-line edit warm rebuild on a freshly-scaffolded hello-world JVM project measured ~1.8–2.0s across 3 back-to-back runs (daemon already hot, deps cached, no-change run is ~25ms). ADR 0016 advertises "~0.3 s warm rebuild" but a vanilla `kolt new && kolt build && edit && kolt build` does not reproduce it. Either spell out the ADR's measurement conditions (kotlinc daemon already warm in same shell, source size, hardware) or take a fresh perf pass → watching.
- 2026-05-03 Warm `kolt test` on JVM target measures ~7.5s vs ~24ms on Native (~300x gap) on a hello-world probe. Earlier diagnosis ("JUnit Console Launcher JVM cold start dominates") was wrong — `kolt test --no-daemon` produces the same ~7.5s, proving the JVM compile daemon isn't being used at all. Real cause: `doTestInner`'s JVM branch (`BuildCommands.kt:1056`) spawns kotlinc as a fresh subprocess every time, and there's no JVM analog of Native's `.kolt-test-state.json` up-to-date short-circuit. Native test pipeline already has both (daemon route + cache check) → #373.
- 2026-05-03 `kolt fmt` warm run (ktfmt jar already cached at `~/.kolt/tools/`) measures ~2.0–2.5s on a two-file project — JVM cold start + ktfmt classloading. The original cold reading of ~9.2s was misleading: it bundled the first-run `downloading ktfmt...` step. 2s is borderline-tolerable for pre-commit; not actionable on its own → wontfix-for-now (reconsider only if a bigger perf pass touches the formatter path).
- 2026-05-03 `kolt remove <group:artifact>` leaves an empty `[dependencies]` table header in `kolt.toml`. Cosmetic only, but makes the file read as "still has dependencies" at a glance and looks unfinished in PR diffs → fixed in #366.
- 2026-05-03 Unknown subcommand (`kolt foobar`) exits 1; the README exit-code table reserves 127 for "Command not found". Either the table is shell-only (kolt binary absent) and should say so, or unknown-subcommand should map to 127. Doc/impl alignment either way → fixed in #367.
- 2026-05-03 Generated `.gitignore` contains only `build/`; first-time IntelliJ users will end up checking in `.idea/` and `*.iml`. Adding the standard JetBrains + OS-junk lines (`.idea/`, `*.iml`, `.DS_Store`) to the scaffold is a one-line UX win → fixed in #368.
- 2026-05-03 `kolt build` and `kolt test` on Native targets (e.g. `linuxX64`) leak ~14 lines of JVM warnings from `kotlin-native-compiler-embeddable.jar` per invocation under JDK 23+: a `java.lang.System::load` restricted-method warning and a `sun.misc.Unsafe::objectFieldOffset` deprecation warning, each repeated twice (klib stage + kexe link stage). Same flavor as #358's ktfmt fix, but the konanc launcher (subprocess + daemon) doesn't pass `--sun-misc-unsafe-memory-access=allow` or `--enable-native-access=ALL-UNNAMED` → #374.

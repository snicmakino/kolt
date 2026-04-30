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

---
status: proposed
date: 2026-04-24
---

# ADR 0028: v1.0 release policy and RC window

## Summary

- v1.0 is gated by the `v1.0` GitHub milestone reaching zero open
  issues, then one or more `1.0.0-rc.N` pre-releases each observed for
  at least 14 calendar days with no `regression-v1`-tagged issue
  opened against them (§1).
- SemVer; the 0.x line breaks at will until `rc.1` freezes the surface
  (§2).
- Post-v1 breaking changes require a deprecation window of at least
  one minor release with a release-note call-out and a stderr warning
  on hit (§3).
- v1.0 ships `linuxX64`, `linuxArm64`, `macosX64`, `macosArm64`. All
  four are required; a linuxX64-only v1.0 is not a valid cut. Windows
  is out of scope, with no tracking issue, revisited post-v1 (§4).
- Yank policy: shipped tags are immutable and their tarballs stay
  reachable; yank status is declared in a `YANKED` manifest that
  `install.sh` consults (§5).

## Context and Problem Statement

kolt has a `v1.0` GitHub milestone that tracks the issues blocking v1.0.
CLAUDE.md records "no backward compatibility until v1.0"; past that
line, the project takes on stable-release obligations. Three informal
decisions are not written down: the gate between milestone-clear and
`v1.0`, the post-v1 compatibility commitments, and the target matrix
for v1.0. ADR 0018 §4 covers release tarball packaging but is silent
on when tarballs turn from "0.x continuous" into "release-candidate"
into "stable".

## Decision Drivers

- **Concrete cut criteria.** A maintainer should be able to decide
  "now is or is not the time to tag v1.0" from this ADR without asking.
- **User visibility.** Whatever is committed to must be visible from
  the GitHub Release page or the README, not buried in ADRs only
  contributors read.
- **Observation period that reflects real use.** An RC whose only
  exercise is CI self-host-smoke is not a signal; real users must
  install it.
- **Reversibility.** A policy bug found post-v1 (e.g. yank mechanics)
  must be fixable without a v2.0 cut.

## Decision Outcome

Chosen: the five-bullet Summary above, because each bullet closes an
informal decision that currently lives only in conversation.

### §1 Gate to v1.0

- The `v1.0` GitHub milestone must reach zero open issues. A new issue
  added to the milestone after it cleared re-opens the gate.
- After milestone-clear, the next release is `1.0.0-rc.1`, not
  `1.0.0`. The RC tag fires the release workflow (#230) and publishes
  `kolt-1.0.0-rc.1-<os>-<arch>.tar.gz` on GitHub Releases marked
  pre-release.
- Observation window: **14 calendar days measured from the RC's
  publication timestamp**. The window passes clean when no issue
  tagged `regression-v1` has been opened against that RC during those
  14 days. There is no separate "real user install confirmed" test —
  the time floor plus the absence of a `regression-v1` issue is the
  full criterion.
- **Each new RC resets the full 14 days from its own publication
  timestamp.** A bug found on day 10 of `rc.1` ships as `rc.2` and
  `rc.2` starts a fresh 14-day window. Publishing `rc.(N+1)`
  terminates `rc.N`'s clock; only the newest RC is live. No partial
  carry-over, regardless of how narrow the fix is.
- **Milestone re-opens invalidate the current RC.** If a new milestone
  issue is opened during an RC window, that RC is abandoned and no
  release is cut from it. The next publication is `rc.(N+1)` after
  the milestone is cleared again.
- **RC → v1.0 promotion.** When an RC passes clean, a new `v1.0.0`
  tag is created at the same SHA as the final RC. The RC tag stays in
  place; no tag is moved. The release workflow treats the new tag as
  a stable publication and marks it `latest`.
- At least one RC is required; no upper bound.

### §2 Version scheme

- Pre-v1: the `0.x.y` line. Breaking changes land at will, release-note
  guidance per CLAUDE.md (e.g. "run `rm -rf ~/.kolt/daemon/`"). No
  deprecation window is required in 0.x.
- RC: `1.0.0-rc.N`, `N` starts at 1. RCs are GitHub pre-releases, never
  tagged `latest`.
- Stable: `1.0.y` for bug-fix-only patches, `1.x.0` for additive
  features (and compatibility relaxation per §3).
- Pre-release tail (`1.1.0-beta.1` etc.) is reserved for post-v1
  experiments and does not fire `latest`.

### §3 Post-v1 compatibility commitments

- **kolt.toml schema.** Additive fields allowed in any minor; removed
  or semantically-changed fields are minor after a deprecation
  window of at least one released minor, during which the old shape
  continues to work and a stderr warning names the replacement.
  Never patch.
- **Lockfile format** (`kolt.lock`). A new format is written on the
  next regenerate; the old format is read for one minor then rejected
  in the following minor. Users whose lockfiles have not regenerated
  in a year see a clear "please run `kolt update`" error, not silent
  corruption.
- **CLI surface.** Same rule as kolt.toml. Adding a command or flag is
  minor; removing or re-meaning an existing command or flag is minor
  after a deprecation window, never patch.
- **On-disk layout** (`~/.kolt/` and `build/`, including library
  artifact layout per ADR 0025). Reorganisations require either a
  one-time migration on first run or a deprecation window; the
  proposing ADR picks which.
- **Wire protocol.** The daemon protocol carries its own version
  (existing `protocolVersion` in the frame header, ADR 0016). A bump
  is allowed at a minor boundary if the native client negotiates down.
  A hard break requires a major.
- **Deprecation warnings** are emitted once per kolt invocation and
  silenced by `KOLT_DEPRECATION_WARNINGS=off` (positive form; `on`
  is the default).

### §4 Platform scope at v1.0

- **All four of `linuxX64`, `linuxArm64`, `macosX64`, `macosArm64`
  must ship together.** A `linuxX64`-only v1.0 is not a valid cut;
  if #82 or #83 slip, v1.0 slips. The `v1.0` milestone already holds
  both issues, so this follows from §1 transitively, but the all-four
  rule is called out to prevent a "ship what we have, chase the rest
  as 1.1.0" shortcut.
- **Windows is out of scope** for v1.0 and has no tracking issue.
  Windows issues filed pre-v1 are closed with a pointer to this
  section. The decision is not "never", it is "not required to call
  kolt stable on Linux and macOS"; a future ADR revisits post-v1.
- Target addition post-v1.0 is a minor-release event. Removing a
  supported target is a major.

### §5 Yank policy

- **Shipped tags are immutable.** A tag's SHA is never moved. If a
  release is broken, the fix ships as a new patch.
- **Yanked tarballs stay reachable** at their original URLs so
  existing installs can still re-download. Yank is advisory, not
  destructive.
- **Yank manifest.** The repo ships a top-level `YANKED` text file.
  Each non-empty line is exactly three tab-separated fields —
  `<version>\t<replacement-version>\t<reason>`; comments and blank
  lines are not allowed (parser failure is a release-workflow error).
  Order is newest yank last. `install.sh` (see #230) reads the
  manifest from the HEAD of `main` at install time and refuses a
  yanked version unless the user sets `KOLT_ALLOW_YANKED=1`. The
  manifest is edited by the same PR that publishes the replacement;
  CI fails the release workflow if a yanked version is about to be
  tagged without its replacement landing first.

### Consequences

**Positive**
- "What does kolt commit to post-v1?" has one authoritative answer.
- The milestone is the single checklist; no parallel v1-readiness
  bookkeeping.
- Breaking changes are visible to the user via stderr warning before
  the removal release.

**Negative**
- The 14-day RC floor delays v1.0 by at least two weeks past
  milestone-clear; a bug on day 10 costs another 14 days. No workaround.
- Every deprecation becomes a two-release commitment: a runtime
  warning in `1.N.0` and the removal code in `1.(N+1).0`. Wire-
  protocol and on-disk-layout deprecations carry detection code
  (legacy `protocolVersion` branches, legacy `~/.kolt/` path probes)
  that survives the window and then gets deleted.
- Windows users get no v1 story.
- `kolt init` / `kolt new` have a chicken-and-egg question — what
  kolt version does a generated `kolt.toml` pin — that this ADR does
  not answer. Tracked as a follow-up under the `kolt new` feature
  (#28).

### Confirmation

- `v1.0` milestone on GitHub is the source of truth for the gate. No
  separate `v1.0` label on issues.
- Release workflow (#230) enforces SemVer tag format via a pre-publish
  regex; a tag not matching
  `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(rc|beta)\.[1-9]\d*)?$`
  fails the job. The `-beta.N` arm is reserved for post-v1 experiments
  per §2; pre-v1 tags never carry a pre-release suffix.
- RC publication timestamp and any `regression-v1` tagged issues
  against that RC are the only inputs to "is this RC clean?". Tracked
  by maintainer memo, not automation. An RC with a `regression-v1`
  issue opened after its publication timestamp is not clean and
  blocks promotion.
- Yank status is enforced by the `YANKED` manifest in repo HEAD;
  `install.sh` parsing it is in scope for #230.

## Alternatives considered

1. **Cut `1.0.0` the moment the milestone empties.** Rejected. Even a
   perfect milestone cannot catch integration bugs that only surface
   under real install-and-use flows; the RC window is a cheap safety
   margin against "we forgot to test X on a fresh machine" regressions.
2. **Indefinite beta (no v1.0 promise).** Rejected. Pre-v1 is the
   status quo and the no-back-compat cost is already recorded; keeping
   it indefinitely would leave kolt unrecommendable for real projects,
   which is the entire point of cutting v1.0.
3. **CalVer (`YYYY.MM.N`).** Rejected. kolt's users consume its
   breakage budget, not its release cadence; SemVer communicates the
   breakage budget, CalVer does not.
4. **Drop the 14-day RC floor and ship as soon as an RC is "looks
   fine".** Rejected. Without an explicit floor the observation
   period collapses to whoever is most eager to ship; a hard minimum
   is the cheapest guard against this.

## Related

- #230 — `install.sh` + release workflow that RCs consume (ADR 0018 §4).
- #84 — multi-platform binary distribution, unblocks §4.
- ADR 0018 §4 — release tarball packaging (this ADR sits on top of it).
- CLAUDE.md — the "no back-compat until v1.0" rule is the
  contributor-facing side; this ADR is the user-facing counterpart
  that activates when v1.0 ships.

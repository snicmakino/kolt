# Requirements Document

## Introduction

`kolt build`, `kolt add`, and `kolt fetch` currently print `resolving dependencies...` and then go silent through the POM, `.module`, and JAR downloads. On a project with many dependencies that silence can run 10s+; the user cannot tell whether kolt is fetching, stuck on a slow mirror, or hung on DNS. Retries and mirror fallbacks are also invisible until the final success or failure line.

This feature surfaces per-artifact progress on stderr during the in-Kotlin resolver's fetch loop, makes retries and mirror fallbacks visible, and keeps the emission contract readable when parallel fetch lands later. Stdout stays clean for command output.

GitHub issue: #404 (label: dogfood). Related: #355 (failure-message rendering, already shipped — unchanged here), #405 (install.sh progress, separate workstream — out of scope).

## Boundary Context

- **In scope**:
  - The in-Kotlin resolver path used by `kolt build`, `kolt add`, `kolt fetch`, and any other CLI entry that drives dependency resolution against a configured repository set.
  - Both the JVM transitive resolution path and the Kotlin/Native target resolution path.
  - Per-artifact progress lines, retry / mirror-fallback annotations, and a non-interleaving emission contract.
  - Keeping stdout clean across the commands listed above.
- **Out of scope**:
  - `install.sh` progress (#405).
  - Changes to the failure-message rendering itself (closed under #355).
  - A new `--quiet` / `--no-progress` flag.
  - Implementing parallel fetch in this feature.
- **Adjacent expectations**:
  - kolt's existing stderr writer surface (severity-tagged diagnostic helpers) is the integration point; this feature is expected to layer onto it rather than introduce a parallel writer.
  - The existing failure-message rendering remains unchanged on the error path.

## Requirements

### Requirement 1: Per-artifact progress emission

**Objective:** As a user running `kolt build`, `kolt add`, or `kolt fetch` against an uncached or partially-cached dependency set, I want to see which artifact is being fetched at each step, so I can tell whether kolt is making progress, blocked on a slow mirror, or hung.

#### Acceptance Criteria
1. When the resolver begins a network fetch for an artifact during a kolt command that drives dependency resolution, the kolt CLI shall emit a line of the form `[N/M] <group:artifact:version>` to stderr before the fetch starts, where `N` is the 1-indexed sequence number and `M` is the total number of artifacts the resolver will fetch over the network in this invocation.
2. When the resolver finds an artifact already present in the local cache and skips its network fetch, the kolt CLI shall not emit a `[N/M]` line for that artifact.
3. When the fetch loop has zero artifacts to download (fully warm cache), the kolt CLI shall emit no `[N/M]` lines.
4. The kolt CLI shall apply the same progress emission to both the JVM transitive resolution path and the Kotlin/Native target resolution path.

### Requirement 2: Retry and mirror-fallback visibility

**Objective:** As a user with multiple `[repositories]` entries, I want a failed-then-retried fetch to be visible, so I can tell "first repo missed, second succeeded" from "single slow link".

#### Acceptance Criteria
1. When a fetch attempt against one configured repository fails with HTTP 404 and the resolver retries against the next configured repository for the same artifact, the kolt CLI shall emit a follow-up annotation line on stderr surfacing the retry, and the annotation shall identify the repository being retried against.
2. When the retry annotation in 2.1 is emitted, the kolt CLI shall emit it after the artifact's primary `[N/M]` line and before the next attempt completes.
3. If a fetch attempt fails with a non-404 error and the resolver does not continue to the next repository, the kolt CLI shall not emit a retry annotation for that artifact.
4. When one or more retries succeed for a single artifact, the kolt CLI shall emit exactly one `[N/M]` line for that artifact plus its retry annotations and shall not emit a duplicate `[N/M]` line on retry.

### Requirement 3: Non-interleaving emission contract

**Objective:** As a maintainer, I want the emission contract to remain readable when parallel fetch lands later, so this feature does not have to be revisited.

#### Acceptance Criteria
1. The kolt CLI shall ensure that progress lines belonging to a single artifact (its `[N/M]` line and any retry annotations) form one contiguous block on stderr without lines from another artifact interleaved between them.
2. While the resolver runs serially, the kolt CLI shall emit progress synchronously and is not required to buffer.
3. Where a future change introduces concurrent network fetches in the resolver, the kolt CLI shall either serialize emission or buffer per-task lines and flush atomically on task completion, so that the property in 3.1 still holds.

### Requirement 4: Stream isolation — progress on stderr

**Objective:** As a user piping command output to a script or file, I want progress output not to corrupt my command output stream.

#### Acceptance Criteria
1. The kolt CLI shall write all progress lines and retry annotations introduced by this feature to stderr.
2. The kolt CLI shall write the existing `resolving dependencies...` banner to stderr instead of stdout.
3. When stdout is redirected (e.g. `kolt fetch > deps.txt`), the kolt CLI shall continue to emit progress to stderr and stdout shall contain only the command's primary output.

### Requirement 5: Sources fetch silence

**Objective:** As a user, I do not want best-effort sources downloads cluttering progress, since missing upstream sources are common and not actionable.

#### Acceptance Criteria
1. While the resolver performs the best-effort sources JAR fetch for a resolved binary, the kolt CLI shall not emit a `[N/M]` line for the sources fetch.
2. If a sources fetch fails, the kolt CLI shall remain silent for that failure, preserving the current best-effort behavior.

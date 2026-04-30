# kolt

Lightweight build tool written in Kotlin/Native (linuxX64).
Reads `kolt.toml`, compiles with `kotlinc`, and runs with `java -jar`.

## Build & Test

```bash
./gradlew build              # Build + test + binary
./gradlew linuxX64Test       # Tests only
./gradlew compileKotlinLinuxX64  # Compile only
```

## Key Rules

- **Exception throwing is prohibited** — use kotlin-result `Result<V, E>` for all error handling
- **Follow TDD** (Red -> Green -> Refactor)
- **No backward compatibility until v1.0** — kolt is pre-v1. Do not add migration shims, legacy-layout probes, or deprecation warnings for older kolt versions. Cut breaking changes cleanly; document upgrade steps in the release note and expect users to run them (e.g. `rm -rf ~/.kolt/daemon/`). Cross-version-of-external-tools compat (Kotlin compiler family, Gradle metadata) is different and still required.
- **Comments default to deletion** — keep only design invariants, "why-not" decisions, and anchored external-tool gotchas. See `/kolt-dev`.
- **Write all code, comments, documentation, and commit messages in English**
- **Profiles** — `kolt build [--release]` is Cargo-style: debug default, `--release` opt-in. JVM is declared no-op; Native routes `-opt` / `-g` and partitions output under `build/<profile>/`. Single source of truth: `kolt.build.Profile`. See ADR 0030.
- Place test files in `src/nativeTest/kotlin/kolt/<package>/XxxTest.kt` mirroring main source structure
- Annotate POSIX API usage with `@OptIn(ExperimentalForeignApi::class)` at function level

## Issue & PR writing

- Issues: problem, definition of done, scope. Skip project background and tech prerequisites — the reader already knows what kolt is.
- PRs: what changed and where the reviewer should focus (judgment calls, places to double-check). Don't restate what the diff shows. Don't repeat the issue's background.
- Write prose, not checklists. Don't mechanically fill a `## Summary` / `## Test plan` template — default `gh pr create` scaffolding is fine to discard.
- Release notes: hand-write `docs/release-notes/v<X>.md` in the bump-version PR. `release.yml` consumes it via `--notes-file`; do not rely on `--generate-notes`. PR-title autoenum is internal-voiced and not user-facing.

## Skills

- `/kolt-usage` — How to use kolt (commands, kolt.toml configuration, dependencies)
- `/kolt-dev` — Development guide (architecture, error handling policy, testing patterns)

# Agentic SDLC and Spec-Driven Development

Kiro-style Spec-Driven Development on an agentic SDLC

## Project Context

### Paths
- Steering: `.kiro/steering/`
- Specs: `.kiro/specs/`

### Steering vs Specification

**Steering** (`.kiro/steering/`) - Guide AI with project-wide rules and context
**Specs** (`.kiro/specs/`) - Formalize development process for individual features

### Active Specifications
- Check `.kiro/specs/` for active specifications
- Use `/kiro-spec-status [feature-name]` to check progress

## Development Guidelines
- Think in English, generate responses in Japanese. All Markdown content written to project files (e.g., requirements.md, design.md, tasks.md, research.md, validation reports) MUST be written in the target language configured for this specification (see spec.json.language).

## Minimal Workflow
- Phase 0 (optional): `/kiro-steering`, `/kiro-steering-custom`
- Discovery: `/kiro-discovery "idea"` — determines action path, writes brief.md + roadmap.md for multi-spec projects
- Phase 1 (Specification):
  - Single spec: `/kiro-spec-quick {feature} [--auto]` or step by step:
    - `/kiro-spec-init "description"`
    - `/kiro-spec-requirements {feature}`
    - `/kiro-validate-gap {feature}` (optional: for existing codebase)
    - `/kiro-spec-design {feature} [-y]`
    - `/kiro-validate-design {feature}` (optional: design review)
    - `/kiro-spec-tasks {feature} [-y]`
  - Multi-spec: `/kiro-spec-batch` — creates all specs from roadmap.md in parallel by dependency wave
- Phase 2 (Implementation): `/kiro-impl {feature} [tasks]`
  - Without task numbers: autonomous mode (subagent per task + independent review + final validation)
  - With task numbers: manual mode (selected tasks in main context, still reviewer-gated before completion)
  - `/kiro-validate-impl {feature}` (standalone re-validation)
- Progress check: `/kiro-spec-status {feature}` (use anytime)

## Skills Structure
Skills are located in `.claude/skills/kiro-*/SKILL.md`
- Each skill is a directory with a `SKILL.md` file
- Skills run inline with access to conversation context
- Skills may delegate parallel research to subagents for efficiency
- Additional files (templates, examples) can be added to skill directories
- `kiro-review` — task-local adversarial review protocol used by reviewer subagents
- `kiro-debug` — root-cause-first debug protocol used by debugger subagents
- `kiro-verify-completion` — fresh-evidence gate before success or completion claims
- **If there is even a 1% chance a skill applies to the current task, invoke it.** Do not skip skills because the task seems simple.

## Development Rules
- 3-phase approval workflow: Requirements → Design → Tasks → Implementation
- Human review required each phase; use `-y` only for intentional fast-track
- Keep steering current and verify alignment with `/kiro-spec-status`
- Follow the user's instructions precisely, and within that scope act autonomously: gather the necessary context and complete the requested work end-to-end in this run, asking questions only when essential information is missing or the instructions are critically ambiguous.

## Steering Configuration
- Load entire `.kiro/steering/` as project memory
- Default files: `product.md`, `tech.md`, `structure.md`
- Custom files are supported (managed via `/kiro-steering-custom`)

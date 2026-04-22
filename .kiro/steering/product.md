# Product Overview

kolt is a lightweight, no-ceremony build tool for Kotlin — a purpose-built alternative
to Gradle for individual developers and small teams who value fast startup, predictable
builds, and a simple mental model over plugin-ecosystem flexibility. It targets the
same niche for Kotlin that `go build` or `cargo build` fill for their languages.

## Core Capabilities

- **Declarative TOML config**: `kolt.toml` replaces Kotlin-DSL build scripts; no script
  compilation step, no task graph to evaluate.
- **Warm compiler daemons**: persistent JVM daemon amortizes kotlinc startup; warm
  builds are bounded by compilation time itself, not orchestration tax. Native
  compiler daemon is in progress.
- **Integrated dependency resolution**: Maven Central + custom repos, transitive
  resolution with exclusions and version intervals, lockfile with SHA-256 verification.
- **Single-binary CLI**: Kotlin/Native `kolt.kexe`; JDK required only for JVM-target
  compilation, not for invoking kolt itself.
- **Full day-to-day task coverage**: `build`, `run`, `test` (JUnit Platform), `check`,
  `fmt` (ktfmt), `add`, `deps`, `toolchain`, `daemon`, each with `--watch` where it
  makes sense.

## Target Use Cases

- JVM applications and CLI tools where Gradle's overhead dominates iteration time.
- Kotlin/Native binaries on Linux (macOS and linuxArm64 planned, not yet supported).
- Teams transitioning from Gradle who can trade plugin ecosystem for predictability.
- Contexts requiring reproducible, auditable builds via lockfile pins.

## Value Proposition

kolt's distinctness is not more features — it is fewer. Gradle's flexibility (Kotlin
DSL, plugin ecosystem, multi-module orchestration) is deliberately out of scope where
it would cost startup speed, auditability, or mental-model simplicity.

- **No startup tax**: CLI responsiveness is immediate; heavy work is delegated to the
  warm daemon.
- **Transparent config**: TOML is easier to audit and diff than DSL metaprogramming.
- **Baseline performance**: warm-build latency approaches kotlinc's own lower bound.
- **Low barrier to entry**: a simpler mental model than Gradle's task abstraction.

## Current Maturity

Pre-1.0 (current: v0.13.0). Breaking changes are expected and explicitly permitted.
kolt does not ship migration shims or legacy probes for its own earlier versions —
v1.0 is the first stability anchor.

Scope gaps tracked toward v1.0 include library packaging / `kolt publish`, macOS and
linuxArm64 targets, private repositories, multi-module projects, and mixed Kotlin/Java
sources. Development uses Kiro-style spec-driven workflow (`.kiro/specs/`) against
persistent steering (`.kiro/steering/`).

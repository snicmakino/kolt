# ADR 0015: `main` field is a Kotlin function FQN

## Status

Accepted (2026-04-13). Supersedes ADR 0012.

## Context

kolt.toml's `main` field originally held a JVM facade class name:

```toml
main = "com.example.MainKt"
```

This worked for `target = "jvm"` because the JVM runner passes the
value directly to `java -cp ... <main>`. For `target = "native"`,
konanc wants the fully-qualified name of the entry **function** (e.g.
`com.example.main`), so ADR 0012 introduced `nativeEntryPoint()` to
derive the FQN by stripping the trailing class segment and appending
`.main`.

Two problems surfaced once `target = "native"` became a first-class
path rather than an add-on:

1. **Leaky abstraction.** `MainKt` is a JVM implementation artifact —
   kotlinc synthesizes it from the file name, and it only exists to
   satisfy JVM interop. Forcing users to write a JVM class name in a
   build-tool config file that also targets native exposes something
   they did not ask for and that has no meaning outside the JVM.
2. **Target switching requires edits.** Flipping a project from
   `target = "jvm"` to `target = "native"` should not require rewriting
   `main`. With the class-name scheme, it technically does not (the
   derivation papers over it), but the field's declared meaning no
   longer matches its effect on the native path — `MainKt` is not an
   entry for konanc.

While verifying the self-host effort (#61), the inverse bug bit us:
kolt's own `fun kolt.cli.main()` lives in a named package, and
`nativeLinkCommand` intentionally omitted `-e` on the assumption that
`-Xinclude` would carry the entry point through. konanc then failed
with `could not find '/main' function` because it looked for `main` in
the root package. The assumption only holds when `fun main()` sits at
the root.

## Decision

**`main` holds a Kotlin top-level function FQN**, not a JVM class
name. The field is target-independent; kolt derives whatever each
backend needs:

| `main` value         | JVM Main-Class        | Native `-e`         |
| :---                 | :---                  | :---                |
| `main`               | `MainKt`              | `main`              |
| `com.example.main`   | `com.example.MainKt`  | `com.example.main`  |

### Parsing and validation

`parseConfig` calls `validateMainFqn` after schema decoding:

- Accepts the bare literal `"main"`, or any dotted package followed
  by `".main"`.
- Rejects any value ending in `Kt` with a migration hint that
  back-derives the function FQN: `main = "com.example.MainKt"` →
  `"Use a Kotlin function FQN instead: main = \"com.example.main\""`.
- Rejects other malformed values with a generic error.

There is no migration period. kolt is pre-1.0 (v0.9.0) and the
field's new meaning cannot coexist with the old — either the JVM path
treats `MainKt` as a class name directly (old) or it derives
`MainKt` from `main` (new). A hard error at parse time is less
confusing than a silent two-mode interpretation.

### JVM facade derivation

`jvmMainClass(main)` replaces the final `main` segment with `MainKt`:

```kotlin
fun jvmMainClass(main: String): String {
    val prefix = main.substringBeforeLast("main")
    return "${prefix}MainKt"
}
```

This is a best-effort heuristic that assumes the entry function lives
in a file named `Main.kt`, which is kolt init's template convention.
Projects that put `fun main()` in a file with a different name will
compile to `<FileName>Kt` on the JVM side, and kolt's derivation will
point at the wrong class.

### Native entry derivation

The native path forwards `config.main` to konanc verbatim via `-e`:

```kotlin
add("-e")
add(config.main)
```

No derivation is needed — the FQN in `main` is already what konanc
expects.

## Consequences

- `config.main` is finally target-agnostic: the same `kolt.toml` works
  for both `target = "jvm"` and `target = "native"` without touching
  the field.
- The `nativeEntryPoint()` / `needsNativeEntryPointWarning()` helpers
  introduced by ADR 0012 are deleted. The comment on
  `nativeLinkCommand` warning against `-e` is removed — the flag is
  now load-bearing.
- `kolt init` generates `main = "main"` instead of `main = "MainKt"`.
- Users migrating from the old scheme see a precise error at parse
  time telling them exactly what to change.

### Non-goal: `@JvmName`

Projects that override the JVM class name via `@JvmName("Foo")` or
place `fun main()` in a non-`Main.kt` file produce a JVM class whose
name `jvmMainClass()` cannot recover from `main` alone. This is
deliberately out of scope: YAGNI until someone actually needs it. The
eventual fix would be an explicit `jvm_main_class` override in
`kolt.toml`, derived on demand and unused for `target = "native"`.

## References

- ADR 0012 — the superseded scheme
- Issue #61 — self-host work, where the old `nativeLinkCommand`
  assumption broke on kolt's own `kolt.cli.main`
- `src/nativeMain/kotlin/kolt/config/Main.kt` — `jvmMainClass`,
  `validateMainFqn`
- `src/nativeMain/kotlin/kolt/build/Builder.kt` — `nativeLinkCommand`
  now emits `-e config.main`
- `src/nativeMain/kotlin/kolt/build/Runner.kt` — JVM `runCommand` uses
  `jvmMainClass(config.main)`

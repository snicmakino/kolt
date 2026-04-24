# Requirements Document

## Introduction

kolt の JVM 依存 resolver は `[dependencies]` と `[test-dependencies]`、および JVM で自動注入される `kotlin-test-junit5` を単一の closure に畳み込んで resolve する。結果、test 由来の依存 (junit-jupiter 系 / junit-platform 系 / opentest4j / apiguardian-api 等) が `.kolt.lock` と `build/<name>-runtime.classpath` (ADR 0027 §1) に main 依存として記録され、JVM `kind = "app"` の配布成果物 (`scripts/assemble-dist.sh` が `libexec/<daemon>/deps/` に copy する jar 群) が bloat する。同じ挙動は user プロジェクトが `[test-dependencies]` を宣言した時点で再現する。

本 spec は main 依存 closure と test 依存 closure を resolver 出力の段階で分離し、runtime classpath manifest と JVM 実行時 classpath は main closure のみを含み、`kolt test` の classpath は従来通り main ∪ test を含むという契約を確立する。併せて `.kolt.lock` は origin (main / test) を区別できる表現を持ち、ADR 0027 §1 の "transitive closure, post-exclusion" 文言は非test closure に限定する旨を補足する。

## Boundary Context

- **In scope**: JVM target (`kind = "app"` / `kind = "lib"`) の resolver 出力 / lockfile / runtime classpath manifest / `kolt run` / `kolt test` classpath。`kolt.build.TestDeps`、`kolt.cli.DependencyResolution`、`kolt.resolve.Lockfile` (schema)、`kolt.build.Builder.writeRuntimeClasspathManifest`、ADR 0027 §1。
- **Out of scope**: POM `<scope>test</scope>` フィルタ (`Resolution.isIncludedScope` で実装済、`TransitiveResolverTest.scopeFilteringSkipsTestAndProvided` に回帰テストあり)、`provided` / `system` / `import` scope の細分対応、Gradle Module Metadata の `usage=test` variant 区別 (別 issue)、Kotlin/Native target の依存解決 (現行で `config.dependencies` のみを解決し、本問題の影響を受けない)。
- **Adjacent expectations**: `scripts/assemble-dist.sh` は runtime manifest のエントリをそのまま `libexec/<daemon>/deps/` に copy する passive consumer であり、本 spec が manifest を非test closure に絞る副作用として tarball が縮む。ADR 0027 §1 の文言は実装に合わせて更新する。pre-v1 の "no backward compatibility" 方針 (CLAUDE.md) に従い、lockfile schema は clean break で bump し移行 shim は設けない。

## Requirements

### Requirement 1: Main / Test closure を resolver 出力で分離する

**Objective:** kolt を使う JVM 開発者として、resolver の解決結果で main 由来依存と test 由来依存が別物として扱われることを望む。runtime classpath には test 依存が載らず、test 実行時には必要な test 依存が揃うという配布成果物と開発ワークフロー双方の期待が成り立つからである。

#### Acceptance Criteria

1. When JVM target で `kolt build` / `kolt run` / `kolt test` / `kolt deps install` のいずれかを実行した時、the resolver shall `config.dependencies` を root とする main closure と `config.testDependencies` + 自動注入された `kotlin-test-junit5` を root とする test closure をそれぞれ別集合として算出する。
2. When 同一の `group:artifact` が main closure と test closure の両方に現れた時、the resolver shall main closure の version を採用し、test closure 側の同じエントリは main closure のものとして扱う (現行の "main wins on overlap" 挙動の保持)。
3. If `[dependencies]` と `[test-dependencies]` に同じ `group:artifact` が異なる version で宣言された時、then the kolt CLI shall 現行どおり警告を stderr に出力し main version を採用する。
4. When JVM target で `config.build.testSources` が空かつ `config.testDependencies` が空だった時、the resolver shall test closure を空集合として算出し (`kotlin-test-junit5` の自動注入を skip する)、main closure の結果には影響を与えない。
5. The resolver shall Kotlin/Native target では main closure のみを解決し、本分離契約の対象外として現行動作を維持する。

### Requirement 2: Lockfile が main / test origin を区別できる

**Objective:** `.kolt.lock` を参照するツールと人間として、各エントリが main 由来か test 由来かを `.kolt.lock` 単体から判別できる表現を望む。配布成果物のレビュー、ローカル監査、CI の差分チェックが一ファイルで成り立つからである。

#### Acceptance Criteria

1. When resolver が test closure のみに属するエントリを lockfile に書き出す時、the lockfile writer shall そのエントリが test 由来である事実を `.kolt.lock` 上で識別可能な形で記録する。
2. When resolver が main closure に属するエントリ (main のみ、あるいは main と test の両方で同じ version) を lockfile に書き出す時、the lockfile writer shall そのエントリが main 由来である事実を `.kolt.lock` 上で識別可能な形で記録する。
3. When `.kolt.lock` を再読込した時、the lockfile parser shall 各エントリの origin (main / test) を復元でき、再書き出ししても origin 情報が失われない。
4. If kolt が本 spec 以前の schema version で書かれた `.kolt.lock` を読んだ時、then the kolt CLI shall clean break 方針 (pre-v1) に従って unsupported version を stderr に報告し、ユーザーに `kolt deps install` による再生成を促す。
5. The lockfile serializer shall 全エントリを `group:artifact` の辞書順で出力し、origin 情報は per-entry で保持する (main / test を別セクションに分割しない)。

### Requirement 3: Runtime classpath manifest は main closure のみを含む

**Objective:** JVM `kind = "app"` 成果物を配布する開発者として、`build/<name>-runtime.classpath` が実行時に必要な main closure だけを列挙することを望む。`scripts/assemble-dist.sh` が tarball に入れる jar から test 専用依存が除外され、配布物が意図通りの構成になるからである。

#### Acceptance Criteria

1. When JVM target `kind = "app"` の build が成功した時、the Builder shall `build/<name>-runtime.classpath` に main closure のエントリのみを ADR 0027 §1 のルール (ファイル名の辞書順、同名時は GAV で tiebreak、絶対パス、UTF-8 LF、末尾改行なし) で書き出す。
2. When `kotlin-test-junit5` や `[test-dependencies]` 由来のエントリが test closure にのみ存在した時、the Builder shall それらを `build/<name>-runtime.classpath` に含めない。
3. The Builder shall JVM `kind = "lib"` / Kotlin/Native target では現行通り `build/<name>-runtime.classpath` を emit しない。
4. The ADR 0027 §1 shall "transitive closure, post-exclusion" の定義を main closure (非test closure) に限定する旨の補足文言を含む。

### Requirement 4: `kolt test` の classpath は main ∪ test を維持する

**Objective:** `kolt test` を使う開発者として、test コンパイルと test 実行に必要な依存 (`kotlin-test-junit5` + `[test-dependencies]` + その transitive + main 依存) が従来どおりすべて揃うことを望む。既存の test ワークフローが本分離によって退行しない保証が要るからである。

#### Acceptance Criteria

1. When JVM target で `kolt test` を実行した時、the kolt CLI shall main closure と test closure の和集合を test コンパイル / 実行用 classpath に渡す。
2. When 同じ `group:artifact` が main と test の両方に解決された時、the kolt CLI shall main closure の version で一度だけ classpath に載せ、重複エントリを生まない。
3. When `config.build.testSources` が空だった時、the kolt CLI shall 現行挙動どおり `kolt test` を no-op として扱い、test closure の解決・配置を skip する。

### Requirement 5: `kolt run` / JVM in-process 実行は main closure のみを使う

**Objective:** `kolt run` や JVM `kind = "app"` を直接実行する立場として、development 時起動と配布成果物起動が同じ main closure だけで成り立つことを望む。両者で挙動が一致し、test 依存が production 実行経路に紛れ込まないからである。

#### Acceptance Criteria

1. When JVM target `kind = "app"` で `kolt run` を実行した時、the kolt CLI shall main closure のみを `java` サブプロセスの classpath として渡し、test 由来依存を含めない。
2. The kolt CLI shall `kolt run` 時に `build/<name>-runtime.classpath` manifest を read-back せず、ADR 0027 §5 の asymmetry を保持する。

### Requirement 6: 配布成果物と daemon self-host の再検証

**Objective:** kolt 自身の保守担当として、`kolt-jvm-compiler-daemon` と `kolt-native-compiler-daemon` が本分離契約の下で正しく起動する配布成果物を生成することを望む。v1.0 milestone で self-host を崩さないことが前提だからである。

#### Acceptance Criteria

1. When `kolt-jvm-compiler-daemon` に対して `kolt build` が成功した時、the daemon runtime classpath manifest shall `org.junit.jupiter:*` / `org.junit.platform:*` / `org.opentest4j:opentest4j` / `org.apiguardian:apiguardian-api` / `org.jetbrains.kotlin:kotlin-test` / `org.jetbrains.kotlin:kotlin-test-junit5` のいずれをも含まない。
2. When `scripts/assemble-dist.sh` が本契約下で生成された manifest を読んだ時、the assemble-dist.sh shall `libexec/kolt-jvm-compiler-daemon/deps/` に test 由来の jar を copy しない。
3. When `kolt-jvm-compiler-daemon/kolt.lock` と `spike/daemon-self-host-smoke/kolt.lock` が本契約下で再生成された時、the lockfiles shall 新 schema で書き出され、tracked 状態を維持する。

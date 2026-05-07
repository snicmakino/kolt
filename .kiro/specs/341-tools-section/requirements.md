# Requirements Document

## Project Description (Input)

Issue #341: Add `[tools]` section for project-pinned executable jars

ktlint や detekt のような runnable jar ツールは、現状ユーザーが手作業で取得している。`dependencies` に列挙するとアプリの classpath を汚染し、build 出力にも漏れる。グローバルインストールではプロジェクトごとの pin ができず、チームメンバー間で版が drift する。「プロジェクトに紐付くがプロジェクトの依存ではない」というカテゴリが欠けている — npm の `devDependencies` が同じ形を解いている。

`kolt.toml` に `[tools]` テーブルを追加する。各エントリは alias と Maven coordinates だけ。string 短縮形なし、組み込みの name → coords レジストリなし。kolt は jar をアプリ classpath に**載らない** cache に解決して、`java -jar` で起動する。引数は npx スタイルでそのまま透過する。

ガードレール（non-goals）: エントリ間の参照は不可（`depends-on` なし）。ツールごとの kolt subcommand は作らない（`kolt lint` のような）。エントリ内でのコマンドライン構築は不可（`args = [...]` なし）。`[tools]` は jar 取得 + 起動のみで、orchestration はしない。

### Done when

- `kolt.toml` が `[tools]` を受け付ける（`alias = { coords = "group:artifact:version[:classifier]" }`）
- `kolt-<surface> <alias> <args...>` が解決・キャッシュ・jar の Main-Class を `java -jar` で起動する
- 解決された coordinates は lockfile に pin される（ファイル位置は ADR で TBD）。チーム全員が同じ jar を実行する
- v1 では Main-Class manifest を持つ runnable jar のみサポート。それ以外の形は resolve / launch 時に loud に失敗する
- cache hit はネットワーク不要、`kolt build` の offline-when-possible 挙動に揃える

### Out of scope

- `[hooks]` 連携（`[hooks]` が landing したら follow-up #119）
- distribution-zip + launcher-script ツール（kotlinc 系）
- plugin classpath 組み立て（detekt plugin、ktlint ruleset）
- 短縮構文（`ktlint = "1.3.1"`）と組み込み name → coords マッピング
- グローバルインストール（`~/.kolt/bin/`、別議論）

### Open questions to resolve in design.md (= ADR-equivalent)

これら 8 項目は ADR 0028 §3 の凍結面を同時に拡張するため、design.md でまとめて決め切る:

- **CLI surface.** `kolt-x ktlint check`（separate binary）vs `kolt tool run ktlint check`（subcommand）。どちらも ADR 0028 §3 の凍結対象 — ship でゲートが閉じる
- **Coordinate shape and transitives.** `coords` 文字列に classifier を含めるか（`group:artifact:version:classifier` — `ktlint-cli:1.3.1:all` に必要）、それとも `classifier` を別フィールドにするか。fat-jar tool は transitive 不要のため、resolve-and-skip vs main-classifier-only fetch も同じ判断に乗る
- **Lockfile placement.** 既存 `kolt.lock` vs 別ファイル
- **Alias grammar.** regex を up-front で pin する。ADR 0028 §3 の additive-only schema 制約により、後から narrow するのは痛い
- **Launch JDK.** デフォルトは bootstrap JDK（`~/.kolt/toolchains/jdk/<version>/`、ADR 0017 §1）か。ツールが project の Kotlin JDK target と独立に JDK を要求できるかも open
- **Cache layout.** `~/.kolt/tools/<alias>/<version>/` か coords-hashed か。ship で ADR 0028 §3 凍結に乗る
- **Failure exit codes.** resolve failure / Main-Class missing / checksum mismatch を別々の exit code にするか — `install.sh` の粒度に揃える
- **§3 update.** ADR draft で §3 を拡張し、新 toml section / CLI surface / cache layout を凍結面に含める

### Implementation strategy

ユーザーと 2026-05-08 に合意したハイブリッド方針: 単一 SDD で 8 項目の設計判断を ADR 相当の design.md に集約 → tasks.md で 3〜4 phase に自然に分割（schema parse + alias validation / resolve + cache / launch + lockfile / failure exit code surface）。

### Milestone

現状 v1.0 milestone（Issue 末尾「v1 inclusion is open. v1.1 is acceptable as fallback」）。最終配置は design.md の scope freeze で決定する。

### Related

- Issue #119 — `[hooks]` 議論（将来の連携対象）
- Issue #28 — `kolt init` / `new`（closed、共有 CLI surface 判断の前例）
- ADR 0017 — bootstrap JDK provisioning（§1 を launch JDK default に流用）
- ADR 0025 — library artifact layout（cache layout の前例）
- ADR 0028 — v1 release policy §3（本機能が凍結面を拡張する）

## Introduction

`[tools]` セクションは「プロジェクトに紐付くが、プロジェクト本体の依存ではない」ツール (ktlint / detekt のような runnable jar) を、 alias + Maven coordinates で kolt.toml に宣言できるようにする機能である。 kolt はそれらを **アプリの classpath とは分離された** cache に解決し、 lockfile で pin し、 ユーザーの呼び出し時に `java -jar` で起動する。 引数は変更せずそのまま透過し、 ツールの終了コードもそのまま返す。 npm の `devDependencies` + `npx` に相当するカテゴリを kolt に持ち込むことが狙いで、 `[dependencies]` への手作業列挙、 グローバルインストールの版 drift、 手作業 jar 取得をすべて置き換える。

本機能は jar 取得・キャッシュ・起動だけを担い、 orchestration（ツール間依存、 ツール固有の kolt subcommand、 エントリ内でのコマンドライン構築）は明示的に持たない。 `[hooks]` (#119) との連携は、 `[hooks]` が landing した後の follow-up で扱う。

## Boundary Context

- **In scope**: `[tools]` テーブルの parse と validation、 alias による起動、 引数の verbatim 透過、 解決済 jar の cache、 lockfile への pin、 起動失敗の cause-distinguishable な surface、 v1 で Main-Class manifest を持つ runnable jar のみサポートすること
- **Out of scope**: `[hooks]` (#119) との連携、 distribution-zip + launcher-script 形式のツール、 ツールの plugin classpath 組み立て、 `ktlint = "1.3.1"` のような短縮構文と name→coords レジストリ、 グローバルインストール (`~/.kolt/bin/`)、 ツール間 orchestration 機能 (depends-on, ツール固有 kolt subcommand, エントリ内 args 構築)
- **Adjacent expectations**: Maven coordinates の解決には kolt の既存依存解決経路を再利用すること、 ツール起動 JDK は kolt の JDK provisioning (ADR 0017 §1) と同じ管理対象であること、 lockfile 形式は kolt の既存 lockfile (kolt.lock) と互換に整合させること。 これらの具体的な束ね方 (CLI surface, coords 文字列形状, lockfile 配置, alias regex, JDK 既定, cache layout, exit code 粒度) は design.md で決定し、 ADR 0028 §3 の凍結面に組み込む

## Requirements

### Requirement 1: `[tools]` テーブルの宣言と parse

**Objective:** kolt ユーザーとして、 プロジェクト固有の runnable jar ツールを kolt.toml に宣言したい。 そうすればチーム全員が同じ kolt.toml + lockfile から同一のツールを起動できる。

#### Acceptance Criteria

1. When `kolt.toml` が `[tools.<alias>]` 形式のエントリを含むとき、 kolt shall そのエントリを (alias, coords) のペアとして parse して内部設定に取り込む
2. When エントリの `coords` 値が `group:artifact:version` または `group:artifact:version:classifier` の形であるとき、 kolt shall その coordinates を Maven coordinates として受理する
3. If エントリの `alias` がプロジェクトで定義する alias 文法に違反しているとき、 kolt shall 違反した alias と期待される文法を提示して `kolt.toml` の load を失敗させる
4. If エントリの `coords` 値が group / artifact / version を欠くなど未定義の形であるとき、 kolt shall 該当 alias と coords の値を提示して `kolt.toml` の load を失敗させる
5. If 同一の `alias` が `[tools]` 配下に複数回宣言されているとき、 kolt shall 衝突した alias を提示して `kolt.toml` の load を失敗させる

### Requirement 2: alias による起動と引数の verbatim 透過

**Objective:** kolt ユーザーとして、 宣言したツールを alias でそのまま呼びたい。 そうすれば npx 相当の体験で、 引数を覚え直したりラッパーシェルを書く必要がない。

#### Acceptance Criteria

1. When ユーザーが宣言済の `<alias>` を引数 `<args...>` 付きで呼び出したとき、 kolt shall その alias に紐付く jar を起動して `<args...>` を変更せず透過する
2. The kolt shall 起動したツールの終了 exit code をそのまま呼び出し側に返す
3. If ユーザーが `[tools]` に宣言されていない alias を呼び出したとき、 kolt shall 未知 alias であることと、 既知 alias の一覧を表示して non-zero で終了する
4. The kolt shall ツール起動時にプロジェクトの application classpath、 `[dependencies]`、 build 出力に対してそのツールの依存性を混入させない

### Requirement 3: 解決と cache（offline-when-possible）

**Objective:** kolt ユーザーとして、 同じツールを 2 回目以降はネットワークなしで起動したい。 そうすれば CI / 機内 / オフライン開発でも安定して走る。

#### Acceptance Criteria

1. When ある alias が初めて呼び出されて cache 上に該当 jar が存在しないとき、 kolt shall 宣言された coordinates から jar を取得して cache に格納してから起動する
2. When ある alias が呼び出されて cache 上に該当 jar が存在するとき、 kolt shall ネットワークアクセスなしで cache から jar を起動する
3. If cache 上の jar が integrity check (期待 checksum との一致) を満たさないとき、 kolt shall 該当 alias と検出した不整合を提示して non-zero で終了し、 自動で再取得は行わない
4. If リモートから jar を取得できず cache 上にも該当 jar がないとき、 kolt shall 解決失敗の原因 (取得対象 coordinates と試行先) を提示して non-zero で終了する

### Requirement 4: lockfile による pin とチーム間整合性

**Objective:** kolt ユーザーとして、 ツールの版もアプリ依存と同じ「kolt.toml + lockfile」契約で固定したい。 そうすればチームメンバー間で版が drift しない。

#### Acceptance Criteria

1. When ある alias の coordinates が初めて解決されたとき、 kolt shall 解決後の具体 coordinates を kolt の lockfile に記録する
2. When `[tools]` の宣言と lockfile の pin の両方が存在するとき、 kolt shall lockfile の pin を優先してその通りに jar を起動する
3. If `[tools]` の coordinates と lockfile の pin が矛盾していて、 ユーザーが明示的に lockfile 更新を指示していないとき、 kolt shall 矛盾箇所と取るべき手順 (例: lockfile 更新コマンド) を提示して non-zero で終了する
4. The kolt shall lockfile の pin に再現に必要な情報 (具体 version と integrity 識別子) を含める

### Requirement 5: サポートされる jar 形状

**Objective:** kolt ユーザーとして、 サポート対象 jar とそうでない jar の境界を明確にしたい。 そうすれば想定外の形を渡したときに沈黙で誤動作せず、 直すべき箇所がすぐ分かる。

#### Acceptance Criteria

1. When `[tools]` で参照された jar が `MANIFEST.MF` に有効な `Main-Class` を持つとき、 kolt shall その `Main-Class` を `java -jar` 経路で起動する
2. If `[tools]` で参照された jar が `Main-Class` を持たない、 または `Main-Class` が解決できないとき、 kolt shall 該当 alias と原因を提示して non-zero で終了する
3. If `[tools]` で参照された artifact が runnable jar 以外の形 (distribution zip、 launcher script bundle 等) であるとき、 kolt shall サポート対象外であることを提示して non-zero で終了する
4. The kolt shall ツールの起動失敗を「resolve 失敗」「Main-Class なし / 解決不可」「integrity check 失敗」のように cause-distinguishable な surface (exit code もしくは構造化メッセージ) で返す

### Requirement 6: ツール実行用 JDK の前提

**Objective:** kolt ユーザーとして、 ツール起動に使う JDK の出処を予測可能にしたい。 そうすればホスト環境に依存しないチーム共通の挙動が得られる。

#### Acceptance Criteria

1. When 同じ kolt.toml + lockfile + 同じ kolt version でツールを起動するとき、 kolt shall ツール jar の起動に同じ JDK 環境を決定論的に選択し、 host の PATH 偶然や非宣言の環境変数差に依存しない
2. If 起動に必要な JDK が利用できないとき、 kolt shall 期待された JDK の出所と取るべき手順を提示して non-zero で終了する
3. The kolt shall ツール起動に実際に使った JDK の出所を、 ユーザーが事後に確認できる形 (ログ / verbose 出力 / 失敗時メッセージのいずれか) で提供する

### Requirement 7: orchestration を持たない境界

**Objective:** kolt メンテナーとして、 `[tools]` の責務を「jar 取得 + 起動」に厳しく限定したい。 そうすれば将来 `[hooks]` (#119) や別のスコープ追加が来たときに、 既存の `[tools]` 契約と競合しない。

#### Acceptance Criteria

1. If `[tools]` エントリにツール間の依存関係を表すフィールド (例: `depends-on`) が含まれているとき、 kolt shall そのフィールドが `[tools]` の対象外であることを提示して `kolt.toml` の load を失敗させる
2. If `[tools]` エントリにエントリ内コマンドライン構築を表すフィールド (例: `args = [...]`) が含まれているとき、 kolt shall そのフィールドが `[tools]` の対象外であることを提示して `kolt.toml` の load を失敗させる
3. The kolt shall `[tools]` のエントリに対してツール固有の kolt subcommand (例: `kolt lint`) を自動生成しない
4. The kolt shall `[tools]` 経由のツール起動を、 `kolt build` / `kolt test` / その他の kolt lifecycle event の前後に自動で挿入しない (連携が必要なら別機能 / 別 toml セクションで導入する)

# Gap Analysis — installer

## Analysis Summary

- **既存資産は薄い** (release.yml / install.sh / YANKED いずれも未存在) が、`scripts/assemble-dist.sh` (PR #228, 256 lines) と `.github/workflows/self-host-smoke.yml` の `self-host-post` job が「tarball を生成して install レイアウトに展開する」流れを既に CI 上で動かしており、本 spec はこの基盤への薄い拡張に近い。
- **実装の重心は `install.sh` (新規 Bash script)**。release.yml はテンプレ的な構成、`assemble-dist.sh` への変更は `.sha256` 生成 1 行、YANKED は空ファイル commit のみ。本 spec の総 LoC のうち install.sh が 60〜70% を占める想定。
- **`self-host-post` job の inline sed 部 (lines 184-188) を install.sh に置き換える**ことで、PR-time smoke が「install.sh の本物の経路」を毎 PR で exercise できるようになる。これは spec が明示的に求めているわけではないが、実装上の自然な統合点。
- **GitHub Releases API からの version 列挙** (Req 3 AC 1) と **version <=> tag 整合 gate** (Req 1 AC 6) が、design phase で詰めるべき主要な未確定事項。
- **Effort: M (3-7 days)**、**Risk: Medium** (install.sh の edge case 多数、tag-time smoke は first tag まで実地検証不可)。

## Document Status

Gap analysis を `.kiro/specs/installer/research.md` に新規 write。既存コード (`scripts/assemble-dist.sh`、`.github/workflows/*.yml`、README、`kolt.toml`) と ADR 0018/0028 の整合性を踏まえて、3 つの実装オプションと推奨を記載。

## 1. Current State Investigation

### 既存の release / distribution 関連資産

| Path | 状態 | 役割 |
|---|---|---|
| `scripts/assemble-dist.sh` | 存在 (#228 で完成、256 lines) | tarball stitcher。3 × `kolt build --release` + Maven Central から BTA-impl 取得 + `bin/` + `libexec/` レイアウト構築 + `tar czf`。`@KOLT_LIBEXEC@` placeholder を argfile に書き込む。`kolt.toml` の `version =` を読む。 |
| `.github/workflows/self-host-smoke.yml` | 存在 (200 lines) | `self-host` job (Gradle bootstrap → kolt.kexe) と `self-host-post` job (3 × kolt build → assemble-dist.sh → tarball extract → inline sed → fixture smoke) の 2 段構成。**`self-host-post` lines 184-188 が「install 後の sed 置換」を inline で実装しており、これが install.sh の責務に対応する**。 |
| `.github/workflows/unit-tests.yml` | 存在 (50+ lines) | linuxX64Test の standard runner 構成 (ubuntu-latest + JDK 25 + libcurl + caches)。新 workflow の雛形として再利用可能。 |
| `README.md` lines 14-30 | 存在 | 「Build from source: git clone → ./gradlew build → cp kexe to PATH」を案内。Req 8 で curl|sh に書き換え対象。 |
| `kolt.toml` の `version = "0.15.0"` | 存在 | tarball 命名と `VERSION` ファイルの単一情報源。release ごとに手動 bump (PR #271 「Bump version to 0.15.0」が precedent)。 |
| `.github/workflows/release.yml` | **未存在** | 本 spec で新規作成 |
| `install.sh` (repo root) | **未存在** | 本 spec で新規作成 |
| `YANKED` (repo root) | **未存在** | 本 spec で空ファイル commit |
| `assemble-dist.sh` の `.sha256` 生成 | **未存在** | 本 spec で 1〜2 行追加 |
| 過去 release への asset attach | **未存在** | v0.15.0 含む全 release が assets 0 件。本 spec では retroactive attach せず、初版 installer が最新 tag (v0.16.0 想定) から有効になる |

### 関連 ADR の制約

- **ADR 0018 §1**: tarball layout (`bin/` + `libexec/{kolt-jvm-compiler-daemon, kolt-native-compiler-daemon, kolt-bta-impl, classpath}/`) は既に確定済み。assemble-dist.sh が完全準拠している。
- **ADR 0018 §4**: install.sh は `~/.local/share/kolt/<version>/` 展開 + `~/.local/bin/kolt` symlink を行う。「First release covers fresh install only。Upgrade / uninstall / shell-rc PATH bootstrap は follow-up」と明記。
- **ADR 0018 Confirmation**: 「`install.sh` は `curl | sh` CI job で release ごとに 1 度 exercise される」が confirmation の必要条件。
- **ADR 0028 §1**: tag SemVer regex `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(rc|beta)\.[1-9]\d*)?$` 確定。release workflow の pre-publish gate でこれに従う。
- **ADR 0028 §5**: YANKED manifest format は tab-separated 3 フィールド `<version>\t<replacement>\t<reason>`、コメント・空行不可、parser failure は release-workflow error。install.sh が repo HEAD `main` から fetch する前提。

### 既存の規約・パターン

- **CI workflow 構造**: ubuntu-latest + setup-java@v4 (JDK 25 temurin) + libcurl4-openssl-dev + 3 種類のキャッシュ (`~/.gradle`, `~/.konan`, `~/.kolt`)。release.yml もこの雛形に乗せる。
- **`kolt.toml` の version**: 手動で release PR ごとに bump (#271 等が precedent)。release.yml は「pushed tag の version-prefix 除去 == kolt.toml version」を assert する gate を持つことで drift を防ぐ。
- **`gh release create`**: 既存 release は手動で作成 (assets 0 件)。release.yml で `gh release create --target $SHA <tag> <assets>` を使う想定。`GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` + `permissions: contents: write` が必要。
- **assemble-dist.sh の version 抽出**: `grep -E '^version = "' kolt.toml` の単一ライン抽出。release.yml で同等のロジックで tag check も可能。

## 2. Requirements Feasibility Analysis

### Requirement-to-Asset Map

| Requirement | 既存資産 | Gap | タグ |
|---|---|---|---|
| Req 1: tag-triggered release.yml | なし | `.github/workflows/release.yml` 全部新規 | Missing |
| Req 1 AC 1-3: SemVer gate + asset upload | unit-tests.yml の boilerplate を雛形化可能 | `gh release create` 使用法、tag-from-ref 抽出、asset upload の確認 | Missing |
| Req 1 AC 4: YANKED gate (release-side) | ADR 0028 §5 で format pin | YANKED parser を release.yml の Bash step として実装 | Missing |
| Req 1 AC 6: tag <=> kolt.toml version 整合 | `kolt.toml` の version = 行は単一情報源 | gate logic 追加 (release.yml 内 1 step) | Missing |
| Req 2 全体: install.sh fresh install | self-host-smoke の inline sed が部分プロトタイプ | install.sh 新規 (~200-300 lines、deno/bun の install.sh が参考) | Missing |
| Req 2 AC 2: @KOLT_LIBEXEC@ sed | self-host-smoke.yml lines 184-188 が同等処理 | install.sh 内に移植 | Constraint (既存挙動と一致必須) |
| Req 2 AC 5: kolt --version 通る | self-host-post の `kolt build` が後段で assert | smoke job の assertion 追加 | Constraint |
| Req 3 AC 1: SemVer 順最新 non-yanked | なし | GitHub Releases API enumerate + SemVer 比較 + YANKED 突合 | Unknown (API 詳細は design phase で詰める) |
| Req 3 AC 3-4: KOLT_ALLOW_YANKED override | なし | install.sh 内 env var 処理 | Missing |
| Req 4: platform 検出 | なし | `uname -s`/`uname -m` case 文 | Missing |
| Req 5 AC 1: .sha256 生成 | assemble-dist.sh 末尾に追加点あり | `sha256sum tarball > tarball.sha256` 1〜2 行 | Missing (簡易) |
| Req 5 AC 2-4: install.sh 検証 | なし | install.sh 内で `sha256sum -c` 相当 | Missing |
| Req 6: YANKED 空ファイル | なし | `touch YANKED && git add YANKED` | Missing (簡易) |
| Req 6 AC 3-7: format parser 整合 | ADR 0028 §5 が format pin | release.yml と install.sh の双方で同 parser ロジック | Constraint (drift 不可) |
| Req 7 AC 1: PR-time install smoke | self-host-smoke.yml が近い基盤 | install.sh 経路の挿入 + 合成 YANKED テスト追加 | Missing |
| Req 7 AC 3: tag-time real curl|sh smoke | なし | release.yml の最終 step | Missing |
| Req 8: README install command | README lines 14-30 | curl|sh + 環境変数表記に書き換え | Missing |

**タグ凡例**: Missing = 完全新規、Unknown = design で要詳細決定、Constraint = 既存挙動・規約との整合必須。

### Non-functional 観点

- **Reliability**: install.sh は単発実行で失敗時はクリーンに非ゼロ終了する責務。中途半端な install state を残さない (展開前に SHA-256 検証、symlink 作成は最後)。
- **Security**: SHA-256 verification は MITM / CDN 改竄に対する第一防衛線。cosign / GPG は将来 issue。
- **Idempotency**: 同一 version の install.sh 再実行は no-op もしくは re-overwrite で成功する想定。upgrade (異なる version) は symlink 上書きで natural に動く。
- **Performance**: install.sh は 1 回のネットワーク + 数秒の展開で完了。バックグラウンド処理は不要。

## 3. Implementation Approach Options

### Option A: 既存 self-host-smoke.yml を release+smoke 兼用に拡張

self-host-smoke.yml に tag trigger と asset upload step を加え、PR / push / tag の 3 系統を 1 ファイルで扱う。

**Trade-offs**
- ✅ workflow ファイル 1 個で済む。重複が無い
- ❌ 「smoke (PR)」「smoke (push)」「release (tag)」が同居して責務が混雑する
- ❌ tag 時の経路が PR 時の trigger 条件と異なる箇所が多く、`if:` 分岐が増えて読みにくい

### Option B: release.yml 新規 + self-host-smoke.yml に install.sh 経路を組み込む（推奨）

- 新 `.github/workflows/release.yml` を tag-trigger 専用に作る (gate → assemble-dist → upload → tag-time smoke)。
- 既存 `self-host-smoke.yml` の `self-host-post` job の inline sed (lines 184-188) を `install.sh` 呼び出しに置き換え、PR-time install smoke を兼ねる。同 job 内に「合成 YANKED で yank refuse path をテスト」step を追加。
- assemble-dist.sh に `.sha256` 生成 1 行を追加。

**Trade-offs**
- ✅ 「release」と「smoke」の責務が明確に分かれる
- ✅ install.sh の本物の経路が PR ごとに exercise される (release が出ていない HEAD でも)
- ✅ 既存 `self-host-post` job の知見 (キャッシュ戦略、JDK 25 setup) を再利用できる
- ❌ workflow ファイル数が増える (1 → 2)
- ❌ self-host-smoke.yml と install.sh の責務重複が生じる場合がある (例: 両方が `@KOLT_LIBEXEC@` 置換ロジックを持つ — install.sh が source-of-truth で、smoke はそれを呼ぶ形になるため最終的に重複は無い)

### Option C: release.yml 完全独立 + 新 install-smoke.yml + self-host-smoke.yml は無変更

- 3 つの workflow ファイル: release.yml (tag-only), install-smoke.yml (PR-only, install.sh 経路 + YANKED refuse), self-host-smoke.yml (現状維持)。
- install.sh は完全独立、self-host-post の inline sed はそのまま残す。

**Trade-offs**
- ✅ 既存 self-host-smoke.yml は無変更で安心
- ❌ self-host-post の inline sed と install.sh で「同じ責務の 2 実装」が永続化する。drift の温床
- ❌ workflow ファイル数が増える (1 → 3)
- ❌ install-smoke.yml が self-host-post の caching と重複する (`~/.kolt`, `~/.gradle` を再設定)

### 推奨: Option B

理由:
1. install.sh が source-of-truth として複数 workflow から呼ばれる構造は健全
2. self-host-post の inline sed を install.sh 呼び出しに置き換える diff は機械的で小さい (lines 184-188 を 1 行に置換)
3. CI minutes / cache の節約 (job を増やさない)
4. release と smoke の責務分離は、release.yml は新規・self-host-smoke.yml は拡張、と書き方を変えるだけで実現可能

## 4. Effort & Risk

- **Effort: M (3-7 days)**
  - install.sh 新規実装 + 各 edge case の手動テスト: 2 日
  - release.yml 新規 + gates + `gh release create` + tag-time smoke job: 1 日
  - assemble-dist.sh への `.sha256` 追加 + self-host-smoke.yml の sed 部 install.sh 化 + YANKED refuse smoke step: 0.5 日
  - YANKED 空ファイル + README 書き換え: 0.5 日
  - PR cycle で発覚する整合バグ修正 (test mode の追加、SemVer parsing edge case 等): 1〜2 日
  - **合計: 約 5 日 (Medium)**

- **Risk: Medium**
  - install.sh は edge case が多い (platform detection、JSON 解析、SHA 検証、idempotency)。Bash の portable 性 (POSIX sh vs bash) を確認しないと curl|sh で死ぬ
  - GitHub Releases API の rate limit (60/h unauth) は普通の使い方なら問題ないが、CI で何度も叩くと当たり得る
  - tag-time smoke は first real tag push まで実地検証できない (chicken-and-egg)。RC tag を pre-flight で 1 度発射して挙動を確かめるのが妥当
  - 緩和策: PR-time smoke で全経路を exercise する設計を取る。テスト用 base URL の env override は必須

## 5. Research Items for Design Phase

1. **GitHub Releases API endpoint と response 解析**
   - `/repos/{owner}/{repo}/releases` を newest-first で enumerate
   - 各 release の `tag_name`, `prerelease`, `draft` フィールドを読む
   - SemVer 比較は Bash の `sort -V` で済むか、明示的な parser が必要か
   - JSON パースは `jq` が最も信頼できるが、user 環境にあるとは限らない (deno/bun の install.sh は grep+sed で済ませている。要参考)

2. **install.sh のテストモード**
   - PR-time smoke が実 GitHub Release を叩かずに済む方法
   - 候補: `KOLT_BASE_URL` env で `https://github.com/.../releases/download/<tag>/` を上書き、`KOLT_YANKED_URL` で manifest URL を上書き。ローカル `python3 -m http.server` で配信
   - install.sh の「test mode」を明示するか、env override で透過的に扱うか

3. **`kolt.toml` version vs tag の整合 gate**
   - release.yml の最初の step で `[[ "$(extract version from kolt.toml)" == "${GITHUB_REF#refs/tags/v}" ]]` を assert
   - assemble-dist.sh は kolt.toml を読むので、release.yml が前段で gate しないと「tag は v0.16.0 だが tarball は 0.15.0」の不整合が出る
   - assemble-dist.sh 自身が tag check を持つべきか、release.yml が前段で gate するべきか

4. **404 (asset 不在) のエラーハンドリング**
   - 過去 release (v0.15.0 等) には asset が無い。`KOLT_VERSION=0.15.0` 指定で install.sh が hit するシナリオ
   - 親切なメッセージ: 「version `<v>` にはバイナリが publish されていません (the installer was introduced in #230, earlier releases predate it)」
   - これは Req 5 と Req 3 のギャップに位置する。design phase でメッセージ文言を pin

5. **GitHub Actions permissions と secrets**
   - release.yml は `permissions: contents: write` が必須 (asset upload のため)
   - `${{ secrets.GITHUB_TOKEN }}` で `gh release create` が動くことを確認
   - PR-time smoke は `contents: read` のみで十分

6. **Bash portability**
   - install.sh は `#!/bin/sh` (POSIX sh) か `#!/bin/bash` (bash) か
   - macOS (将来) の bash は古い (3.2)。POSIX sh で書く方が長期的に安全
   - `case` 文・`[[ ]]` の使用可否、`local` の有無、配列の使用可否、を design phase で確定

7. **install.sh の I/O 体裁**
   - 進捗表示 (curl の `-#` flag、stage ごとの `echo` 等)
   - 成功時の最終メッセージ (「kolt 0.16.0 installed at ~/.local/bin/kolt」)
   - 失敗時のメッセージ formatting (色付け? plaintext?)
   - これは UX 設計、design phase で決める

8. **`gh release create` の使い方**
   - `--target` の SHA 指定 (tag created at SHA は GitHub が自動で持つ)
   - `--prerelease` flag for `-rc.N` / `-beta.N`
   - `--notes` / `--notes-file` の運用 (release notes は手動か、自動生成か。本 spec の scope 外と思われるが design で確認)

## 6. Recommendations for Design Phase

### Preferred approach
**Option B**: release.yml 新規 + self-host-smoke.yml の inline sed を install.sh 呼び出しに置換 + assemble-dist.sh に `.sha256` 生成追加。

### Key design decisions to nail down
1. **install.sh の shell**: POSIX sh か bash か → 推奨 sh (macOS 将来対応の保険)
2. **JSON 解析**: `jq` の動的検出 + grep+sed フォールバック か、grep+sed のみか → 推奨 grep+sed (依存最小)
3. **テストモードの env**: `KOLT_BASE_URL` / `KOLT_YANKED_URL` の名前と意味論
4. **404 メッセージ文言** (Req 5/Req 3 のギャップ補完)
5. **release.yml の job 構成**: 1 job (gates → assemble → upload → smoke) か 2 job (build / smoke を split) か → 推奨 1 job (state transfer の overhead 回避)
6. **`assemble-dist.sh` への .sha256 追加位置**: 末尾の `tar czf` の直後 (1〜2 行)。`sha256sum kolt-${VERSION}-linux-x64.tar.gz > kolt-${VERSION}-linux-x64.tar.gz.sha256`

### Carry-forward research items
- GitHub Releases API response format の確認 (実際に curl で叩いて構造を見る)
- deno / bun / rye / uv の install.sh 実装を 1 つ参照 (JSON 解析、SemVer 比較、エラーハンドリングのパターン)
- POSIX sh で macOS の古い `sed` (BSD sed) と GNU sed の差を確認 (in-place flag が `-i ''` vs `-i` の違い)

### Out of scope for design phase
- cosign / GPG 署名 (別 issue)
- 多 platform binary 生成 (#82, #83)
- install.sh の upgrade/uninstall (follow-up issue)

---

## Synthesis (追加 — design phase)

### Generalization

- **YANKED parser は release.yml と install.sh の 2 箇所で必要**だが、共有コードを切り出す価値は薄い。理由 3 点:
  1. install.sh は curl|sh で叩かれる self-contained script、外部依存を持てない (parse-yanked.sh への分割は install.sh 側で source できないので破綻する)。
  2. release.yml は GitHub Actions YAML、Bash step として inline で書く方が読みやすい
  3. ADR 0028 §5 が format を pin しており、両側で 10-20 行の単純実装がドリフトする risk は低い (parse error 時のメッセージ文言だけ少し違う程度)
- 結論: **両側で実装重複を許容し、install.sh の `parse_yanked` 関数のコメントに「release.yml の同等ロジックと一致させること」を明記する**。drift 検出は将来 native test で行う余地を残す。

### Build vs Adopt

- **Bash shell**: POSIX sh (`#!/bin/sh`) を採用。理由: 将来 macOS 対応 (#82) のとき bash 3.2 の制約に巻き込まれない。bash-isms (`[[ ]]`、配列、`local`) を避ける。
- **GitHub Releases API**: REST endpoint `/repos/snicmakino/kolt/releases?per_page=100` を curl で直接叩く。`gh` CLI は user 環境にあるとは限らない (curl|sh される側の前提)。
- **`gh release create`** (release.yml 側のみ): GHA runner は `gh` を pre-install しており、REST API より簡潔。
- **JSON parsing**: 必要なのは `tag_name` 文字列の抽出のみ。grep + sed で十分:
  ```sh
  grep -oE '"tag_name":\s*"[^"]+"' | sed -E 's/"tag_name":\s*"([^"]+)"/\1/'
  ```
  `jq` 依存は採らない (curl|sh される user 環境にあるとは限らない)。
- **SHA-256**: `sha256sum` (GNU coreutils on Linux)。macOS では `shasum -a 256` だが #82 で対応。初版は `sha256sum` 直書き + `command -v sha256sum` 検出のスケルトンを入れて将来分岐できる構造を残す。
- **SemVer 比較**: `sort -V` (GNU sort) で十分。BSD sort は `-V` を Catalina 以降サポート。初版 linux-x64 のみは GNU sort 前提。
- **Tarball 展開**: `tar xzf` (POSIX 標準)。

### Simplification

- **JSON parser を持たない**: `tag_name` 抽出のみ。response shape は GitHub の安定 contract、過剰な防御不要。
- **SemVer parser を持たない**: `sort -V` の出力をそのまま使う。`v0.16.0` と `v0.15.10` の比較もこれで通る。
- **download manager / progress UI なし**: `curl -fsSL` の `-sS` で進捗バー抑制、エラーは見せる。シンプル。
- **upgrade / rollback / lock なし**: 初版は fresh install のみ (ADR 0018 §4)。同 version 再 install は no-op に近い idempotent overwrite、異 version は symlink 上書き、で natural に動く。並行 install の race は accept (vanishingly rare)。
- **release.yml は単一 job + 隣接 smoke job の 2 job 構成**: build/publish を分けると state transfer (artifact upload/download) overhead。1 job 内 sequential step が最短。

### Test mode env vars (PR-time smoke の自然な統合)

PR-time install smoke は実 GitHub Releases を叩かずに install.sh の経路を完走させる必要がある。これを以下の 2 env vars で透過的に扱う:

- `KOLT_TEST_BASE_URL`: 設定時、tarball + `.sha256` の取得元 base URL を override (default: `https://github.com/snicmakino/kolt/releases/download/v<VERSION>`)。CI は `python3 -m http.server` 等で local serve し、その URL を渡す。
- `KOLT_TEST_YANKED_URL`: 設定時、YANKED manifest の取得元 URL を override (default: `https://raw.githubusercontent.com/snicmakino/kolt/main/YANKED`)。CI は synthetic YANKED ファイルへの URL を渡す。

これらは「テスト用」と install.sh のヘルプ / コメントで明記し、user 向けではないことを示す。`KOLT_VERSION` を併用することで API enumeration を完全 bypass できる。

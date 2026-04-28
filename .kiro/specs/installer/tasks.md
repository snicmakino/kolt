# Implementation Plan

## 1. Foundation: YANKED manifest と assemble-dist.sh extension

- [x] 1.1 (P) repo root に空の YANKED ファイルを commit
  - 0 bytes (改行を含まない) のファイルを `YANKED` として追加
  - `.gitignore` 対象外
  - `git add YANKED` + commit でファイルが repo HEAD に出現する
  - Observable: `ls -l YANKED` で size 0、`wc -c YANKED` で 0 が表示される
  - _Requirements: 6.1, 6.2_
  - _Boundary: YANKED file_

- [x] 1.2 (P) assemble-dist.sh に .sha256 生成 step を追加
  - 既存 `tar czf` 直後 (line 254 付近) に `(cd dist && sha256sum "kolt-${VERSION}-linux-x64.tar.gz" > "kolt-${VERSION}-linux-x64.tar.gz.sha256")` を追記
  - subshell 内で cd するので `.sha256` 内の filename は basename になる (絶対パス埋め込み無し)
  - 既存 cwd は `$ROOT_DIR` で `dist/...` 相対参照、subshell 統合に問題なし
  - Observable: `bash scripts/assemble-dist.sh` 実行後、`dist/kolt-<v>-linux-x64.tar.gz.sha256` が `<hex>  kolt-<v>-linux-x64.tar.gz` の 1 行を含むファイルとして存在する
  - _Requirements: 1.3, 5.1_
  - _Boundary: assemble-dist.sh extension_

## 2. install.sh の実装

- [x] 2.1 install.sh の skeleton + env var contract
  - `#!/bin/sh` shebang + `set -eu`
  - `KOLT_VERSION` / `KOLT_ALLOW_YANKED` / `KOLT_TEST_BASE_URL` / `KOLT_TEST_YANKED_URL` の default 値設定 (default URL は raw GitHub URL / GitHub Release URL)
  - 7 関数の stub (各 stub は echo "TODO: <name>" + exit 1) と main flow の関数 call 順序
  - bash-isms (`[[ ]]`, `local`, 配列) を使わない方針を冒頭コメントで明記
  - Observable: `sh install.sh` が `platform_detect` stub に到達して exit 1 する (set -u で env var の参照ミスが起きない)
  - _Boundary: install.sh_

- [x] 2.2 platform_detect 関数の実装
  - `uname -s` + `uname -m` を case 文 1 箇所で読む
  - `Linux` + `x86_64` → "linux-x64" を stdout、続行
  - `Darwin/*` → exit 1 + "macOS support is tracked in #82, not yet released" を stderr
  - `Linux/aarch64` 等 → exit 1 + "linuxArm64 support is tracked in #83, not yet released" を stderr
  - その他 → exit 1 + "unsupported platform: <os>/<arch>" を stderr
  - tarball 命名規約 `kolt-<version>-<platform>.tar.gz` の `<platform>` 変数化を維持 (URL 構築側で参照)
  - Observable: 各 OS/arch を環境で偽装した状態で exit code とメッセージが期待通り
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_
  - _Boundary: install.sh_

- [ ] 2.3 fetch_yanked_and_validate 関数の実装
  - `KOLT_TEST_YANKED_URL` 設定時はそれを、未設定時は raw GitHub URL を curl で fetch
  - HTTP error → exit 6 + URL と HTTP error をメッセージに含める
  - 各非空行に対し、`awk -F'\t' 'NF != 3 || $1=="" || $2=="" || $3==""'` 相当で format validation。コメント行 (`#` 始まり) と空行と先頭末尾空白を全て reject (ADR 0028 §5)
  - parse error → exit 2 + "YANKED parse error at line N: <reason>" を stderr
  - 成功時、validated tempfile の絶対 path を stdout
  - install.sh 1 回の実行で 1 度のみ呼ばれる前提
  - Observable: 不正フォーマット (2 fields のみ、空行入り、コメント `# foo` 入り、tab→spaces 置換) の YANKED を渡すと exit 2 + 違反行番号がメッセージに含まれる
  - _Requirements: 3.5, 3.6, 6.3, 6.4, 6.6, 6.7_
  - _Boundary: install.sh_

- [ ] 2.4 is_yanked + yank_check 関数の実装
  - `is_yanked(manifest, version)`: validated manifest を逐行読み、第 1 field が version と一致すれば exit 0 + `<replacement>\t<reason>` を stdout、無ければ exit 1。format validation は再実施しない (caller が事前 validate 済み前提)
  - `yank_check(manifest, version)`: `is_yanked` を呼び、(a) yanked かつ `KOLT_ALLOW_YANKED!=1` → exit 3 + replacement と reason を stderr、(b) yanked かつ `=1` → "warning: <v> is yanked, replacement is <r>" を stderr 出力後 return、(c) non-yanked → silent return
  - `set -e` 干渉対策として sub-shell capture (`result=$(is_yanked ...)`) を使う場合は `|| true` パターンを併用
  - Observable: yanked entry を持つ manifest と `KOLT_VERSION=<v>` を併用して install.sh を呼ぶと exit 3 + replacement が stderr に出る。`KOLT_ALLOW_YANKED=1` 付与で warning 出力後に続行する
  - _Requirements: 3.3, 3.4_
  - _Boundary: install.sh_

- [ ] 2.5 select_version 関数の実装
  - `KOLT_VERSION` set → 値をそのまま echo (API 不問、yank filter なし)
  - 未 set → `https://api.github.com/repos/snicmakino/kolt/releases?per_page=100` を curl
  - response から `grep -oE '"tag_name":\s*"[^"]+"'` + `sed -E 's/.../\1/'` で tag_name を抽出
  - pre-release (tag に `-` を含む) を除外
  - `sort -V -r` で降順 SemVer 並べ替え、loop 内で `is_yanked` を呼んで最初の non-yanked tag を pick
  - `v` prefix を除去して echo
  - API 失敗 (HTTP error / timeout) → exit 6
  - Observable: `KOLT_VERSION` 未設定時に GitHub Releases から SemVer 順で最新の non-yanked tag が選ばれる (yanked tag が最新でも skip される)。`KOLT_VERSION=<v>` 指定時はそのまま `<v>` が返る
  - _Requirements: 3.1, 3.2_
  - _Boundary: install.sh_

- [ ] 2.6 download_and_verify 関数の実装
  - `KOLT_TEST_BASE_URL` 設定時はそれを base、未設定時は `https://github.com/snicmakino/kolt/releases/download/v<v>` を base にする
  - tarball (`kolt-<v>-<platform>.tar.gz`) と `.sha256` を curl で fetch
  - HTTP 404 / error → exit 4 + URL と HTTP status をメッセージに含める。tarball が無い version (pre-#230 release) のケースに「the installer was introduced in #230, earlier releases predate it」相当の hint を 404 時に追加
  - `sha256sum -c` 相当で digest verify (download した `.sha256` を `sha256sum -c` に渡す)
  - 検証 mismatch → tarball を削除 + exit 5 + 期待 digest と実 digest 両方を stderr
  - 成功時 tarball の local path を stdout
  - Observable: 改竄した `.sha256` (digest を 1 文字書き換え) を serve した状態で install.sh を呼ぶと exit 5 + 両 digest がメッセージに出力される
  - _Requirements: 5.2, 5.3, 5.4_
  - _Boundary: install.sh_

- [ ] 2.7 extract_and_link 関数の実装 (idempotent)
  - `mkdir -p ~/.local/share/kolt ~/.local/bin` (必要なら作成)
  - `~/.local/bin/kolt` の existing を `[ -L ... ]` で check: 既存しかつ symlink ではない (regular file / dir) → exit 8 + 「remove this file manually then re-run」を stderr
  - `~/.local/share/kolt/<v>/` 既存なら `rm -rf` (rustup-init 流の clean repair)
  - `tar xzf <tarball> -C ~/.local/share/kolt` で展開 (`kolt-<v>-linux-x64/` が作られる)
  - canonical name (`<v>/`) に rename
  - `libexec/classpath/*.argfile` 中の `@KOLT_LIBEXEC@` を `<install-dir>/libexec` の絶対 path に sed `-i` で置換
  - `ln -sf <install-dir>/bin/kolt ~/.local/bin/kolt` で symlink 作成・上書き
  - 全失敗 path で symlink 作成は最後 (展開済み・sed 済みでも symlink が古いままにならない順序)
  - Observable: 同 version で 2 度実行すると古い `~/.local/share/kolt/<v>/` が削除されて新規展開された状態になり、`~/.local/bin/kolt --version` が exit 0 で対象 version 文字列を返す
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6_
  - _Boundary: install.sh_

- [ ] 2.8 print_path_hint + main flow 完成 + 成功メッセージ
  - `print_path_hint`: `case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) echo hint to stderr ;;` (literal `$HOME` のみ check、tilde `~` は見ない)
  - main flow: stub を全部実装関数の呼び出しに置換、各関数の stdout を変数 capture して順送り
  - 成功時 stdout に "kolt <version> installed at ~/.local/bin/kolt" を 1 行出力
  - Observable: PATH に `$HOME/.local/bin` が無い状態で実行すると hint が stderr に出る、含まれていれば hint なし。`kolt --version` を別シェルで叩いて exit 0 + version 出力を確認できる
  - _Requirements: 2.5, 2.7_
  - _Boundary: install.sh_

## 3. release.yml の実装

- [ ] 3.1 (P) workflow file の skeleton + triggers + permissions
  - `.github/workflows/release.yml` を新規作成
  - `on: push: tags: ['v*']` および `on: workflow_dispatch: inputs: dry_run: {type: boolean, default: true, required: true}`
  - `permissions: contents: write`
  - 2 jobs: `release` (steps を後続 task で埋める) + `smoke` (`needs: release` + job-level `if: github.event_name == 'push' || !inputs.dry_run`)
  - Observable: GHA UI で release.yml が visible、`v0.16.0-rc.test` 風の dummy tag (push せず手元で workflow yaml lint) で構文エラー無し、workflow_dispatch 入力 `dry_run` が UI で選択可能
  - _Requirements: 1.1_
  - _Boundary: release.yml_

- [ ] 3.2 release job: SemVer + kolt.toml + YANKED gates
  - Step 1 (SemVer): `${{ github.ref_name }}` を ADR 0028 §1 regex `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(rc|beta)\.[1-9]\d*)?$` に match させる。fail 時 step 失敗 + 該当 tag と regex を stderr
  - Step 2 (kolt.toml↔tag): `${{ github.ref_name }}` から `v` prefix を除いた値が `kolt.toml` の `version =` 行と一致することを assert。mismatch 時 両 value を stderr
  - Step 3 (YANKED gate): repo HEAD の `YANKED` を `awk` で逐行読み、各非空行が exactly 3 tab-separated non-empty fields であること (format validation) を assert。format error 時 違反行番号と reason を stderr で fail。tag の version が第 1 field に出現する行があれば fail (該当行を stderr)
  - 3 gate は build より前に直列実行、いずれかの failure で job fail
  - Observable: 不正 tag (例 `v0.16` のような pseudo-SemVer)、kolt.toml mismatch、yanked tag、不正 YANKED format の各ケースで release job が build に到達せず fail し、stderr に原因が一意に identify できる
  - _Requirements: 1.2, 1.4, 1.6, 6.5_
  - _Boundary: release.yml_

- [ ] 3.3 release job: build + assemble + sha256 + publish
  - Setup steps: `actions/checkout@v4` + `actions/setup-java@v4` (JDK 25 temurin) + libcurl4-openssl-dev install + `actions/cache@v4` 3 種 (gradle / konan / kolt)。self-host-smoke.yml と同じキャッシュキーを使い両 workflow で cache 共有
  - Bootstrap step: `./gradlew --no-daemon linkDebugExecutableLinuxX64`
  - Build step: 3 × `kolt build --release` (root / kolt-jvm-compiler-daemon / kolt-native-compiler-daemon)
  - Assemble step: `KOLT="$GITHUB_WORKSPACE/build/bin/linuxX64/debugExecutable/kolt.kexe" ./scripts/assemble-dist.sh` (self-host-smoke.yml の bootstrap kexe 経由 invocation pattern と同形、KOLT 環境変数は assemble-dist.sh の line 102 default を override する形で渡す)。1.2 で .sha256 が dist/ 配下に出る前提
  - Publish step: `gh release create --target $GITHUB_SHA <tag> dist/kolt-<v>-linux-x64.tar.gz dist/kolt-<v>-linux-x64.tar.gz.sha256`。tag が `-rc.N` / `-beta.N` の場合 `--prerelease` flag を付与。`if: github.event_name == 'push' || !inputs.dry_run` で dry_run 時は publish step だけ skip
  - Observable: 実 tag push (例 `v0.16.0`) で GitHub Release に 2 assets (`kolt-0.16.0-linux-x64.tar.gz` + `.sha256`) が attach される。`workflow_dispatch + dry_run=true` 起動時は GHA log に `dist/kolt-...sha256` 生成までの記録は残るが Release は作成されない
  - _Requirements: 1.1, 1.3, 1.5_
  - _Boundary: release.yml_
  - _Depends: 1.2_

- [ ] 3.4 smoke job: clean runner + raw URL curl|sh + assertion
  - `runs-on: ubuntu-latest` + clean state (`HOME=$(mktemp -d)` を export)
  - `curl -fsSL https://raw.githubusercontent.com/snicmakino/kolt/main/install.sh | sh` で install.sh を起動
  - `~/.local/bin/kolt --version` の exit 0 + `^kolt <expected-version>$` regex match を assert
  - smoke fail で job fail (release は既に publish 済みなので Release は public のまま残るが、smoke job 失敗が GHA UI に表示される)
  - `if: github.event_name == 'push' || !inputs.dry_run` で dry_run 時は job 全体 skip
  - 注: 本 step の curl|sh は install.sh が main にマージされてから初めて意味を持つ。実 exercise は first real tag (v0.16.0 想定) の workflow run で発生する
  - Observable: tag-time の workflow run で smoke job が success、`kolt --version` の出力が GHA log に記録される
  - _Requirements: 2.5, 7.3, 7.4_
  - _Boundary: release.yml_
  - _Depends: 2.8_

## 4. self-host-smoke.yml extension

- [ ] 4.1 inline sed step を install.sh + 環境変数経由に置換 (happy path)
  - 既存 lines 181-188 (Substitute KOLT_LIBEXEC placeholder in argfiles step) を削除
  - serve directory を `mktemp -d` で用意し、`dist/kolt-<v>-linux-x64.tar.gz` + `.sha256` + 空 `YANKED` を copy / 生成
  - `python3 -m http.server 8000 --bind 127.0.0.1 --directory <serve-dir> &` で local serve、PID を変数に保存し step 終了時に `trap 'kill $PID' EXIT`
  - install.sh をローカル checkout から実行: `KOLT_TEST_BASE_URL=http://127.0.0.1:8000 KOLT_TEST_YANKED_URL=http://127.0.0.1:8000/YANKED KOLT_VERSION=<kolt.toml の version> sh ./install.sh`
  - 完了後 `~/.local/bin/kolt --version` を assert (exit 0 + version 文字列一致)
  - 既存の fixture smoke build (lines 194-201) のロジックは保持しつつ、INSTALL_DIR の path を install.sh が作る `~/.local/share/kolt/<v>/` に取り直す形に書き換える (variable 1 つの差し替え)
  - Observable: PR-time CI で install.sh の本物の経路 (download → SHA verify → extract → sed → symlink → version 確認) が緑になる。dist/ 配下のファイルが install dir (`~/.local/share/kolt/<v>/`) に正しく配置される
  - _Requirements: 7.1, 7.5, 2.5_
  - _Boundary: self-host-smoke.yml_
  - _Depends: 2.8_

- [ ] 4.2 yank refuse + override smoke step を追加
  - 同 HTTP server の serve directory に `YANKED-with-yank` を生成 (1 行: `<kolt.toml-version>\treplacement-test\ttest yank entry`)
  - `KOLT_TEST_YANKED_URL=http://127.0.0.1:8000/YANKED-with-yank KOLT_VERSION=<v>` で install.sh を起動 (set +e でくくって exit code を捕まえる)
  - exit code が 3 であること、replacement version 文字列 (`replacement-test`) が stderr に含まれることを assert
  - 続けて `KOLT_ALLOW_YANKED=1` 付与で同じ command を再実行、exit 0 + warning が stderr に出ることを assert
  - Observable: PR-time CI で「yanked refuse path で exit 3」と「override で exit 0 + warning」の 2 シナリオが両方緑になる
  - _Requirements: 7.2, 3.3, 3.4_
  - _Boundary: self-host-smoke.yml_
  - _Depends: 2.8_

- [ ] 4.3 install.sh の edge case smokes (parse error / non-symlink / SHA mismatch)
  - parse error: serve directory に `YANKED-bad` (例: 2 fields のみの行) を置き、`KOLT_TEST_YANKED_URL=...YANKED-bad` で install.sh を呼んで exit 2 + 違反行番号が stderr に出ることを assert
  - non-symlink refuse: `touch ~/.local/bin/kolt` (regular file) を作って install.sh を呼び、exit 8 + 「remove this file manually」を stderr で assert。後始末で `rm ~/.local/bin/kolt`
  - SHA-256 mismatch: serve directory の `kolt-<v>-linux-x64.tar.gz.sha256` の digest を 1 文字書き換え、install.sh を呼んで exit 5 + 期待・実 digest 両方が stderr に出ることを assert
  - 各 case で `set +e` で exit code を捕まえ、HTTP server / 一時ファイルの cleanup を trap で行う
  - Observable: 3 つの edge case それぞれで install.sh が期待 exit code を返し、stderr メッセージが識別可能
  - _Requirements: 6.3, 6.4, 6.6, 5.3_
  - _Boundary: self-host-smoke.yml_
  - _Depends: 2.8_

## 5. (P) README Installation セクション書き換え

- [ ] 5. README.md の Installation section を curl|sh コマンドに書き換え
  - 既存 lines 14-30 (Build from source 手順 + #97 言及) を削除
  - 新セクション冒頭: copy-paste-able code block で `curl -fsSL https://raw.githubusercontent.com/snicmakino/kolt/main/install.sh | sh`
  - 環境変数解説 (1-2 行ずつ): `KOLT_VERSION=<v>` で特定 version 指定、`KOLT_ALLOW_YANKED=1` で yanked 強制 install
  - サポート platform 明記: 「初版は linux-x64 のみ。macOS は #82、linuxArm64 は #83 で別 track」
  - PATH 注意: 「インストール後 `~/.local/bin` を `PATH` に追加する必要があり得る」
  - Build-from-source 手順は contributor 向けの別セクション (例: `### Building from source` 等) として README 下部に移動。`#97` 言及は self-host shipped 済のため削除
  - Observable: `cat README.md` で curl|sh コマンドと環境変数解説が冒頭近くに表示され、Build-from-source は contributor section に残る
  - _Requirements: 8.1, 8.2, 8.3, 8.4_
  - _Boundary: README.md_

## 6. End-to-end validation

- [ ] 6.1 PR-time CI が green になることを確認
  - 4.1 + 4.2 + 4.3 の 3 step を含む self-host-smoke.yml が PR 上で全て pass
  - 既存 jobs (self-host bootstrap、unit-tests) も regression なく pass
  - `unit-tests.yml` の linuxX64Test も従来通り green
  - Observable: PR の CI checks が全て green、self-host-post job のステップ一覧に install.sh smoke / yank refuse / edge case が表示されている
  - _Depends: 4.1, 4.2, 4.3_

- [ ]* 6.2 workflow_dispatch dry_run の手動検証 (optional / pre-flight)
  - PR を main にマージする前に release.yml を `workflow_dispatch + dry_run=true` で起動 (or fork branch で確認)
  - GHA UI で 3 gate (SemVer / kolt.toml / YANKED) + setup + bootstrap + 3 × kolt build + assemble-dist + sha256 までの step が全て成功し、`gh release create` step と smoke job が skip されたことを log で確認
  - 手動 GHA UI 操作のため optional 扱い (本タスクは実装の完了条件ではなく first tag 前の pre-flight として実行を推奨)
  - Observable: GHA UI に dry_run 起動の workflow run が記録され、`dist/kolt-...sha256` 生成までは成功、Release が作成されていない (Releases ページに新 entry なし)
  - _Depends: 3.3_

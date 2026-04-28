# Requirements Document

## Introduction

kolt は v0.15.0 まで「git clone → Gradle bootstrap → 自力で PATH 通し」が唯一の入手手段で、外部ユーザが軽く試す導線が無い。ADR 0018 §4 は tarball を `curl | sh` でインストールする UX を v1.0 までの必達ゴールとして定めており、PR #228 で `scripts/assemble-dist.sh` の tarball stitcher は完成済み、ADR 0028 §1/§5 で tag SemVer regex と YANKED manifest の format も pin 済みである。残っているのは (a) tag push を起点とする GitHub Actions release workflow、(b) 公開された `install.sh`、(c) repo HEAD の `YANKED` manifest、(d) install.sh と release workflow の CI smoke、の 4 つを束ねて出荷することのみ。

本 spec はこの 4 点を Linux x64 only で出荷する。多 platform バイナリ (#82 / #83) は tarball 命名と install.sh の case 文を初版から多 platform 前提で書くことで additive な diff として後から追加できるようにし、本 spec のスコープには含めない。SHA-256 verification は初版から install.sh が必須として実施し、cosign / GPG 署名は別 issue に defer する。YANKED gate は release workflow と install.sh の双方に同じ format で実装し、Bash 側 (install.sh) と Kotlin 側 (release workflow gate) で format が drift しないよう ADR 0028 §5 を唯一の正とする。

## Boundary Context

- **In scope**:
  - tag push (`v*`) を起点に走り、SemVer regex gate と YANKED gate を経て `assemble-dist.sh` を実行し、`kolt-<version>-linux-x64.tar.gz` と `kolt-<version>-linux-x64.tar.gz.sha256` を GitHub Release に upload する `.github/workflows/release.yml`。
  - repo root に置かれた `install.sh` (`curl -fsSL https://raw.githubusercontent.com/snicmakino/kolt/main/install.sh | sh` で起動)。
  - repo root の空の `YANKED` ファイル。
  - `scripts/assemble-dist.sh` への `.sha256` 生成追加 (既存 tarball 生成パスへの最小拡張)。
  - install.sh smoke (PR 毎、ローカル組み立て tarball + 合成 YANKED に対して) と release workflow smoke (tag 毎、実 release assets に対して) の CI ジョブ。
  - README の install command と環境変数 (`KOLT_VERSION`、`KOLT_ALLOW_YANKED`) ドキュメント。
- **Out of scope**:
  - macOS / Windows / linuxArm64 サポート (#82, #83 で別 track)。install.sh の case 文に「未対応 platform」分岐を持つことは本 spec のスコープだが、各 platform 用 binary を produce することは含めない。
  - install.sh の upgrade / uninstall 経路、複数 version の rollback、shell rc への PATH 追記自動化。
  - `kolt.dev` ドメイン取得 / DNS 設定 / `https://kolt.dev/install.sh` redirect (raw GitHub URL を初版で採用)。
  - cosign / GPG / Sigstore 署名 (SHA-256 のみ)。
  - 多 platform 同時 release (linux-x64 のみ生成)。
- **Adjacent expectations**:
  - `scripts/assemble-dist.sh` (PR #228) は tarball layout (ADR 0018 §1: `bin/` + `libexec/`) と `@KOLT_LIBEXEC@` placeholder の sed 戦略を確定済み。本 spec はこれを呼び出す側に立ち、tarball 生成ロジック自体は変更しない (`.sha256` 生成のみ追加)。
  - ADR 0018 §1/§4 が tarball layout、install 先 (`~/.local/share/kolt/<version>/`)、symlink 先 (`~/.local/bin/kolt`)、platform 検出方針を pin 済み。本 spec はこれに準拠する。
  - ADR 0028 §1 が tag SemVer regex `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(rc|beta)\.[1-9]\d*)?$` を、§5 が `YANKED` manifest format (tab-separated `version<TAB>replacement<TAB>reason`、コメント・空行不可、newest yank last) を pin 済み。本 spec はこれに準拠する。
  - `self-host-smoke.yml` は既に PR 毎に `assemble-dist.sh` を走らせて tarball 生成と install レイアウト展開を検証している。本 spec の install.sh smoke はこの既存検証と重複しないよう「install.sh 自身の挙動」に focus する。
  - CLAUDE.md の "no backward compatibility until v1.0" 方針に従い、release workflow / install.sh / YANKED の format に対する pre-v1 における breaking change は migration shim を伴わず行ってよい。

## Requirements

### Requirement 1: tag push を起点とする release workflow が成果物を発行する

**Objective:** kolt メンテナとして、SemVer 形式の tag を push するだけで対応する tarball と SHA-256 が GitHub Release に自動で公開されることを望む。手元で `assemble-dist.sh` を実行して assets を手動 upload する手間を排し、release が tag SHA に bind された再現可能な手順となるためである。

#### Acceptance Criteria

1. When ADR 0028 §1 の SemVer regex `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(rc|beta)\.[1-9]\d*)?$` に match する tag が push された時、the release workflow shall `scripts/assemble-dist.sh` を実行し、生成された `kolt-<version>-linux-x64.tar.gz` を tag に対応する GitHub Release に upload する。
2. If push された tag が上記 SemVer regex に match しない時、then the release workflow shall asset の upload を一切行わず非ゼロ終了で job を fail させ、Release を draft として残さない。
3. When the release workflow が tarball を upload する時、the release workflow shall 同一 Release に `kolt-<version>-linux-x64.tar.gz.sha256` を `sha256sum` の標準出力フォーマット (`<hex digest>  <filename>\n`) で併せて upload する。
4. If push された tag の version が repo HEAD の `YANKED` の最初の column に出現する時、then the release workflow shall asset の upload を行わず非ゼロ終了で job を fail させ、エラーメッセージに該当行を含める。
5. When push された tag が `-rc.N` または `-beta.N` 形式の pre-release suffix を持つ時、the release workflow shall 対応する GitHub Release を pre-release として mark し、`latest` Release として扱われない状態にする。
6. The release workflow shall tarball の version 文字列を `kolt.toml` の `version =` 値ではなく push された tag (`v` prefix を除いたもの) から導出し、tag と tarball ファイル名と Release タイトルが齟齬なく一致する状態を保つ。

### Requirement 2: install.sh が linux-x64 上で fresh install を完了させる

**Objective:** kolt を初めて試す利用者として、`curl -fsSL <raw URL> | sh` の 1 コマンドで kolt が動く状態にしてもらえることを望む。git clone や package manager を介さずに deno / bun / rye / uv 並みの導入体験で kolt を評価できるためである。

#### Acceptance Criteria

1. When 利用者が linux-x64 ホスト上で install.sh を実行した時、the install script shall 対象 version の tarball と `.sha256` を GitHub Release から download し、SHA-256 verification 成功後に `~/.local/share/kolt/<version>/` 配下に展開する。
2. When 展開が完了した時、the install script shall `~/.local/share/kolt/<version>/libexec/classpath/` 配下の全 `*.argfile` 中の `@KOLT_LIBEXEC@` placeholder を `~/.local/share/kolt/<version>/libexec` の絶対パスに in-place 置換する。
3. When argfile の置換が完了した時、the install script shall `~/.local/bin/kolt` symlink を `~/.local/share/kolt/<version>/bin/kolt` に作成または既存 symlink を上書き更新する。
4. While `~/.local/bin` または `~/.local/share/kolt` ディレクトリが利用者のホームに存在しない時、the install script shall それらを `mkdir -p` で先回り作成し、permission error 以外の理由で fail しない。
5. When install.sh が成功終了した時、the install script shall `~/.local/bin/kolt --version` が exit code 0 で対象 version 文字列を stdout に出力する状態を保証する。
6. If `~/.local/bin/kolt` が既存しかつ symlink ではない (regular file / directory) 時、then the install script shall その既存ファイルを上書きせず非ゼロ終了し、利用者に削除を求めるメッセージを出力する。
7. When the install script がインストールを完了し、かつ利用者の `PATH` 環境変数に `~/.local/bin` が含まれていない時、the install script shall 「`~/.local/bin` を `PATH` に追加してください」相当の hint を stderr に出力する (shell rc の自動編集は行わない)。

### Requirement 3: install.sh の version 選択と YANKED 取り扱いが明確である

**Objective:** kolt 利用者として、`KOLT_VERSION` 未指定では maintainer が現時点で推奨する最新版がインストールされ、yank された version は明示同意なしには引かれないことを望む。誤って既知不具合のある version をインストールしないこと、および特定 version への pin を必要とする CI / 再現テストでも install.sh が同じ経路で使えることが目的である。

#### Acceptance Criteria

1. When 利用者が `KOLT_VERSION` 環境変数を設定せず install.sh を実行した時、the install script shall GitHub Releases を SemVer 順に降順列挙し、pre-release (rc / beta) 以外でかつ `YANKED` に出現しない最新の tag を install 対象として pick する。
2. When `KOLT_VERSION=<v>` が設定された時、the install script shall `<v>` を install 対象として用い (本 AC は AC 3 / AC 4 に従って yank チェックを行う)、最新版検出ロジックを bypass する。
3. If install 対象 version が repo HEAD の `YANKED` の最初の column に出現し、かつ `KOLT_ALLOW_YANKED` 環境変数が `1` に設定されていない時、then the install script shall tarball を download せず非ゼロ終了し、`YANKED` から読み取った replacement version をエラーメッセージに含めて利用者に提示する。
4. When 同条件で `KOLT_ALLOW_YANKED=1` が設定されている時、the install script shall インストールを続行しつつ「version `<v>` は yank 済み、replacement は `<r>` です」相当の警告を stderr に出力する。
5. If `YANKED` manifest の fetch が HTTP error / 404 / network error で失敗した時、then the install script shall tarball download を行わず非ゼロ終了し、manifest URL と取得失敗の事実をエラーメッセージに含める。
6. The install script shall `YANKED` manifest を `https://raw.githubusercontent.com/snicmakino/kolt/main/YANKED` から fetch し、その他のソース (キャッシュ、バンドル、過去 release の埋め込みなど) を参照しない。

### Requirement 4: install.sh が unsupported platform を明示拒否する

**Objective:** kolt 利用者として、非対応 platform で install.sh を実行した時に「サイレントに動かない」のではなく「何が動かず、いつ動く予定か」を即座に伝えてもらえることを望む。利用者が次に何をすべきか判断でき、また将来 #82 / #83 が darwin / arm64 を実装した時に install.sh への変更が darwin 分岐の fail メッセージ除去と case 文への entry 追加のみで済む構造にしておくことが目的である。

#### Acceptance Criteria

1. When `uname -s` が `Linux` かつ `uname -m` が `x86_64` の時、the install script shall platform 文字列 `linux-x64` を確定し install 経路を続行する。
2. If `uname -s` が `Darwin` の時、then the install script shall tarball download を行わず非ゼロ終了し、エラーメッセージに「macOS support is tracked in #82, not yet released」相当の文と issue へのリンクを含める。
3. If `uname -s` が `Linux` で `uname -m` が `x86_64` 以外 (`aarch64` 等) の時、then the install script shall tarball download を行わず非ゼロ終了し、エラーメッセージに「linuxArm64 support is tracked in #83, not yet released」相当の文を含める。
4. If `uname -s` が `Linux` / `Darwin` 以外の時、then the install script shall tarball download を行わず非ゼロ終了し、エラーメッセージに detect された OS / arch 文字列と「unsupported platform」相当の文を含める。
5. The tarball naming convention shall `kolt-<version>-<platform>.tar.gz` の形に従い、platform 部分が変数として置換可能な状態を保つ (#82 / #83 が新 platform を additive に追加できる前提を作る)。

### Requirement 5: SHA-256 verification が tarball 取得経路に組み込まれる

**Objective:** kolt 利用者として、curl で取得した tarball が転送中に改竄・破損していないことが install.sh によって自動検証されることを望む。download の整合性を maintainer の手作業ではなく仕組みで保証し、cosign / GPG への将来の拡張を容易にするためである。

#### Acceptance Criteria

1. When the release workflow が tarball を upload する時、the release workflow shall 同 tarball の SHA-256 digest を `sha256sum` を用いて算出し `<hex digest>  <filename>\n` 形式の `.sha256` ファイルとして同 Release に upload する (Requirement 1 AC 3 と整合)。
2. When the install script が tarball を download した時、the install script shall 同 Release から `.sha256` ファイルを併せて download し、tarball の実 SHA-256 と `.sha256` 内 digest が一致することを `sha256sum -c` 相当の方法で検証する。
3. If SHA-256 検証が失敗した時、then the install script shall download した tarball を削除し、`~/.local/share/kolt/<version>/` への展開を一切行わず非ゼロ終了し、エラーメッセージに期待 digest と実 digest の双方を含める。
4. The install script shall SHA-256 検証を tarball 展開・argfile 置換・symlink 作成のいずれより前に実施し、検証未通過の状態でファイルシステムに改変を加えない。

### Requirement 6: YANKED manifest の format と repo 配置が確定する

**Objective:** kolt メンテナおよび install 経路実装として、yank 状態が単一の machine-readable な manifest にのみ存在し、Bash (install.sh) と GitHub Actions (release workflow gate) の双方が同じ format で読めることを望む。format drift による「release workflow は通ったが install.sh は refuse する」のような不整合を防ぎ、ADR 0028 §5 を唯一の権威とするためである。

#### Acceptance Criteria

1. The kolt repository shall repo root に `YANKED` という名前のテキストファイルを 1 つ持つ。
2. Where 現時点で yank されている version が無い時、the YANKED manifest shall サイズ 0 bytes (改行のみの行も含まない) として commit されている。
3. The YANKED manifest shall 各非空行について exactly 3 つの tab (`\t`) 区切りフィールドを持ち、左から `<yanked-version>`、`<replacement-version>`、`<reason>` の順で並ぶ (ADR 0028 §5 完全準拠)。
4. The YANKED manifest shall コメント行・空行・先頭末尾の余分な空白を許容しない。新しい yank entry は manifest の末尾に追記する (newest yank last)。
5. If the release workflow が YANKED manifest の構文違反 (フィールド数不一致 / 空行 / コメント等) を検出した時、then the release workflow shall 非ゼロ終了し、エラーメッセージに違反行番号と検出された問題を含める。
6. If the install script が YANKED manifest の構文違反を検出した時、then the install script shall 非ゼロ終了し、エラーメッセージに違反行番号と検出された問題を含める。
7. The release workflow および the install script shall 同じ YANKED manifest を repo HEAD (main branch) から fetch し、過去 commit / 過去 Release / バンドル済みコピーを参照しない。

### Requirement 7: install.sh と release workflow が CI で smoke test される

**Objective:** kolt メンテナとして、install.sh または release workflow に対する変更が CI を通過するためには「実際に動くこと」が要求される状態を望む。手元での動作確認漏れや、`install.sh` の Bash regression、release workflow の YAML 構文エラー、YANKED gate の format drift 等を本番 release 前に検出するためである。

#### Acceptance Criteria

1. When pull request が main に対して open / update された時、the CI shall `scripts/assemble-dist.sh` を実行して tarball を生成し、HOME を `mktemp -d` で隔離した状態で install.sh を当該ローカル tarball に対して実行し、`kolt --version` の exit code 0 と version 文字列の一致を assert する。
2. When 同 PR CI ジョブで yank refuse path が exercising される時、the CI shall 合成 `YANKED` manifest にテスト version の entry を加えた状態で install.sh を当該 version 指定で実行し、`KOLT_ALLOW_YANKED` 未設定では非ゼロ終了し replacement version が stderr に出力されることを assert する。
3. When SemVer 形式の tag が push されて release workflow が assets を publish した時、the CI shall 直後に新規の clean GHA `ubuntu-latest` runner を立ち上げて install.sh を生 GitHub URL から `curl | sh` で実行し、`~/.local/bin/kolt --version` の成功を assert する。
4. If 上記いずれかの smoke ジョブが fail した時、then the CI shall 当該 PR の merge をブロックする (PR 時) もしくは tag に対応する GitHub Release を pre-release のまま残し latest mark を付けない (tag 時)。
5. The PR-time install smoke shall 実 GitHub Release assets を必要とせず、release が出ていない HEAD であっても install.sh の経路が常に exercise される状態を保つ。

### Requirement 8: README が install command と環境変数を文書化する

**Objective:** kolt 評価者として、README の冒頭を読むだけで install コマンドと version 指定方法が分かることを望む。GitHub の Releases ページや ADR を辿らずに「最初の 1 コマンド」と「特定 version を入れたい時の手順」が同じ場所で見つかるためである。

#### Acceptance Criteria

1. The README shall インストール手順を示すセクションに `curl -fsSL https://raw.githubusercontent.com/snicmakino/kolt/main/install.sh | sh` (もしくは同等の表記) を copy-paste 可能な code block として含む。
2. The README shall `KOLT_VERSION=<v>` を「特定 version を入れる時に設定する環境変数」として、`KOLT_ALLOW_YANKED=1` を「yank された version を強制インストールする環境変数」として、それぞれ 1 文以上で文書化する。
3. The README shall 初版が linux-x64 のみであること、macOS / linuxArm64 が #82 / #83 で別 track であることを明記する。
4. The README shall インストール後に `~/.local/bin` を `PATH` に追加する必要があり得ることに 1 文以上で言及する。

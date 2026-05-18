# Requirements Document

## Project Description (Input)
`kolt self update` (および `kolt self update --check`) を追加し、 kolt の単一バイナリが GitHub Releases から自分自身を最新版に置き換えられるようにする。 ユーザーがインストーラを再実行したりリリース成果物を手動で取得することなく、 新リリースに追従できる体験を提供する。

Source issue: [snicmakino/kolt#27](https://github.com/snicmakino/kolt/issues/27) (milestone v0.21.0, size: M, priority: nice)

### スコープの方針 (圧縮版)
本 spec は **install.sh が POSIX sh で持つのと同等のスコープ** を Kotlin/Native 側に実装するものとする。 install.sh wrapper にしないのは、 macOS / linuxArm64 対応時に platform 分岐をネイティブ側で一元管理するため (memory `project_macos_selfhost_v1`)。 install.sh が持たない防衛機構 (並行排他 advisory lock、 SIGINT 中断回復、 installer layout の網羅的分類) は priority: nice の本機能には過剰であり、 意図的に scope 外とする。

### Who has the problem
- 既存 kolt ユーザーで、 インストーラ経由で `~/.local/bin/kolt` (installer spec のレイアウト) に kolt を入れている人。
- 新リリースに追従する手段が今は「インストーラを再実行する」または「リリースページから tarball を手動で取得する」しかない。

### Current situation
- kolt は Kotlin/Native の単一バイナリ (linuxX64) として配布される。
- installer spec (`.kiro/specs/installer/`) が `~/.local/share/kolt/<version>/bin/kolt` 実体と `~/.local/bin/kolt` → 実体への symlink という配置を所有している。 release tarball は `bin/` + `libexec/` 構造で daemon thin jar を同梱するため、 バイナリ更新と daemon 更新は tarball 取り直しで同期する。
- リリース成果物の命名規則は `kolt-<version>-linux-x64.tar.gz` と隣接する `kolt-<version>-linux-x64.tar.gz.sha256` で、 後者は標準 `sha256sum` フォーマット。
- `kolt --version` は `kolt <semver>` を出力する (例: `kolt 0.20.0`)。
- kolt は HTTPS ダウンロードを既に実装済み (内部の HTTPS GET 基盤を再利用する)。
- v1 まで backward compatibility は持たない方針 (memory `feedback_no_backcompat_pre_v1`)。

### What should change
- 新しい CLI subcommand 群 `kolt self` を導入し、 その下に `update` (および `--check`) を実装する。
- `--check` は API 参照のみで書き込み副作用ゼロ、 `update` は最新版へ置換する。
- linuxX64 以外と installer layout 非一致のケースは明示的にエラーで拒否する。
- ネットワーク・チェックサム・展開のエラーは識別可能なカテゴリで報告される。

## Boundary Context

- **In scope**:
  - `kolt self update` および `kolt self update --check` の追加。
  - linuxX64 ターゲットの kolt が installer spec のレイアウトでインストールされているケース。
  - 比較対象は GitHub Releases の `releases/latest` (安定版のみ、 prerelease は release workflow が除外)。
  - SHA256 による完全性検証と、 symlink を `rename(2)` で切り替えることによる「途中状態を観測させない」最小限の atomic 性。
  - Network / metadata / asset / extract / layout / platform のエラーをカテゴリ単位で報告。
- **Out of scope**:
  - macOS / linuxArm64 ターゲット (memory `project_macos_selfhost_v1` でロードマップ管理)。
  - `kolt self update --version <ver>` のような任意バージョン指定、 ダウングレード、 プレリリース追従。
  - `~/.local/share/kolt/<old>/` の自動削除や rollback コマンド。
  - `/usr/local/bin/kolt` など installer layout 外への上書き、 root 権限昇格、 `sudo` 自動実行。
  - `kolt self uninstall` などその他 `self` サブコマンド。
  - **並行 `kolt self update` 実行の排他**: advisory lock は持たない。 staging は pid 別ディレクトリ (`.staging-<pid>/`) で隔離されるため並行プロセスが互いの展開作業を破壊することはないが、 最終的な symlink 切替は last writer wins。 通常ユーザーは並行実行しない前提。
  - **SIGINT / kill 中断からの専用回復ロジック**: 中断時は一時展開ディレクトリが残るが、 次回 `kolt self update` 実行時に無条件で作り直されるため実害はない。 中断検出や復旧の専用機構は持たない。
  - **installer layout 非一致の網羅的分類**: 「installer 配置の symlink か否か」 の単一判定に留め、 dangling / 外部 target / regular file 等の細分類はしない (どのケースも「install.sh で入れ直してください」 で十分)。
- **Adjacent expectations**:
  - **installer spec が所有するもの**: 配布 tarball の構造、 `~/.local/share/kolt/<ver>/bin/kolt` 配置、 `~/.local/bin/kolt` symlink の初期作成。 `kolt self update` はこのレイアウトを前提に動作するが、 レイアウトそのものを再定義しない。
  - **release workflow が所有するもの**: GitHub Releases に `kolt-<ver>-linux-x64.tar.gz` と `.sha256` を同時公開し、 安定版以外を draft / prerelease として印付けることで `releases/latest` が安定版のみを返すこと。
  - **既存の HTTPS ダウンロード機構が提供するもの**: HTTPS GET と一時ファイルへのストリーミング書き込みは kolt 内に既にある (再実装しない前提)。
  - **既存 daemon の version 隔離**: daemon ↔ binary の版整合性は既存の socket fingerprint 機構が自動的に隔離する。 `kolt self update` は daemon を意識しない。

## Requirements

### Requirement 1: `kolt self update` サブコマンドの提供
**Objective:** As a kolt ユーザー, I want `kolt self update` および `kolt self update --check` を直接呼び出せる, so that 別ツールやインストーラ再実行なしに kolt の自己更新を起動できる.

#### Acceptance Criteria
1. When ユーザーが `kolt self update` を引数なしで実行した場合, the kolt CLI shall 「最新版の取得 → 検証 → 置換」を一連の処理として開始する.
2. When ユーザーが `kolt self update --check` を実行した場合, the kolt CLI shall 最新版の確認のみを行い、 ファイルシステムに書き込みを残さない.
3. When ユーザーが `kolt self` を引数なしで実行した場合, the kolt CLI shall `update` を含む利用可能なサブコマンド一覧を usage 文として表示し、 exit code 0 で終了する.
4. When ユーザーが `kolt self update --help` または `kolt self --help` を実行した場合, the kolt CLI shall `update` と `--check` の使い方を含む usage 文字列を表示し、 exit code 0 で終了する.
5. If ユーザーが `kolt self update` に未知のフラグまたは引数を渡した場合, then the kolt CLI shall 未知のフラグ名を含むエラーメッセージを表示し、 exit code を 0 以外で終了する.

### Requirement 2: 最新バージョンの取得と比較 (`--check`)
**Objective:** As a kolt ユーザー, I want 自分の kolt が最新かどうかをファイルを書き換えずに確認したい, so that 任意のタイミングで「アップデート可能か」を安全に問い合わせできる.

#### Acceptance Criteria
1. When `kolt self update --check` が呼ばれた場合, the kolt CLI shall GitHub Releases の `releases/latest` のタグ名を取得し、 タグ名が `vX.Y.Z` 形式 (X, Y, Z は非負整数、 プレリリース修飾子なし) であることを確認する.
2. When タグ名の取得・パースに成功した場合, the kolt CLI shall 現在実行中の kolt のセマンティックバージョン (`kolt --version` の出力から `kolt ` を除いた部分) と取得したバージョンを semver 順序で比較する.
3. When 取得した最新バージョンが現在バージョンより新しい場合, the kolt CLI shall 「Update available: `<current>` → `<latest>`」を含むメッセージを標準出力に表示し、 exit code 0 で終了する.
4. When 取得した最新バージョンが現在バージョン以下 (等しいか古い) の場合, the kolt CLI shall 「Already at latest version (`<current>`)」を含むメッセージを標準出力に表示し、 exit code 0 で終了する.
5. While `--check` が動作している間, the kolt CLI shall インストールディレクトリ・symlink・実行バイナリのいずれにも書き込みを行わない.
6. The kolt CLI shall `--check` の正常完了時、 アップデート可否によらず常に exit code 0 を返す.

### Requirement 3: 最新バージョンへの実行更新 (`update`)
**Objective:** As a kolt ユーザー, I want `kolt self update` 1 コマンドで kolt 本体を最新の安定リリースに置き換えたい, so that 手動ダウンロードや tar 展開、 symlink 操作を回避できる.

#### Acceptance Criteria
1. When `kolt self update` が呼ばれ、 最新バージョンが現在バージョンより新しい場合, the kolt CLI shall 取得 → 検証 → 展開 → 切替 → 完了表示の順に処理を進める.
2. When `kolt self update` が呼ばれ、 最新バージョンが現在バージョン以下である場合, the kolt CLI shall ファイルシステムに変更を加えず、 「Already at latest version (`<current>`)」を表示し、 exit code 0 で終了する.
3. When 更新が成功した場合, the kolt CLI shall `<old-version> → <new-version>` 形式で更新前後のバージョン文字列を最終行に表示し、 exit code 0 で終了する.
4. While 更新処理が進行中である間, the kolt CLI shall 以下の進行ステージそれぞれの開始時に、 一行ずつ識別可能な進捗行を標準出力に表示する: `fetching release metadata`, `downloading tarball`, `verifying checksum`, `extracting`, `switching to new version`.
5. The kolt CLI shall 既存の `~/.local/share/kolt/<old-version>/` および他の既存バージョンディレクトリを `kolt self update` の処理中に削除しない.
6. When symlink の切替を行う場合, the kolt CLI shall `~/.local/bin/kolt` を `rename(2)` 1 回で差し替え、 並行または後続の `kolt` 呼び出しが更新前か更新後のいずれかを完全に観測する (途中状態を観測しない) ようにする.

### Requirement 4: ダウンロードとチェックサム検証
**Objective:** As a kolt ユーザー, I want 自己更新で取得されるバイナリの完全性が保証されたい, so that ネットワーク経路の改竄や破損したダウンロードによって壊れた kolt を握らされない.

#### Acceptance Criteria
1. When `kolt self update` が新バージョンの取得を開始した場合, the kolt CLI shall リリース成果物 `kolt-<new-version>-linux-x64.tar.gz` と隣接する `.sha256` ファイルの両方を取得する.
2. When 両ファイルの取得に成功した場合, the kolt CLI shall ダウンロードした tarball の SHA-256 を計算し、 `.sha256` ファイル内のハッシュと一致するかを比較する.
3. If 取得した SHA-256 値とダウンロード成果物のハッシュが一致しない場合, then the kolt CLI shall インストールに進まずエラーを報告し、 `~/.local/share/kolt/` 配下に新バージョンディレクトリを作成しないまま exit code を 0 以外で終了する.
4. If `kolt-<new-version>-linux-x64.tar.gz` または `.sha256` のいずれかが該当リリースに公開されていない場合, then the kolt CLI shall どの成果物が欠落しているかを名前付きでエラーに含め、 exit code を 0 以外で終了する.
5. The kolt CLI shall ダウンロードと展開を自プロセス専用の一時ディレクトリ (`~/.local/share/kolt/.staging-<pid>/`) で行い、 SHA-256 検証と展開が成功した後に初めて `~/.local/share/kolt/<new-version>/` へ配置する. 自 pid のディレクトリは処理開始時に作り直す. 生存しているプロセスの `.staging-*` ディレクトリには触れない (並行実行時の相互破壊を防ぐ) が、 生存していないプロセスが残した `.staging-*` は処理開始時に best-effort で削除してよい (中断残骸の累積防止).

### Requirement 5: installer layout の検出と非対応構成の拒否
**Objective:** As a kolt の運用者, I want 想定外の場所に置かれた kolt バイナリに対しては自己更新を実行しないでほしい, so that root 所有の `/usr/local/bin/kolt` などを暗黙に書き換えて環境を壊さない.

#### Acceptance Criteria
1. When `kolt self update` が呼ばれた場合 (書き込みを伴う update 経路のみ; `--check` は対象外), the kolt CLI shall `~/.local/bin/kolt` が `~/.local/share/kolt/<ver>/bin/kolt` 配下を指す symlink であるか (installer spec のレイアウト) を判定する.
2. If `~/.local/bin/kolt` が installer spec のレイアウトと一致しない場合, then the kolt CLI shall 検出された実体パスと「install.sh で入れ直してください」の案内を含むエラーを表示し、 ネットワークアクセスもファイル書き込みも行わずに exit code を 0 以外で終了する.
3. If 起動された kolt は installer layout 上に存在するが、 `~/.local/share/kolt/` または `~/.local/bin/kolt` への書き込み権限が現在のユーザーにない場合, then the kolt CLI shall 該当パスを含むエラーを表示し、 ダウンロードや展開を行わずに exit code を 0 以外で終了する.

### Requirement 6: プラットフォーム対応範囲
**Objective:** As a kolt メンテナ, I want 現状サポートしている linuxX64 以外のプラットフォームでは自己更新を明示拒否したい, so that macOS / linuxArm64 など対応中のターゲットで意図しない動作が起こらない.

#### Acceptance Criteria
1. When `kolt self update` または `kolt self update --check` が linuxX64 以外のプラットフォームで実行された場合, the kolt CLI shall 「該当プラットフォーム向けバイナリは未対応である」旨を検出されたプラットフォーム名と共に表示し、 exit code を 0 以外で終了する.
2. The kolt CLI shall `--check` / `update` いずれの経路でもプラットフォーム判定を最初に実行する. update 経路では installer layout 判定 (Requirement 5) よりも前にプラットフォーム判定を行い、 非対応プラットフォーム上では GitHub Releases API への問い合わせも layout 検査も行わない.

### Requirement 7: エラー報告の識別性
**Objective:** As a kolt ユーザー, I want 自己更新が失敗した場面を「何で失敗したか」が一目で分かる形でメッセージを受け取りたい, so that ネットワーク・整合性・権限・サポート範囲のどの問題かを即座に切り分けられる.

#### Acceptance Criteria
1. If GitHub Releases API への通信が失敗した (タイムアウト・接続エラー・5xx) または応答したタグ情報が `vX.Y.Z` 形式に一致しない場合, then the kolt CLI shall ネットワーク由来かメタデータ由来かを区別したメッセージを表示し、 exit code を 0 以外で終了する.
2. If リリースアセット (`kolt-<new-version>-linux-x64.tar.gz` または `.sha256`) のダウンロードまたは SHA-256 検証に失敗した場合, then the kolt CLI shall アセット由来の失敗である旨と対象アセット名を表示し、 exit code を 0 以外で終了する.
3. If tarball の展開や中身の検証に失敗した場合, then the kolt CLI shall 展開フェーズでの失敗である旨を表示し、 `~/.local/bin/kolt` の symlink を変更しないまま exit code を 0 以外で終了する.
4. The kolt CLI shall 自己更新が失敗するすべての終了経路で、 人間可読な空でないエラーメッセージを表示し、 メッセージなしで非ゼロ終了しない.

# Requirements Document

## Introduction

複数の `kolt build` プロセスが同一プロジェクト (`kolt.toml`) または共有 `~/.kolt/cache/` に同時にアクセスしたときの挙動が現状未定義であり、`kolt.lock` の rewrite、`build/` 配下の成果物 finalisation、グローバルキャッシュへの JAR ダウンロードがすべて直接書き込みで実装されている。IDE-on-save watcher と手動 `kolt build` の併走、`kolt watch` と `kolt test` の同時起動、CI matrix ジョブが共通の `~/.kolt/cache/` に並列アクセスする運用は実プロジェクトで日常的に発生し、現実装では静かに状態を破壊しうる。

本 spec は、(a) 同一プロジェクトの並走に対しては project-local な排他ロックで `kolt.lock` rewrite と `build/` finalisation を直列化し、上限つきで待機させて clean error にフォールバックする、(b) グローバルキャッシュへの JAR ダウンロードは中間状態を最終パスに露出させない atomic な書き出しに切り替える、(c) 上記方針および NFS 越しの cross-machine 並走を unsupported とする旨を ADR で明文化する、というハイブリッド方針で v1.0 に向けた並行性契約を確立する。daemon socket の `bind()` は OS の `EADDRINUSE` で既に相互排他されており、`DaemonReaper` が孤立 socket を掃除する既存挙動に依存し、本 spec のスコープには含めない。

## Boundary Context

- **In scope**: 同一 `kolt.toml` を共有するプロセス間の並走に対する `kolt.lock` rewrite、`build/classes/` 書き出し、`build/kolt.kexe` / `build/*.jar` finalisation、`build/<name>-runtime.classpath` 書き出しの直列化。`~/.kolt/cache/<group>/<artifact>/<version>/*.jar` の atomic 書き出し。待機メッセージと待機上限。並行性契約の ADR 明文化。
- **Out of scope**: cross-machine locking (NFS shared `~/.kolt/`)、非 kolt consumer (IDE 自身の indexer、並行する Gradle ビルド) との調停、kolt の異なるバージョン同士が同居する環境での並走 (pre-v1 clean break 方針に従い保証しない)、daemon-vs-daemon の startup race (OS の `bind()` 排他で既解決)、CI 上の non-shared `~/.kolt/cache/` (各ジョブが独立する場合は本契約の対象外で従来通り)。
- **Adjacent expectations**: daemon socket の `bind()` は OS が `EADDRINUSE` で相互排他し、`DaemonReaper` が孤立 socket を掃除する既存挙動を本 spec も前提として利用する。グローバルキャッシュの SHA-256 post-download 検証 (現行 `TransitiveResolver.kt:236` 経路) は本契約のもとでも引き続き実行され、atomic 書き出しと直交する。CLAUDE.md の "no backward compatibility until v1.0" 方針に従い、本契約導入による `build/` レイアウト変更や `~/.kolt/cache/` 内の一時ファイル命名は migration shim を伴わない。

## Requirements

### Requirement 1: 同一プロジェクトのビルドが直列化される

**Objective:** IDE-on-save watcher と手動 `kolt build` の同時起動や、`kolt watch` と `kolt test` の併走を行う開発者として、同一 `kolt.toml` に対する複数の kolt プロセスが `kolt.lock` や `build/` を相互に破壊せず、片方が完了するまで他方が待機することを望む。realistic な開発ワークフローと CI 上の単一マシン多重ジョブで状態破壊を排するためである。

#### Acceptance Criteria

1. When 同一プロジェクトで 2 つ目以降の `kolt build` プロセスが起動し、まだ critical section に入っていない時、the kolt CLI shall プロジェクトローカルな排他ロックの取得を試みる。
2. While 別の kolt プロセスが既に同じプロジェクトの排他ロックを保持している時、the kolt CLI shall `kolt.lock` の rewrite および `build/classes/` / `build/kolt.kexe` / `build/*.jar` / `build/<name>-runtime.classpath` への書き出しを開始しない。
3. When 排他ロックの待機状態に入った時、the kolt CLI shall 「別の kolt が同プロジェクトで実行中、待機しています」相当の人間可読メッセージを stderr に 1 度出力する。
4. If 排他ロックの待機が 30 秒の上限を超えた時、then the kolt CLI shall `build/` および `kolt.lock` を破壊せずに非ゼロ終了コードで exit し、待機がタイムアウトした旨と保持側プロセスの存在を示すエラーメッセージを stderr に出力する。
5. When `kolt build` が成功または失敗で終了した時、the kolt CLI shall プロセス終了をもって排他ロックを解放し、後続のプロセスがロックを獲得できる状態にする。
6. If 直前の kolt プロセスがクラッシュ / SIGKILL / 電源断などで排他ロックを正常に解放せずに消失した時、then the kolt CLI shall 後続の `kolt` 起動を無期限にブロックせず、再取得して処理を進められる。
7. The kolt CLI shall 排他ロックを critical section (依存解決の lockfile rewrite から `build/` 成果物 finalisation まで) のみに適用し、CLI 起動・`kolt.toml` parse・help 表示・読み取り専用コマンドには適用しない。

### Requirement 2: グローバルキャッシュが書きかけ JAR を露出しない

**Objective:** 別プロジェクトの kolt や CI matrix ジョブと `~/.kolt/cache/` を共有する開発者として、別プロセスが同じ座標の JAR を書き込み中であっても自プロセスの解決経路で書きかけのバイト列が読まれず、最終 JAR が破損しないことを望む。並列ジョブの flaky failure を排し、SHA-256 検証が常に有意な対象に対して動くためである。

#### Acceptance Criteria

1. While kolt が `~/.kolt/cache/<group>/<artifact>/<version>/*.jar` のダウンロードを進行中である時、the Downloader shall 最終パスに書きかけのバイト列を露出させない。
2. When ダウンロードが完了し SHA-256 検証が成功した時、the Downloader shall 最終パスにダウンロード済み JAR を atomic に出現させ、別プロセスから見て不在または完成のいずれかしか観測されない状態を保証する。
3. If ダウンロードが HTTP エラー / I/O エラー / プロセス中断のいずれかで失敗した時、then the Downloader shall 最終パスを書き換えず、自プロセスが書いた中間ファイルを残骸として残さない。
4. While 複数の kolt プロセスが同一座標の JAR を同時にダウンロードする時、the Downloader shall 各プロセスが他プロセスの中間状態を踏まずに完走できるようにし、いずれかのプロセスによる最終パスへの確定をもって他プロセスの中間状態は破棄してよい。
5. The Downloader shall ダウンロード後に SHA-256 検証を引き続き実施し、検証失敗時は最終パスを書き換えない。

### Requirement 3: 並行性契約が ADR で明文化される

**Objective:** kolt の利用者および保守担当として、どの並走シナリオが kolt の責任範囲で safe に処理されるか、どれが unsupported かを ADR 上で参照可能にすることを望む。NFS 越し共有を避ける、IDE と CLI の同時起動を許容する、CI で `~/.kolt/cache/` を共有してよいかどうかなどの運用判断を、明文化された契約に基づいて行えるためである。

#### Acceptance Criteria

1. The kolt repository shall ADR を 1 本追加し、(a) project-local 排他ロックの対象 critical section、(b) 待機メッセージと 30 秒上限、(c) global cache の atomic 書き出し、(d) daemon socket `bind()` の OS-level 相互排他への依存、(e) cross-machine (NFS) 並走の非サポート、を記録する。
2. The ADR shall 本 spec で導入される project-local 排他ロックのファイルパスと、global cache の中間ファイル命名規則を含む。
3. The ADR shall NFS 越しに `~/.kolt/` を共有した状態での cross-machine 並走を unsupported として明示し、その状態の動作を kolt が保証しない旨を記録する。

# Requirements Document

## Introduction

#376 で daemon の wire 形式が変わったあと、 旧バージョンの daemon プロセスが UNIX socket を握ったまま生き残ると、 新しい kolt クライアントは reply を decode できず subprocess fallback に落ちる。 build 自体は成功するため一見気付かないが、 古い daemon は socket を解放しないので **以降の build も毎回 fallback** し、 daemon の高速化メリットが沈黙のうちに失われる。 ユーザーが手動で `kolt daemon stop` を打たない限り回復しない。

本機能は、 kolt の native クライアントが「daemon からの reply を解釈できない」という事象を *precondition mismatch* として扱う動作を導入する。 具体的には、 best-effort で `Message.Shutdown` を送り、 ユーザーには stderr に 1 行だけ知らせ、 当該 build は既存の subprocess fallback で完了させる。 次回の `kolt build` / `kolt test` は通常通り fresh daemon を起動して走る。 JVM コンパイラ daemon と native コンパイラ daemon の両 backend で同じ振る舞いをする。

## Boundary Context

- **In scope**:
  - daemon 由来の reply を解釈できない事象を検出するクライアント側の振る舞い（ JVM daemon backend と native daemon backend の両方）
  - 当該 daemon への best-effort な `Message.Shutdown` 送信
  - ユーザーへの stderr 1 行通知
  - 当該 build を既存の subprocess fallback 経路で完走させること
- **Out of scope**:
  - daemon 側の変更（ `Shutdown` 処理は既存。本機能では daemon 側コードを触らない）
  - 一般的な daemon ヘルス監視（ハング、 OOM、 応答遅延などは対象外）
  - 当該 build を新規起動した daemon に対して再試行すること
  - JVM 以外のクライアント経路（本機能は native client `kolt.kexe` のみ）
- **Adjacent expectations**:
  - 既存の `FallbackCompilerBackend` / `FallbackNativeCompilerBackend` は in-flight compile を本機能から従来通り受け取り、その動作は変えない
  - 次回 invocation で fresh daemon を起こすのは、 既存の daemon spawn / socket precondition ロジックに依存する。 本機能は「古い daemon に socket を手放させる契機を作る」までを担当する
  - 検出トリガーは #376 で生じた wire 互換性ギャップの *再発防止* ではなく、 *既に動いている古い daemon との遭遇時の救済* である

## Requirements

### Requirement 1: Detect daemon-reply incompatibility on the kolt native client

**Objective:** As a kolt user who has just upgraded across a daemon wire change, I want the kolt native client to recognize when a still-running old daemon replies with something the new client cannot use, so that I am not silently locked into subprocess fallback for every subsequent build.

#### Acceptance Criteria

1. If the kolt native client cannot read a complete, well-formed reply frame from a connected daemon (e.g., truncated bytes, premature connection close, transport error mid-frame), then the kolt native client shall treat the situation as a daemon-reply incompatibility.
2. If the kolt native client reads a complete reply frame but cannot deserialize its payload — whether the variant is unknown or the field shape does not match — then the kolt native client shall treat the situation as a daemon-reply incompatibility.
3. If the kolt native client deserializes a reply variant that does not correspond to the request just sent on the same connection, then the kolt native client shall treat the situation as a daemon-reply incompatibility.
4. The kolt native client shall apply this detection identically for builds that target a JVM compiler daemon and for builds that target a native compiler daemon.
5. While operating against any other daemon error class (e.g., precondition failure, hang, OOM, transport timeout outside reply read), the kolt native client shall not enter the auto-recycle path defined by this feature.

### Requirement 2: Best-effort daemon shutdown on incompatibility

**Objective:** As a kolt user, I want the offending daemon to be asked to stop as part of detection, so that the next build can spawn a fresh daemon instead of contending for the same socket.

#### Acceptance Criteria

1. When the kolt native client classifies a reply as incompatible per Requirement 1, the kolt native client shall send `Message.Shutdown` to that daemon on the still-open connection before completing the in-flight compile.
2. If the `Message.Shutdown` send itself fails (e.g., the connection has already been closed from the daemon side), then the kolt native client shall record a warn-level log entry describing the send failure and shall continue the in-flight compile.
3. The kolt native client shall not wait synchronously for the daemon to confirm or perform shutdown before returning to the build orchestration path.

### Requirement 3: Notify the user once per invocation

**Objective:** As a kolt user whose build silently fell back to subprocess, I want a short stderr line on that build so that I can see why the daemon was bypassed and trust that my next build will be daemon-fast again.

#### Acceptance Criteria

1. When the kolt native client triggers the auto-recycle path during a `kolt build` / `kolt test` invocation, the kolt native client shall write exactly one stderr line that identifies the situation as a stale daemon being recycled.
2. While a single `kolt build` / `kolt test` invocation is running, the kolt native client shall emit at most one such stderr line even if subsequent compiles within the same invocation would also encounter an incompatible reply.
3. The kolt native client shall emit the stderr line regardless of the user's verbosity setting, because the message is a user-actionable signal rather than diagnostic output.

### Requirement 4: Complete the in-flight compile via subprocess fallback

**Objective:** As a kolt user, I want the build that triggered the recycle to still finish, so that the upgrade-related daemon mismatch costs me at most a one-time slow build, not a failed build.

#### Acceptance Criteria

1. When the kolt native client triggers the auto-recycle path, the kolt native client shall complete the in-flight compile via the existing subprocess fallback path.
2. The kolt native client shall not retry the in-flight compile against a freshly spawned daemon.
3. While a single `kolt build` / `kolt test` invocation is running, the kolt native client shall enter the auto-recycle path at most once. Subsequent compiles in the same invocation that meet Requirement 1 conditions shall fall back to subprocess silently without re-sending `Message.Shutdown` or re-emitting the stderr line.

### Requirement 5: Subsequent invocations land on a fresh daemon

**Objective:** As a kolt user, I want my next `kolt build` / `kolt test` after the recycle to run on a clean daemon, so that the cost of the upgrade-related mismatch is bounded to the single build that triggered the detection.

#### Acceptance Criteria

1. When the auto-recycle path has fired in invocation A and the offending daemon has released its socket before invocation B starts, the kolt native client shall spawn a fresh daemon during invocation B as it would when no daemon were running at all.
2. If the offending daemon has not yet released its socket by the time invocation B starts, then the kolt native client shall behave as it does under the existing socket-occupied precondition. This feature shall not introduce new socket-occupied semantics.

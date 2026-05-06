package kolt.build

import kolt.infra.eprintln

// Once-per-compile-pass guard for stale-daemon stderr notice.
// Caller (BuildCommands) calls reset() at each compile pass entry; reporters call emit().
object StaleDaemonNotice {
  private var emitted: Boolean = false

  fun emit(label: String, detail: String, sink: (String) -> Unit = ::eprintln): Boolean {
    if (emitted) return false
    emitted = true
    sink(
      "warning: stale $label detected ($detail); recycling — " +
        "this build runs as subprocess, the next build will spawn a fresh daemon"
    )
    return true
  }

  fun reset() {
    emitted = false
  }
}

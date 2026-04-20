package kolt.nativedaemon

import kotlin.system.exitProcess

// Kotlin version pin for the native daemon. Must move in lockstep with the
// root `daemonKotlinVersion` in the top-level build.gradle.kts; the root
// `verifyDaemonKotlinVersion` task guards against drift. Mirrors
// `KOLT_DAEMON_KOTLIN_VERSION` in kolt-compiler-daemon/.../Main.kt.
internal const val KOLT_NATIVE_DAEMON_KOTLIN_VERSION: String = "2.3.20"

// Stub entry point. This PR lands the module scaffolding + wire protocol
// only; the daemon server (ADR 0024 §2: URLClassLoader load of
// kotlin-native-compiler-embeddable.jar, reflective K2Native.exec) arrives
// in a follow-up PR. Invoking this jar today exits with status 2 and a
// hint so nobody mistakes the scaffolding for a working daemon.
fun main(args: Array<String>) {
    System.err.println(
        "kolt-native-daemon: server implementation not yet wired (ADR 0024, issue #170). " +
            "Received ${args.size} args; refusing to start.",
    )
    exitProcess(2)
}

rootProject.name = "kolt"

// kolt-compiler-daemon is an independent Gradle build rather than a subproject.
// The native client (this build) and the JVM daemon have different targets,
// toolchains, and release cadences; keeping them decoupled at the build-system
// layer is the first step toward the distribution plan in ADR 0018. includeBuild
// preserves "./gradlew build rebuilds both" DX via the dependsOn wiring in the
// root build.gradle.kts, so there is no stale-jar risk for the dev-fallback path
// in DaemonJarResolver.
includeBuild("kolt-compiler-daemon")

// kolt-native-daemon is the sidecar JVM process for konanc compilation, per
// ADR 0024. Same includeBuild rationale as kolt-compiler-daemon: independent
// release cadence, separate classpath (kotlin-native-compiler-embeddable.jar),
// and distinct lifecycle from the JVM daemon. Wired into `./gradlew
// build/check/clean` via dependsOn in the root build.gradle.kts so the
// dev-fallback jar resolver never reads a stale fat jar across protocol
// changes.
includeBuild("kolt-native-daemon")

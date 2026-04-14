rootProject.name = "kolt"

// kolt-compiler-daemon is an independent Gradle build rather than a subproject.
// The native client (this build) and the JVM daemon have different targets,
// toolchains, and release cadences; keeping them decoupled at the build-system
// layer is the first step toward the distribution plan in ADR 0018. includeBuild
// preserves "./gradlew build rebuilds both" DX via the dependsOn wiring in the
// root build.gradle.kts, so there is no stale-jar risk for the dev-fallback path
// in DaemonJarResolver.
includeBuild("kolt-compiler-daemon")

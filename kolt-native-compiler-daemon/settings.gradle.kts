// Resolves JDK toolchains (foojay = Adoptium index) so a contributor without a
// matching local JDK can still build. See sister settings.gradle.kts in
// kolt-jvm-compiler-daemon for full rationale.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "kolt-native-compiler-daemon"

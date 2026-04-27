// Resolves JDK toolchains (foojay = Adoptium index) so a contributor without a
// matching local JDK can still build. Required because `jvmToolchain(25)` here
// is the latest LTS and is unlikely to be pre-installed; CI provides JDK 25
// via setup-java directly so this plugin is a no-op there.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "kolt-jvm-compiler-daemon"

include(":ic")

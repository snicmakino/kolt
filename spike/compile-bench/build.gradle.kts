plugins {
    kotlin("jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
}

val kotlincClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val fixtureClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    testImplementation(kotlin("test"))
    kotlincClasspath("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
    fixtureClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("bench.BenchKt")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kotlinc.classpath", kotlincClasspath.asPath)
    systemProperty("fixture.classpath", fixtureClasspath.asPath)
}

tasks.named<JavaExec>("run") {
    systemProperty("kotlinc.classpath", kotlincClasspath.asPath)
    systemProperty("fixture.classpath", fixtureClasspath.asPath)
}

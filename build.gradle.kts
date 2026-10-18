plugins {
    kotlin("jvm") version "2.4.20"
    id("com.google.devtools.ksp") version "2.3.12"
    application
}

group = "uk.m4xy.fckt"
version = "0.1.0-SNAPSHOT"

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("uk.m4xy.kastor:runtime:0.1.0-SNAPSHOT")
    ksp("uk.m4xy.kastor:processor:0.1.0-SNAPSHOT")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("uk.m4xy.fckt.MainKt")
    applicationName = "fckt"
}

tasks.test { useJUnitPlatform() }

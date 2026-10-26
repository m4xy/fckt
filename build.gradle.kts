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

// The standard library, fckt:std, goes beside fckt, in std: a library of its own, which fckt
// compiles a program with unless told --no-std, and kiln compiles once for every project.
distributions { main { contents { from("std") { into("std"); exclude("build", ".kiln") } } } }

tasks.test { useJUnitPlatform() }

// fckt --version reads it.
tasks.jar { manifest { attributes("Implementation-Version" to project.version) } }

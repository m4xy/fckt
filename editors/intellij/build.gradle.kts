import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "uk.m4xy.fckt"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Builds against an installed IntelliJ IDEA, from -PideaPath=... or ~/Applications, rather than
// downloading one.
val ideaPath = providers.gradleProperty("ideaPath")
    .orElse(System.getProperty("user.home") + "/Applications/IntelliJ IDEA.app")

dependencies {
    // The compiler: its lexer and parser find what each name is, and its checker finds errors.
    // The IDE has its own Kotlin standard library.
    implementation(project(":")) { exclude(group = "org.jetbrains.kotlin") }
    intellijPlatform {
        local(ideaPath)
        // fckt's colours default to Kotlin's, so a type with cases looks like a sealed interface.
        bundledPlugin("org.jetbrains.kotlin")
    }
}

intellijPlatform {
    projectName = "fckt"
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

dependencies {
    testImplementation(kotlin("test"))
    // The IDE's own tests, for what needs a running IDE: references and Find Usages.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    intellijPlatform { testFramework(TestFrameworkType.Platform) }
}

tasks.test { useJUnitPlatform() }

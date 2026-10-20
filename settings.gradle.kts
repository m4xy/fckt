pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "fckt"

includeBuild("../kastor")

// The IntelliJ plugin, which highlights fckt with the compiler's own parser.
include(":intellij")
project(":intellij").projectDir = file("editors/intellij")

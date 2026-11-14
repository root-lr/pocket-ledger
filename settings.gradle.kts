pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Télécharge un JDK 21 si la machine n'en a pas (toolchain Kotlin).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pocketledger"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "modules:shared-kernel",
    "modules:events",
    "modules:platform",
    "modules:crypto",
    "modules:fx",
    "modules:identity",
)

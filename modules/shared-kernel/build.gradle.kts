plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlinx.kover")
}

// Noyau partagé : Kotlin pur, aucune dépendance Spring.
dependencies {
}

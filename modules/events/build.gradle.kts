plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlinx.kover")
}

// Contrats d'événements de domaine : la frontière publique entre modules.
dependencies {
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation(libs.jsonschema.generator)
    testImplementation(libs.jsonschema.module.jackson)
}

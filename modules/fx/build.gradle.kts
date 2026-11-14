plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    implementation(project(":modules:platform"))
    implementation(libs.resilience4j.spring)
    implementation("com.github.ben-manes.caffeine:caffeine")
}

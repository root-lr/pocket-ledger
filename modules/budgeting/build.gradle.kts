plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    implementation(project(":modules:platform"))
}

kover {
    reports {
        filters { includes { packages("io.pocketledger.budgeting.domain") } }
        verify { rule { minBound(80) } }
    }
}

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlinx.kover")
    id("info.solidsoft.pitest")
}

dependencies {
    implementation(project(":modules:platform"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Tests de mutation sur le domaine (score ≥ 70 %, PRD §9).
pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(listOf("io.pocketledger.ledger.domain.*"))
    targetTests.set(listOf("io.pocketledger.ledger.domain.*"))
    mutationThreshold.set(70)
    threads.set(2)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
}

kover {
    reports {
        filters { includes { packages("io.pocketledger.ledger.domain") } }
        verify { rule { minBound(80) } }
    }
}

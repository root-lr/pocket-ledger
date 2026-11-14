plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlinx.kover")
    id("org.springframework.boot")
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation(project(":modules:shared-kernel"))
    implementation(project(":modules:events"))
    implementation(project(":modules:platform"))
    implementation(project(":modules:crypto"))
    implementation(project(":modules:fx"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:ledger"))
    implementation(project(":modules:categorization"))
    implementation(project(":modules:budgeting"))
    implementation(project(":modules:analytics"))
    implementation(project(":modules:alerting"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.springdoc.openapi)
    implementation(libs.resilience4j.spring)
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation(libs.debezium.testcontainers)
    testImplementation("org.awaitility:awaitility-kotlin")
    testImplementation(libs.archunit)
}

// Tests d'intégration (Testcontainers : PostgreSQL, Kafka, Kafka Connect + Debezium, Redis).
val integrationTest by tasks.registering(Test::class) {
    description = "Tests d'intégration sur vraie infrastructure (Docker requis)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.named("test"))
    systemProperty("testcontainers.reuse.enable", "true")
}


springBoot {
    mainClass.set("io.pocketledger.app.PocketLedgerApplicationKt")
    buildInfo()
}

jib {
    from { image = "eclipse-temurin:21-jre" }
    to {
        image = "ghcr.io/" + (System.getenv("GITHUB_REPOSITORY") ?: "pocketledger/pocketledger").lowercase()
        tags = setOf("latest", project.version.toString())
    }
    container {
        jvmFlags = listOf("-XX:MaxRAMPercentage=75", "-XX:+UseZGC")
        ports = listOf("8080")
        user = "1000:1000"
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}

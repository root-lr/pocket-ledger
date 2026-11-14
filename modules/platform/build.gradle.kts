plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlinx.kover")
}

// Infrastructure partagée : event store, outbox, exécution des processeurs d'événements, support web (idempotence, erreurs).
dependencies {
    api(project(":modules:shared-kernel"))
    api(project(":modules:events"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.postgresql:postgresql")
    implementation("com.github.ben-manes.caffeine:caffeine")
}

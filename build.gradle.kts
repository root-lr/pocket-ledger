import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.jib) apply false
    alias(libs.plugins.pitest) apply false
    alias(libs.plugins.kover)
}

allprojects {
    group = "io.pocketledger"
    version = "1.0.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    extensions.configure<DependencyManagementExtension> {
        // Le BOM Boot impose sinon sa propre version de Kotlin (kotlin-reflect incompatible avec la métadonnée 2.2).
        imports { mavenBom(SpringBootPlugin.BOM_COORDINATES) { bomProperty("kotlin.version", libs.versions.kotlin.get()) } }
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    // En local, le lint informe ; en CI (-Plint.strict=true), il bloque.
    val strictLint = providers.gradleProperty("lint.strict").isPresent

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        ignoreFailures = !strictLint
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        ignoreFailures.set(!strictLint)
    }

    // detekt 1.23.x embarque Kotlin 2.0.x : on l'empêche d'hériter de la version du projet.
    configurations.matching { it.name == "detekt" }.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
        }
    }

    dependencies {
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"(libs.kotest.assertions)
        "testImplementation"(libs.kotest.property)
        "testImplementation"(libs.mockk)
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    // Les tests d'intégration (Docker) ne tournent que via :app:integrationTest.
    tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
}

// Couverture agrégée de tous les modules
dependencies {
    subprojects.forEach { kover(it) }
}

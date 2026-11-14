package io.pocketledger.app

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Règles de dépendance (PRD §4.1) : un module métier ne dépend jamais d'un autre module métier ;
 * il passe par les événements ou par les ports de shared-kernel. Le domaine reste du Kotlin pur.
 */
class ArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("io.pocketledger")

    private val businessModules = listOf("ledger", "budgeting", "categorization", "analytics", "alerting", "identity", "fx")

    @Test
    fun `les modules métier sont indépendants les uns des autres`() {
        businessModules.forEach { module ->
            val others = businessModules.filter { it != module }.map { "io.pocketledger.$it.." }
            noClasses()
                .that()
                .resideInAPackage("io.pocketledger.$module..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(*others.toTypedArray())
                .because("le module $module communique par événements ou par les ports partagés")
                .check(classes)
        }
    }

    @Test
    fun `le domaine ne dépend pas de Spring ni de JDBC`() {
        noClasses()
            .that()
            .resideInAnyPackage("io.pocketledger.ledger.domain..", "io.pocketledger.budgeting.domain..", "io.pocketledger.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "java.sql..", "org.jooq..")
            .check(classes)
    }

    @Test
    fun `seul le module crypto manipule les clés de chiffrement`() {
        noClasses()
            .that()
            .resideOutsideOfPackages("io.pocketledger.crypto..", "io.pocketledger.identity..", "io.pocketledger.app..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.pocketledger.crypto..")
            .check(classes)
    }
}

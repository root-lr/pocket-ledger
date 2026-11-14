package io.pocketledger.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jackson.JacksonModule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Contrat des événements : le JSON Schema de chaque type est versionné dans contracts/events.
 * Un schéma absent est écrit (à committer) ; un schéma différent fait échouer le build,
 * sauf si -Dcontracts.update=true (changement voulu, accompagné d'un upcaster).
 */
class EventSchemaContractTest {
    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val generator =
        SchemaGenerator(
            SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(JacksonModule())
                .build(),
        )
    private val contractsDir = File(System.getProperty("user.dir")).resolve("../../contracts/events").normalize()
    private val update = System.getProperty("contracts.update") == "true"

    @Test
    fun `les schémas d'événements correspondent aux contrats versionnés`() {
        contractsDir.mkdirs()
        val mismatches = mutableListOf<String>()
        EventTypes.all.forEach { (name, klass) ->
            val schema = mapper.writeValueAsString(generator.generateSchema(klass.java)) + "\n"
            val file = contractsDir.resolve("$name.v${EventTypes.schemaVersions.getValue(name)}.schema.json")
            when {
                !file.exists() || update -> file.writeText(schema)
                file.readText() != schema -> mismatches += file.name
            }
        }
        mismatches shouldBe emptyList()
    }

    @Test
    fun `chaque type d'événement a un nom unique`() {
        EventTypes.all.size shouldBe DomainEvent::class.sealedSubclasses.size
    }
}

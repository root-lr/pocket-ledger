package io.pocketledger.app.config

import io.pocketledger.events.Context
import io.pocketledger.platform.processing.EventProcessor
import io.pocketledger.platform.processing.ProcessorControl
import io.pocketledger.platform.processing.ProcessorRunner
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.stereotype.Component

@Configuration
class KafkaTopicsConfig(
    private val processors: List<EventProcessor>,
    @Value("\${pocketledger.kafka.partitions:3}") private val partitions: Int,
    @Value("\${pocketledger.kafka.replicas:1}") private val replicas: Int,
) {
    /** Topics d'événements (un par contexte, alimentés par Debezium) et une DLQ par consumer group. */
    @Bean
    fun pocketledgerTopics(): KafkaAdmin.NewTopics {
        val retentionMs = { days: Long -> (days * 24 * 3600 * 1000).toString() }
        val eventTopics =
            Context.entries.map {
                TopicBuilder
                    .name(it.topic)
                    .partitions(partitions)
                    .replicas(replicas)
                    .config("retention.ms", retentionMs(if (it == Context.ALERTS) 7 else 30))
                    .build()
            }
        val dlqTopics =
            processors.map {
                TopicBuilder
                    .name(dlqTopic(it.name))
                    .partitions(1)
                    .replicas(replicas)
                    .config("retention.ms", retentionMs(14))
                    .build()
            }
        return KafkaAdmin.NewTopics(*(eventTopics + dlqTopics).toTypedArray())
    }

    companion object {
        fun dlqTopic(processor: String) = "pocketledger.$processor.dlq"
    }
}

/**
 * Un conteneur Kafka par processeur (consumer group = nom du processeur), créé à partir de la liste
 * des [EventProcessor] : les modules métier n'ont pas de dépendance à Spring Kafka.
 * Erreur : 3 essais avec backoff exponentiel, puis DLQ avec l'exception en en-tête.
 */
@Component
class EventProcessorContainers(
    processors: List<EventProcessor>,
    private val runner: ProcessorRunner,
    consumerFactory: ConsumerFactory<*, *>,
    kafkaTemplate: KafkaTemplate<*, *>,
    @Value("\${pocketledger.kafka.concurrency:3}") private val concurrency: Int,
    @Value("\${pocketledger.kafka.enabled:true}") private val enabled: Boolean,
) : SmartLifecycle,
    ProcessorControl,
    ApplicationContextAware {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val factory = consumerFactory as ConsumerFactory<String, String>

    @Suppress("UNCHECKED_CAST")
    private val template = kafkaTemplate as KafkaTemplate<Any, Any>

    private val containers: Map<String, ConcurrentMessageListenerContainer<String, String>> =
        processors.associate { it.name to container(it) }

    private fun container(processor: EventProcessor): ConcurrentMessageListenerContainer<String, String> {
        val properties =
            ContainerProperties(*processor.contexts.map { it.topic }.toTypedArray()).apply {
                setGroupId(processor.name)
                setClientId("pocketledger-${processor.name}")
                ackMode = ContainerProperties.AckMode.RECORD
                isObservationEnabled = true
                messageListener =
                    MessageListener<String, String> { record ->
                        record.value()?.let { runner.handleRecord(processor, it) }
                    }
            }
        val recoverer =
            DeadLetterPublishingRecoverer(template) { _, _ -> TopicPartition(KafkaTopicsConfig.dlqTopic(processor.name), -1) }
        val backOff =
            ExponentialBackOffWithMaxRetries(3).apply {
                initialInterval = 500
                multiplier = 2.0
            }
        return ConcurrentMessageListenerContainer(factory, properties).apply {
            setConcurrency(this@EventProcessorContainers.concurrency)
            setCommonErrorHandler(DefaultErrorHandler(recoverer, backOff))
            setBeanName("processor-${processor.name}")
        }
    }

    /** Sans contexte applicatif, les conteneurs ne trouvent pas l'ObservationRegistry (traces Kafka). */
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        containers.values.forEach { it.setApplicationContext(applicationContext) }
    }

    fun names(): Set<String> = containers.keys

    override fun pause(processorName: String) {
        containers[processorName]?.pause()
        log.info("Processeur {} en pause", processorName)
    }

    override fun resume(processorName: String) {
        containers[processorName]?.resume()
        log.info("Processeur {} repris", processorName)
    }

    override fun start() {
        if (enabled) containers.values.forEach { it.start() }
    }

    override fun stop() = containers.values.forEach { it.stop() }

    override fun isRunning(): Boolean = containers.values.any { it.isRunning }

    override fun getPhase(): Int = Int.MAX_VALUE - 100
}

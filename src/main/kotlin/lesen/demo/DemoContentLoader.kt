package lesen.demo

import kotlinx.coroutines.runBlocking
import lesen.catalog.CatalogStore
import lesen.catalog.Topic
import lesen.common.Jsonb
import lesen.common.LesenProperties
import lesen.dictionary.DictionaryService
import lesen.dictionary.LexiconEntry
import lesen.reading.ReadingDraft
import lesen.reading.ReadingPublisher
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

data class TopicSeed(
    val slug: String,
    val labels: Map<String, String>,
    val children: List<TopicSeed> = emptyList(),
)

data class CollectionSeed(
    val slug: String,
    val labels: Map<String, String>,
    val descriptions: Map<String, String>,
    val readings: List<String>,
)

// publica el contenido de demostración (docs/content/demo-content.md) con lesen.demo.enabled=true: temas, léxico,
// lecturas (a través del mismo ReadingPublisher que usará el estudio editorial) y colecciones. Idempotente: si nada
// cambió, no crea versiones nuevas
@Component
class DemoContentLoader(
    private val properties: LesenProperties,
    private val catalog: CatalogStore,
    private val dictionary: DictionaryService,
    private val publisher: ReadingPublisher,
) {
    private val log = LoggerFactory.getLogger(DemoContentLoader::class.java)
    private val resources = PathMatchingResourcePatternResolver()

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (properties.demo.enabled) runBlocking { load() }
    }

    suspend fun load() {
        val topics = Jsonb.read<List<TopicSeed>>(text("classpath:demo/topics.json"))
        topics.forEachIndexed { i, t ->
            catalog.saveTopic(Topic(t.slug, null, t.labels, i))
            t.children.forEachIndexed { j, c -> catalog.saveTopic(Topic("${t.slug}/${c.slug}", t.slug, c.labels, j)) }
        }
        for (lexicon in resources.getResources("classpath:demo/lexicon/*.jsonl").sortedBy { it.filename }) {
            val language = lexicon.filename!!.removeSuffix(".jsonl")
            val entries =
                lexicon.inputStream
                    .bufferedReader()
                    .readLines()
                    .filter { it.isNotBlank() }
                    .map { Jsonb.read<LexiconEntry>(it) }
            dictionary.import(language, entries)
            log.info("léxico {}: {} entradas", language, entries.size)
        }
        var created = 0
        for (file in resources.getResources("classpath:demo/readings/*.json").sortedBy { it.filename }) {
            val draft = Jsonb.read<ReadingDraft>(file.inputStream.bufferedReader().readText())
            val result = publisher.publish(draft)
            if (result.created) created++
            if (result.unresolved.isNotEmpty()) log.warn("{}: sin entrada {}", draft.slug, result.unresolved)
        }
        Jsonb.read<List<CollectionSeed>>(text("classpath:demo/collections.json")).forEachIndexed { i, c ->
            catalog.saveCollection(c.slug, c.labels, c.descriptions, i, c.readings)
        }
        log.info("contenido de demostración listo ({} versiones nuevas)", created)
    }

    private fun text(location: String) =
        resources
            .getResource(location)
            .inputStream
            .bufferedReader()
            .readText()
}

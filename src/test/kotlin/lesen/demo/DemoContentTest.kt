package lesen.demo

import lesen.audio.Timings
import lesen.audio.WordTiming
import lesen.common.Jsonb
import lesen.dictionary.LexiconEntry
import lesen.reading.FormResolver
import lesen.reading.ReadingAssembler
import lesen.reading.ReadingDraft
import lesen.reading.Tokenizer
import lesen.reading.UnicodeTokenizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

// el contenido de demostración se publica entero: cada lectura es válida y cada palabra tiene su entrada (la misma regla
// que tools/check_demo.py, aquí con el ensamblador de verdad)
class DemoContentTest {
    private val resources = PathMatchingResourcePatternResolver()

    private fun lexicon(language: String): List<LexiconEntry> =
        resources
            .getResource("classpath:demo/lexicon/$language.jsonl")
            .inputStream
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { Jsonb.read<LexiconEntry>(it) }

    @Test
    fun `every demo reading assembles without orphan words`() {
        val readings = resources.getResources("classpath:demo/readings/*.json").map { Jsonb.read<ReadingDraft>(it.inputStream.bufferedReader().readText()) }
        assertThat(readings).hasSizeGreaterThanOrEqualTo(10)
        for ((language, drafts) in readings.groupBy { it.language }) {
            val entries = lexicon(language)
            val keys = entries.map { it.key }.toSet()
            val forms = mutableMapOf<String, String>()
            entries.forEach { e -> (listOf(e.lemma) + e.forms).forEach { forms.putIfAbsent(Tokenizer.normalize(it), e.key) } }
            for (draft in drafts) {
                val assembled = ReadingAssembler.assemble(draft, UnicodeTokenizer(), FormResolver { forms[it] })
                assertThat(assembled.unresolved).describedAs(draft.slug).isEmpty()
                assertThat(assembled.content.words.mapNotNull { it.entry }).describedAs(draft.slug).allMatch { it in keys }
            }
        }
    }

    @Test
    fun `every entry translates into the other interface languages`() {
        for (language in listOf("de", "en")) {
            val wanted = listOf("es", "en") - language
            lexicon(language).forEach { entry ->
                entry.senses.forEach { sense -> wanted.forEach { assertThat(sense.translations[it]).describedAs("${entry.key} → $it").isNotEmpty() } }
            }
        }
    }

    @Test
    fun `every demo audio has valid timings for the text the backend assembles`() {
        val readings = resources.getResources("classpath:demo/readings/*.json").map { Jsonb.read<ReadingDraft>(it.inputStream.bufferedReader().readText()) }
        assertThat(readings).allSatisfy { draft ->
            val seed =
                Jsonb.read<AudioSeed>(
                    resources
                        .getResource("classpath:demo/audio/${draft.slug}.timings.json")
                        .inputStream
                        .bufferedReader()
                        .readText(),
                )
            val content = ReadingAssembler.assemble(draft, UnicodeTokenizer(), FormResolver { null }).content
            val timings = seed.timings.map { (token, range) -> WordTiming(token, range[0], range[1]) }
            val check = Timings.check(content, timings, seed.durationMs)
            assertThat(check.errors).describedAs(draft.slug).isEmpty()
            assertThat(check.unaligned).describedAs(draft.slug).isEmpty()
            assertThat(resources.getResource("classpath:demo/audio/${seed.file}").exists()).describedAs(seed.file).isTrue()
            assertThat(seed.voice.language).isEqualTo(draft.language)
        }
    }
}

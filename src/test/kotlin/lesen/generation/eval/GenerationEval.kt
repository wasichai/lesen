package lesen.generation.eval

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import lesen.assessment.QuestionType
import lesen.common.Jsonb
import lesen.common.LesenProperties
import lesen.demo.TopicSeed
import lesen.dictionary.LexiconEntry
import lesen.generation.ClaudeReadingWriter
import lesen.generation.GenerationContext
import lesen.generation.GenerationRequest
import lesen.generation.Generator
import lesen.generation.Outcome
import lesen.generation.TargetWord
import lesen.generation.TextKind
import lesen.generation.TokenUsage
import lesen.reading.FormResolver
import lesen.reading.ReadingAssembler
import lesen.reading.ReadingDraft
import lesen.reading.Tokenizer
import lesen.reading.UnicodeTokenizer
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// el juego de evaluación de las lecturas a medida (ADR-0013, fase 1). Corre contra la API real y cuesta dinero: nunca
// en CI, solo a mano con `./gradlew generationEval` (ANTHROPIC_API_KEY en el entorno). El perfil sale del contenido de
// demostración: lo leído, sus palabras y su diccionario. Deja en build/generation-eval/<fecha>/ el informe, los
// resultados y cada lectura en markdown para la revisión humana
data class EvalProfile(
    val id: String,
    val language: String,
    val level: String,
    val minutes: Int,
    val kind: TextKind,
    val topics: List<String>,
    val read: List<String>,
    val targets: List<String>,
    val weakTypes: List<QuestionType>,
    val prompts: Map<String, String>,
)

data class EvalSet(
    val note: String,
    val profiles: List<EvalProfile>,
)

data class EvalCase(
    val id: String,
    val profile: EvalProfile,
    val mode: String,
    val context: GenerationContext,
)

data class EvalResult(
    val id: String,
    val mode: String,
    val outcome: String,
    val reason: String?,
    val attempts: Int,
    val findings: List<List<String>>,
    val measures: Any?,
    val usage: TokenUsage,
    val costUsd: Double?,
    val seconds: Double,
    val error: String? = null,
)

// precios por millón de tokens (entrada, salida); la caché se lee al 10 % y se escribe al 125 % de la entrada
private val PRICES = mapOf("claude-opus-5-5" to (4.0 to 20.0), "claude-sonnet-5-5" to (2.0 to 10.0))

private val WORDS_PER_MINUTE = mapOf("A1" to 60, "A2" to 75, "B1" to 95, "B2" to 115, "C1" to 135, "C2" to 150)
private val INTERFACE = listOf("es", "en")

fun cost(
    model: String,
    usage: TokenUsage,
): Double? {
    val (input, output) = PRICES[model] ?: return null
    return (usage.input * input + usage.cacheRead * input * 0.1 + usage.cacheWrite * input * 1.25 + usage.output * output) / 1_000_000
}

private fun resource(path: String) = checkNotNull(EvalCase::class.java.getResource("/$path")) { "missing $path" }.readText()

class DemoKnowledge {
    val lexicon: Map<String, List<LexiconEntry>> =
        listOf("de", "en").associateWith { language ->
            resource("demo/lexicon/$language.jsonl").lines().filter { it.isNotBlank() }.map { Jsonb.read<LexiconEntry>(it) }
        }

    // como DictionaryService: el lema y sus formas, normalizados; la primera entrada gana
    val resolvers: Map<String, FormResolver> =
        lexicon.mapValues { (_, entries) ->
            val forms = mutableMapOf<String, String>()
            entries.forEach { e -> (listOf(e.lemma) + e.forms).map(Tokenizer::normalize).forEach { forms.putIfAbsent(it, e.key) } }
            FormResolver { forms[it] }
        }

    val topics: List<String> =
        Jsonb.read<List<TopicSeed>>(resource("demo/topics.json")).flatMap { t -> listOf(t.slug) + t.children.map { "${t.slug}/${it.slug}" } }

    fun reading(slug: String): ReadingDraft = Jsonb.read(resource("demo/readings/$slug.json"))

    fun context(
        profile: EvalProfile,
        prompt: String?,
        guided: Boolean,
    ): GenerationContext {
        val entries = lexicon.getValue(profile.language).associateBy { it.key }
        val resolver = resolvers.getValue(profile.language)
        // lo que la persona vio: las entradas de las lecturas que terminó, las más frecuentes primero
        val seen = mutableMapOf<String, Int>()
        val titles = mutableListOf<String>()
        for (slug in profile.read) {
            val draft = reading(slug)
            titles += draft.title
            ReadingAssembler.assemble(draft, UnicodeTokenizer(), resolver).entryOccurrences.forEach { (k, n) -> seen.merge(k, n, Int::plus) }
        }
        val known = seen.entries.sortedByDescending { it.value }.map { it.key }
        return GenerationContext(
            request =
                GenerationRequest(
                    language = profile.language,
                    level = profile.level,
                    minutes = profile.minutes,
                    kind = if (guided) profile.kind else TextKind.STORY,
                    topics = if (guided) profile.topics else emptyList(),
                    prompt = prompt,
                ),
            wordsPerMinute = WORDS_PER_MINUTE.getValue(profile.level),
            translateInto = INTERFACE - profile.language,
            questionLanguages = INTERFACE,
            topics = topics,
            targetWords = profile.targets.map { key -> entries.getValue(key).let { TargetWord(it.key, it.lemma, it.forms) } },
            dictionaryLemmas =
                lexicon
                    .getValue(profile.language)
                    .map { it.lemma }
                    .distinct()
                    .take(GenerationContext.MAX_DICTIONARY_LEMMAS),
            knownLemmas = known.mapNotNull { entries[it]?.lemma }.distinct().take(GenerationContext.MAX_KNOWN_LEMMAS),
            knownEntries = known.toSet(),
            recentTitles = titles.take(GenerationContext.MAX_RECENT_TITLES),
            weakQuestionTypes = profile.weakTypes,
        )
    }

    // cada perfil, con sus tres peticiones
    fun cases(set: EvalSet): List<EvalCase> =
        set.profiles.flatMap { p ->
            listOf(
                EvalCase("${p.id}-guided", p, "guided", context(p, null, guided = true)),
                EvalCase("${p.id}-free", p, "free", context(p, p.prompts.getValue("free"), guided = false)),
                EvalCase("${p.id}-mixed", p, "mixed", context(p, p.prompts.getValue("mixed"), guided = true)),
            )
        }
}

// argumentos: --only <prefijo> (p. ej. de-a1), --limit <n>, --parallel <n>, --effort <nivel>
fun main(args: Array<String>) {
    val options =
        args
            .toList()
            .chunked(2)
            .filter { it.size == 2 }
            .associate { (k, v) -> k.removePrefix("--") to v }
    val apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty()
    check(apiKey.isNotBlank()) { "ANTHROPIC_API_KEY is not set: the evaluation calls the real API" }
    val ai = LesenProperties.Ai(apiKey = apiKey, model = System.getenv("LESEN_AI_MODEL")?.takeIf { it.isNotBlank() } ?: "claude-opus-5-5")
    val properties = LesenProperties(ai = ai, generation = LesenProperties.Generation(effort = options["effort"] ?: "medium"))
    val knowledge = DemoKnowledge()
    val all = knowledge.cases(Jsonb.read<EvalSet>(resource("generation-eval/profiles.json")))
    val cases = all.filter { it.id.startsWith(options["only"] ?: "") }.take(options["limit"]?.toInt() ?: Int.MAX_VALUE)
    val out = File("build/generation-eval/${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())}")
    File(out, "readings").mkdirs()
    val writer = ClaudeReadingWriter(properties)
    val generator = Generator(writer, properties)
    val gate = Semaphore(options["parallel"]?.toInt() ?: 3)
    println("${cases.size} lecturas con ${ai.model} (esfuerzo ${properties.generation.effort}) → $out")
    val results =
        runBlocking {
            cases
                .map { case ->
                    async {
                        gate.withPermit {
                            val started = System.nanoTime()
                            val result =
                                try {
                                    val outcome = generator.generate(case.context, case.id, knowledge.resolvers.getValue(case.profile.language))
                                    if (outcome is Outcome.Ready) File(out, "readings/${case.id}.md").writeText(Review.markdown(case, outcome))
                                    EvalResult(
                                        id = case.id,
                                        mode = case.mode,
                                        outcome = if (outcome is Outcome.Ready) "READY" else "FAILED",
                                        reason = (outcome as? Outcome.Failed)?.reason?.name,
                                        attempts = outcome.attempts.size,
                                        findings =
                                            outcome.attempts.map { a ->
                                                a.findings.map { "${it.code}${it.ref?.let { r -> " $r" } ?: ""}: ${it.detail ?: ""}" }
                                            },
                                        measures = outcome.attempts.lastOrNull()?.measures,
                                        usage = outcome.usage,
                                        costUsd = cost(ai.model, outcome.usage),
                                        seconds = (System.nanoTime() - started) / 1e9,
                                    )
                                } catch (error: Exception) {
                                    EvalResult(
                                        case.id,
                                        case.mode,
                                        "ERROR",
                                        null,
                                        0,
                                        emptyList(),
                                        null,
                                        TokenUsage(),
                                        null,
                                        (System.nanoTime() - started) / 1e9,
                                        error.message,
                                    )
                                }
                            println("${result.id}: ${result.outcome} en ${result.attempts} intentos, ${"%.0f".format(result.seconds)} s")
                            result
                        }
                    }
                }.awaitAll()
        }
    writer.close()
    File(out, "results.json").writeText(Jsonb.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results))
    val report = Review.report(ai.model, properties.generation.effort, results)
    File(out, "report.md").writeText(report)
    println(report)
}

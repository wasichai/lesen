package lesen.dictionary

import lesen.common.Learner
import lesen.learner.ProfileService
import lesen.reading.FormResolver
import lesen.reading.FormResolvers
import lesen.reading.ReadingVersions
import lesen.reading.Tokenizer
import org.springframework.stereotype.Service
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException

// el diccionario bilingüe: consulta en contexto, búsqueda en los dos sentidos y lo personal (historial, favoritas).
// Solo muestra lo que hay en el diccionario curado: nunca inventa una entrada (ADR-0005)
@Service
class DictionaryService(
    private val store: DictionaryStore,
    private val versions: ReadingVersions,
    private val profiles: ProfileService,
) : FormResolvers {
    override suspend fun load(language: String): FormResolver {
        val forms = store.forms(language)
        return FormResolver { form -> forms[form]?.firstOrNull() }
    }

    suspend fun import(
        language: String,
        entries: List<LexiconEntry>,
    ) {
        entries.forEachIndexed { index, entry ->
            if (!entry.key.startsWith("$language:")) throw ValidationException("Invalid entry", "key", "${entry.key} must start with $language:")
            store.upsert(language, entry)
            (listOf(entry.lemma) + entry.forms).map(Tokenizer::normalize).distinct().forEach { store.addForm(language, it, entry.key, index) }
            entry.senses
                .flatMap { it.translations.entries }
                .flatMap { (lang, terms) -> terms.map { lang to Tokenizer.normalize(it) } }
                .distinct()
                .forEach { (lang, term) -> store.addTranslation(entry.key, lang, term) }
        }
    }

    suspend fun lookup(
        me: Learner,
        slug: String,
        version: Int,
        tokenRef: String,
        language: String?,
    ): LookupView {
        val ref = versions.find(slug, version) ?: throw NotFoundException("Reading version not found")
        val (sentence, token) = versions.content(ref.versionId).token(tokenRef) ?: throw NotFoundException("Token not found")
        val into = translationLanguage(me, language)
        val entry = token.entry?.let { store.entry(it) }
        val form = Tokenizer.normalize(token.text)
        val others = store.entriesForForm(ref.language, form).filter { it != token.entry }
        return LookupView(
            token = token.text,
            tokenRef = token.id,
            sentenceRef = sentence.id,
            sentence = sentence.text,
            translationLanguage = into,
            entry = entry?.let { view(me, it, into, withReadings = false) },
            inflected = entry != null && Tokenizer.normalize(entry.lemma) != form,
            alternatives = store.entries(others).map { summary(it, into, "FORM") },
        )
    }

    suspend fun entry(
        me: Learner,
        key: String,
        language: String?,
    ): EntryView {
        val entry = store.entry(key) ?: throw NotFoundException("Entry not found")
        return view(me, entry, translationLanguage(me, language), withReadings = true)
    }

    suspend fun search(
        me: Learner,
        query: String,
        studyLanguage: String?,
        language: String?,
        limit: Int,
        record: Boolean,
    ): List<EntrySummary> {
        val text = Tokenizer.normalize(query.trim())
        if (text.isEmpty()) return emptyList()
        if (text.length > 64) throw ValidationException("Too long", "q", "must be at most 64 characters")
        if (limit !in 1..30) throw ValidationException("Invalid limit", "limit", "must be between 1 and 30")
        val profile = profiles.find(me.organizationId, me.userId)
        val study = studyLanguage ?: profile?.targetLanguage ?: throw ValidationException("Missing language", "language", "required without a profile")
        val into = translationLanguage(me, language)
        val byLemma = store.searchStudy(study, text, limit)
        val byTranslation = store.searchTranslation(study, into, text, limit).filter { k -> byLemma.none { it.first == k } }
        val matched = byLemma.associate { it } + byTranslation.associateWith { "TRANSLATION" }
        val results = store.entries(matched.keys.take(limit)).map { summary(it, into, matched.getValue(it.key)) }
        if (record) store.record(me.organizationId, me.userId, query.trim(), results.firstOrNull()?.key)
        return results
    }

    // las entradas de una lectura, para el glosario de una exportación: lema, categoría y hasta tres traducciones
    suspend fun glossary(
        keys: Collection<String>,
        into: String,
    ): List<EntrySummary> = store.entries(keys).map { summary(it, into, "READING") }.sortedBy { it.lemma.lowercase() }

    suspend fun history(me: Learner) = store.history(me.organizationId, me.userId)

    suspend fun clearHistory(me: Learner) = store.clearHistory(me.organizationId, me.userId)

    suspend fun favorites(
        me: Learner,
        language: String?,
    ): List<EntrySummary> {
        val into = translationLanguage(me, language)
        return store.entries(store.favorites(me.organizationId, me.userId)).map { summary(it, into, "FAVORITE") }
    }

    suspend fun favorite(
        me: Learner,
        key: String,
        on: Boolean,
    ) {
        if (store.entry(key) == null) throw NotFoundException("Entry not found")
        store.setFavorite(me.organizationId, me.userId, key, on)
    }

    suspend fun translationLanguage(
        me: Learner,
        language: String?,
    ): String {
        if (language != null && language !in setOf("es", "en")) throw ValidationException("Invalid language", "language", "must be an interface language")
        return language ?: profiles.find(me.organizationId, me.userId)?.interfaceLanguage ?: "en"
    }

    private suspend fun view(
        me: Learner,
        entry: StoredEntry,
        into: String,
        withReadings: Boolean,
    ): EntryView {
        val relatedKeys = entry.senses.flatMap { it.related }.distinct()
        val related = store.entries(relatedKeys).associate { it.key to it.lemma }
        return EntryView(
            key = entry.key,
            language = entry.language,
            lemma = entry.lemma,
            pos = entry.pos,
            ipa = entry.ipa,
            grammar = entry.grammar,
            forms = store.formsOf(entry.key).filter { it != Tokenizer.normalize(entry.lemma) },
            senses =
                entry.senses.map { s ->
                    SenseView(
                        translations = s.translations[into].orEmpty(),
                        definition = s.definition[into],
                        examples = s.examples.map { ExampleView(it.text, it.translations[into]) },
                        synonyms = s.synonyms,
                        antonyms = s.antonyms,
                        related = s.related.mapNotNull { k -> related[k]?.let { RelatedEntry(k, it) } },
                        note = s.note[into],
                    )
                },
            favorite = store.isFavorite(me.organizationId, me.userId, entry.key),
            saved = store.isSaved(me.organizationId, me.userId, entry.key),
            readings = if (withReadings) store.readingsWith(entry.key) else emptyList(),
        )
    }

    fun summary(
        entry: StoredEntry,
        into: String,
        matchedBy: String,
    ) = EntrySummary(
        entry.key,
        entry.language,
        entry.lemma,
        entry.pos,
        entry.senses
            .flatMap { it.translations[into].orEmpty() }
            .distinct()
            .take(3),
        matchedBy,
    )
}

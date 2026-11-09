package lesen.dictionary

// una entrada tal como la escribe el léxico curado (docs/content/demo-content.md). grammar es libre por idioma
data class LexiconEntry(
    val key: String,
    val lemma: String,
    val pos: String,
    val ipa: String? = null,
    val grammar: Map<String, Any?> = emptyMap(),
    val forms: List<String> = emptyList(),
    val senses: List<Sense>,
)

data class Sense(
    val translations: Map<String, List<String>> = emptyMap(),
    val definition: Map<String, String> = emptyMap(),
    val examples: List<Example> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val note: Map<String, String> = emptyMap(),
)

data class Example(
    val text: String,
    val translations: Map<String, String> = emptyMap(),
)

// lo que ve la persona: todo en su idioma de interfaz, sin el resto de idiomas
data class EntryView(
    val key: String,
    val language: String,
    val lemma: String,
    val pos: String,
    val ipa: String?,
    val grammar: Map<String, Any?>,
    val forms: List<String>,
    val senses: List<SenseView>,
    val favorite: Boolean,
    val saved: Boolean,
    val readings: List<EntryReading>,
)

data class SenseView(
    val translations: List<String>,
    val definition: String?,
    val examples: List<ExampleView>,
    val synonyms: List<String>,
    val antonyms: List<String>,
    val related: List<RelatedEntry>,
    val note: String?,
)

data class ExampleView(
    val text: String,
    val translation: String?,
)

data class RelatedEntry(
    val key: String,
    val lemma: String,
)

data class EntryReading(
    val slug: String,
    val title: String,
    val level: String,
    val occurrences: Int,
)

data class EntrySummary(
    val key: String,
    val language: String,
    val lemma: String,
    val pos: String,
    val translations: List<String>,
    // cómo coincidió con lo buscado: LEMMA, FORM o TRANSLATION (búsqueda desde el idioma de la persona)
    val matchedBy: String,
)

// la palabra tocada en una lectura y su entrada en contexto. entry null: no hay entrada todavía, y se dice así
data class LookupView(
    val token: String,
    val tokenRef: String,
    val sentenceRef: String,
    val sentence: String,
    val translationLanguage: String,
    val entry: EntryView?,
    // la forma tocada no es el lema (geht → gehen): la interfaz lo explica
    val inflected: Boolean,
    val alternatives: List<EntrySummary>,
)

data class SearchHistoryItem(
    val query: String,
    val entryKey: String?,
)

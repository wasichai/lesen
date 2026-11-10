package lesen.editorial

import lesen.assessment.AssessmentService
import lesen.assessment.Question
import lesen.audio.AudioService
import lesen.audio.AudioSubmission
import lesen.audio.Transcripts
import lesen.audio.Voice
import lesen.common.Learner
import lesen.common.Media
import lesen.common.MediaFile
import lesen.common.MediaKind
import lesen.common.requireLength
import lesen.dictionary.DictionaryService
import lesen.dictionary.DictionaryStore
import lesen.dictionary.LexiconEntry
import lesen.language.Languages
import lesen.reading.DraftParagraph
import lesen.reading.DraftSentence
import lesen.reading.ReadingDraft
import lesen.reading.ReadingPublisher
import lesen.reading.ReadingVersions
import lesen.reading.TokenKind
import lesen.reading.Tokenizer
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import java.time.Instant
import java.util.UUID

data class NewDraft(
    // editar una lectura publicada: el borrador sale de su versión vigente
    val from: String? = null,
    // una lectura nueva
    val slug: String? = null,
    val language: String? = null,
    val level: String? = null,
    val title: String? = null,
)

data class SaveDraft(
    val revision: Int,
    val document: ReadingDraft,
    val questions: List<Question> = emptyList(),
)

data class StepNote(
    val note: String? = null,
)

data class DraftSummary(
    val id: UUID,
    val slug: String,
    val language: String,
    val level: String,
    val title: String,
    val status: DraftStatus,
    val baseVersion: Int?,
    val publishedVersion: Int?,
    val questions: Int,
    val audio: Boolean,
    val updatedBy: String,
    val updatedAt: Instant,
)

data class DraftAudioView(
    val file: String,
    val url: String,
    val format: String,
    val durationMs: Int,
    val voice: String,
    val license: String,
    val timedWords: Int,
)

data class DraftView(
    val id: UUID,
    val slug: String,
    val language: String,
    val status: DraftStatus,
    val baseVersion: Int?,
    val publishedVersion: Int?,
    val revision: Int,
    val document: ReadingDraft,
    val questions: List<Question>,
    val audio: DraftAudioView?,
    // los ids de oraciones y palabras que tendrá la versión, para citar en las preguntas: [{id, text, words[{id, text}]}]
    val sentences: List<SentenceRef>,
    val createdBy: String,
    val updatedBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val events: List<StudioEvent>,
)

data class WordRef(
    val id: String,
    val text: String,
)

data class SentenceRef(
    val id: String,
    val text: String,
    val words: List<WordRef>,
)

data class ReadingHistory(
    val slug: String,
    val versions: List<PublishedVersion>,
    val events: List<StudioEvent>,
)

data class EntryRequest(
    val language: String,
    val entry: LexiconEntry,
)

// el estudio editorial (ADR-0012): borradores que se escriben, se comprueban, se envían a revisión y se publican a través
// del mismo ReadingPublisher que la demostración, con su audio y sus preguntas contra la versión nueva
@Service
class StudioService(
    private val store: StudioStore,
    private val publisher: ReadingPublisher,
    private val versions: ReadingVersions,
    private val audio: AudioService,
    private val assessments: AssessmentService,
    private val dictionary: DictionaryService,
    private val entries: DictionaryStore,
    private val languages: Languages,
    private val media: Media,
) {
    suspend fun drafts(status: DraftStatus?): List<DraftSummary> = store.list(status).map { it.summary() }

    suspend fun draft(id: UUID): DraftView = view(find(id))

    suspend fun create(
        me: Learner,
        request: NewDraft,
    ): DraftView {
        val actor = me.user.email
        val id =
            if (request.from != null) {
                requireLength(request.from, "from", 120)
                fromReading(request.from, actor)
            } else {
                val problems = mutableListOf<FieldViolation>()
                val slug = request.slug?.trim().orEmpty()
                if (!SLUG.matches(slug) || slug.length > 120) problems += FieldViolation("slug", "must be lowercase words joined by hyphens")
                val study = languages.all().filter { it.study }.map { it.code }
                if (request.language !in study) problems += FieldViolation("language", "must be a language that can be studied")
                if (request.level !in LEVELS) problems += FieldViolation("level", "must be a CEFR level A1–C2")
                if (request.title.isNullOrBlank() || request.title.length > 200) problems += FieldViolation("title", "must have between 1 and 200 characters")
                if (problems.isNotEmpty()) throw ValidationException("Invalid draft", problems)
                if (versions.find(slug) != null) throw ConflictException("A reading with that slug already exists: edit it instead")
                val document =
                    ReadingDraft(
                        slug = slug,
                        language = request.language!!,
                        level = request.level!!,
                        topics = emptyList(),
                        title = request.title!!.trim(),
                        summary = "",
                        paragraphs = listOf(DraftParagraph(listOf(DraftSentence("")))),
                    )
                open { store.insert(slug, document.language, null, null, document, emptyList(), null, actor) }
            }
        val created = find(id)
        store.event(id, created.slug, StudioAction.CREATED, actor, version = created.baseVersion)
        return view(created)
    }

    // el borrador de una lectura publicada: su versión vigente con sus traducciones, sus preguntas y su audio
    private suspend fun fromReading(
        slug: String,
        actor: String,
    ): UUID {
        val ref = versions.find(slug) ?: throw NotFoundException("Reading not found")
        val meta = versions.meta(ref.versionId)!!
        val content = versions.content(ref.versionId)
        val resolver = publisher.resolver(ref.language)
        val into = languages.all().filter { it.interfaceLanguage && it.code != ref.language }.map { it.code }
        val sentenceTranslations = into.associateWith { lang -> versions.translations(ref.versionId, "SENTENCE", lang, null).associate { it.ref to it.text } }
        val paragraphTranslations = into.associateWith { lang -> versions.translations(ref.versionId, "PARAGRAPH", lang, null).associate { it.ref to it.text } }
        val document =
            ReadingDraft(
                slug = slug,
                language = ref.language,
                level = meta.level,
                topics = meta.topics,
                featured = meta.featured,
                cover = meta.cover,
                accent = meta.accent,
                title = meta.title,
                subtitle = meta.subtitle,
                summary = meta.summary,
                format = content.format,
                layout = content.layout,
                pages = content.pages?.let { lesen.reading.PageRules.draftPages(content.layout, it) }.orEmpty(),
                paragraphs =
                    content.paragraphs.map { paragraph ->
                        DraftParagraph(
                            paragraph.sentences.map { sentence ->
                                // las elecciones de la autora para homógrafos: lo que no es la entrada por defecto
                                val senses =
                                    sentence.tokens
                                        .filter { it.kind == TokenKind.WORD && it.entry != null && it.entry != resolver.resolve(Tokenizer.normalize(it.text)) }
                                        .associate { it.text to it.entry!! }
                                DraftSentence(
                                    sentence.text,
                                    into.mapNotNull { lang -> sentenceTranslations[lang]?.get(sentence.id)?.let { lang to it } }.toMap(),
                                    senses,
                                )
                            },
                            into.mapNotNull { lang -> paragraphTranslations[lang]?.get(paragraph.id)?.let { lang to it } }.toMap(),
                        )
                    },
            )
        val questions = assessments.questionsOf(ref.versionId).orEmpty()
        val current =
            try {
                audio.forVersion(slug, ref.number).assets.firstOrNull()
            } catch (_: NotFoundException) {
                null
            }
        val draftAudio =
            current?.let { asset ->
                DraftAudio(
                    asset.url.substringAfterLast('/'),
                    asset.format,
                    asset.durationMs,
                    asset.voice.name,
                    asset.voice.license,
                    asset.timings.map { lesen.audio.WordTiming(it[0] as String, (it[1] as Number).toInt(), (it[2] as Number).toInt()) },
                )
            }
        return open { store.insert(slug, ref.language, ref.readingId, ref.number, document, questions, draftAudio, actor) }
    }

    suspend fun save(
        me: Learner,
        id: UUID,
        request: SaveDraft,
    ): DraftView {
        val draft = find(id)
        requireStatus(draft, DraftStatus.DRAFT)
        val document = request.document.copy(slug = draft.slug, language = draft.language)
        limits(document, request.questions)
        if (!store.save(id, request.revision, document, request.questions, me.user.email)) {
            throw ConflictException("Someone saved this draft in the meantime: reload it before saving")
        }
        store.event(id, draft.slug, StudioAction.SAVED, me.user.email)
        return view(find(id))
    }

    // el audio y sus tiempos: el archivo al almacén, los tiempos casados con las palabras del borrador tal como está
    suspend fun attachAudio(
        me: Learner,
        id: UUID,
        file: ByteArray,
        timings: String,
        durationMs: Int,
        voice: String,
        license: String,
    ): DraftView {
        val draft = find(id)
        requireStatus(draft, DraftStatus.DRAFT)
        val problems = mutableListOf<FieldViolation>()
        if (durationMs !in 1..MAX_AUDIO_MS) problems += FieldViolation("durationMs", "must be between 1 ms and 2 h")
        if (voice.isBlank() || voice.length > 120) problems += FieldViolation("voice", "name who reads, 1–120 characters")
        if (license.isBlank() || license.length > 300) problems += FieldViolation("license", "say who holds the rights, 1–300 characters")
        if (timings.length > MAX_TIMINGS_CHARS) problems += FieldViolation("timings", "the timings file is too large")
        if (problems.isNotEmpty()) throw ValidationException("Invalid audio", problems)
        val content =
            try {
                publisher.preview(draft.document).content
            } catch (error: WasichaiException) {
                throw ValidationException(
                    "Fix the text before adding audio",
                    error.violations.ifEmpty { listOf(FieldViolation("document", error.message ?: "")) },
                )
            }
        val alignment =
            try {
                Transcripts.align(timings, content)
            } catch (error: IllegalArgumentException) {
                throw ValidationException("Invalid timings", "timings", error.message ?: "unreadable timings")
            }
        val stored = media.save(MediaKind.AUDIO, file, me.user.email)
        val audioDraft =
            DraftAudio(
                stored.file,
                stored.file.substringAfterLast('.'),
                durationMs,
                voice.trim(),
                license.trim(),
                alignment.timings,
                alignment.multiWordCues,
                alignment.extraWords,
            )
        if (!store.setAudio(id, audioDraft, me.user.email)) throw ConflictException("This draft is no longer being edited")
        store.event(id, draft.slug, StudioAction.AUDIO_ATTACHED, me.user.email, "${alignment.timings.size}/${content.words.size}")
        return view(find(id))
    }

    suspend fun removeAudio(
        me: Learner,
        id: UUID,
    ): DraftView {
        val draft = find(id)
        requireStatus(draft, DraftStatus.DRAFT)
        store.setAudio(id, null, me.user.email)
        store.event(id, draft.slug, StudioAction.AUDIO_REMOVED, me.user.email)
        return view(find(id))
    }

    suspend fun cover(
        me: Learner,
        file: ByteArray,
    ): MediaFile = media.save(MediaKind.COVER, file, me.user.email)

    // la ilustración de una página (ADR-0014): devuelve el archivo que el borrador pone en esa página
    suspend fun illustration(
        me: Learner,
        file: ByteArray,
    ): MediaFile = media.save(MediaKind.ILLUSTRATION, file, me.user.email)

    suspend fun check(id: UUID): CheckReport = check(find(id))

    suspend fun submit(
        me: Learner,
        id: UUID,
        note: StepNote,
    ): DraftView {
        requireLength(note.note, "note", 2000)
        val draft = find(id)
        requireStatus(draft, DraftStatus.DRAFT)
        requireReady(check(draft))
        if (!store.move(id, DraftStatus.DRAFT, DraftStatus.IN_REVIEW, me.user.email)) throw ConflictException("This draft changed in the meantime")
        store.event(id, draft.slug, StudioAction.SUBMITTED, me.user.email, note.note)
        return view(find(id))
    }

    // devolver para corregir: la nota dice qué
    suspend fun reject(
        me: Learner,
        id: UUID,
        note: StepNote,
    ): DraftView {
        requireLength(note.note, "note", 2000)
        if (note.note.isNullOrBlank()) throw ValidationException("Say what to fix", "note", "required")
        val draft = find(id)
        requireStatus(draft, DraftStatus.IN_REVIEW)
        if (!store.move(id, DraftStatus.IN_REVIEW, DraftStatus.DRAFT, me.user.email)) throw ConflictException("This draft changed in the meantime")
        store.event(id, draft.slug, StudioAction.RETURNED, me.user.email, note.note)
        return view(find(id))
    }

    // publicar: la versión (si el texto cambió), sus preguntas y su audio. Cada paso es idempotente: si uno falla, volver
    // a publicar termina el trabajo sin duplicar nada
    suspend fun publish(
        me: Learner,
        id: UUID,
        note: StepNote,
    ): DraftView {
        requireLength(note.note, "note", 2000)
        val draft = find(id)
        requireStatus(draft, DraftStatus.IN_REVIEW)
        requireReady(check(draft))
        // lo mismo que la versión vigente (aunque el documento se escribiera distinto): sin versión nueva; las preguntas y el
        // audio van a la vigente
        val current = versions.find(draft.slug)
        val assembled = publisher.preview(draft.document)
        val number =
            if (current != null && sameAsCurrent(draft.document, assembled, current)) {
                current.number
            } else {
                publisher.publish(draft.document).version
            }
        val ref = versions.find(draft.slug, number)!!
        if (draft.questions.isNotEmpty()) assessments.load(ref.versionId, draft.questions)
        draft.audio?.let { a ->
            val voice = Voice("studio-${draft.language}-${slugOf(a.voice)}", "studio", draft.language, a.voice, a.license)
            audio.attach(draft.slug, number, AudioSubmission(voice, a.file, a.format, a.durationMs, "UPLOADED", a.timings))
        }
        if (!store.move(
                id,
                DraftStatus.IN_REVIEW,
                DraftStatus.PUBLISHED,
                me.user.email,
                number,
            )
        ) {
            throw ConflictException("This draft changed in the meantime")
        }
        store.event(id, draft.slug, StudioAction.PUBLISHED, me.user.email, note.note, number)
        return view(find(id))
    }

    suspend fun discard(
        me: Learner,
        id: UUID,
    ) {
        val draft = find(id)
        if (draft.status !in setOf(DraftStatus.DRAFT, DraftStatus.IN_REVIEW)) throw ConflictException("Only an open draft can be discarded")
        if (!store.move(id, draft.status, DraftStatus.DISCARDED, me.user.email)) throw ConflictException("This draft changed in the meantime")
        store.event(id, draft.slug, StudioAction.DISCARDED, me.user.email)
    }

    suspend fun readings(): List<PublishedReading> = store.readings()

    suspend fun history(slug: String): ReadingHistory {
        requireLength(slug, "slug", 120)
        val list = store.versions(slug)
        val events = store.events(slug = slug)
        if (list.isEmpty() && events.isEmpty()) throw NotFoundException("Reading not found")
        return ReadingHistory(slug, list, events)
    }

    // una entrada nueva del diccionario curado, para las palabras que la comprobación encontró sin entrada
    suspend fun addEntry(request: EntryRequest): LexiconEntry {
        val entry = request.entry
        val problems = mutableListOf<FieldViolation>()
        if (request.language !in
            languages.all().filter { it.study }.map { it.code }
        ) {
            problems += FieldViolation("language", "must be a language that can be studied")
        }
        if (entry.lemma.isBlank() || entry.lemma.length > 80) problems += FieldViolation("lemma", "must have between 1 and 80 characters")
        if (!KEY.matches(entry.key) ||
            !entry.key.startsWith("${request.language}:")
        ) {
            problems += FieldViolation("key", "must look like ${request.language}:lemma")
        }
        if (entry.pos !in POS) problems += FieldViolation("pos", "must be one of $POS")
        if (entry.senses.isEmpty() || entry.senses.size > 12) problems += FieldViolation("senses", "between 1 and 12 senses")
        if (entry.senses.any { s ->
                s.translations.values
                    .flatten()
                    .none { it.isNotBlank() }
            }
        ) {
            problems +=
                FieldViolation("senses", "every sense needs a translation")
        }
        if (entry.forms.size > 60 ||
            entry.forms.any { it.isBlank() || it.length > 80 }
        ) {
            problems += FieldViolation("forms", "at most 60 forms of 1–80 characters")
        }
        if (problems.isNotEmpty()) throw ValidationException("Invalid entry", problems)
        if (entries.entry(entry.key) != null) throw ConflictException("That entry already exists")
        dictionary.import(request.language, listOf(entry))
        return entry
    }

    private suspend fun check(draft: StoredDraft): CheckReport {
        val into = languages.all().filter { it.interfaceLanguage && it.code != draft.language }.map { it.code }
        val fieldProblems = mutableListOf<FieldViolation>()
        val assembled =
            try {
                publisher.preview(draft.document)
            } catch (error: WasichaiException) {
                fieldProblems += error.violations.ifEmpty { listOf(FieldViolation("document", error.message ?: "")) }
                null
            }
        fieldProblems += publisher.referenceProblems(draft.document)
        val current = versions.find(draft.slug)
        val unchanged = assembled != null && current != null && sameAsCurrent(draft.document, assembled, current)
        return DraftCheck.report(
            CheckInput(
                language = draft.language,
                translateInto = into,
                fieldProblems = fieldProblems.distinct(),
                assembled = assembled,
                questions = draft.questions,
                audio = draft.audio,
                unchanged = unchanged,
                missingIllustrations =
                    lesen.reading.PageRules
                        .missingIllustrations(draft.document)
                        .map { "g${it + 1}" },
                interfaceLanguages = languages.all().filter { it.interfaceLanguage }.map { it.code },
                currentQuestions = current?.let { assessments.questionsOf(it.versionId) },
                currentAnswered = current?.let { assessments.answered(it.versionId) } ?: false,
            ),
        )
    }

    // lo que vería quien lee es lo de la versión vigente: el mismo texto con las mismas entradas, las mismas traducciones y
    // los mismos datos. No se compara la huella del documento: uno reconstruido desde la versión puede escribirse distinto
    private suspend fun sameAsCurrent(
        document: ReadingDraft,
        assembled: lesen.reading.AssembledVersion,
        current: lesen.reading.VersionRef,
    ): Boolean {
        val meta = versions.meta(current.versionId) ?: return false
        if (versions.content(current.versionId) != assembled.content) return false
        if (meta.level != document.level || meta.topics != document.topics || meta.featured != document.featured) return false
        if (meta.title != document.title.trim() || meta.subtitle != document.subtitle?.trim() || meta.summary != document.summary.trim()) return false
        if (meta.cover != document.cover || meta.accent != document.accent) return false
        // el formato, la maqueta y las páginas van en el contenido: ya se compararon

        fun key(unit: lesen.reading.TranslationUnit) = listOf(unit.unit, unit.ref, unit.language, unit.text.trim())
        val wanted =
            assembled.translations
                .filter { it.text.isNotBlank() }
                .map(::key)
                .toSet()
        val languages = wanted.map { it[2] }.toSet() + languages.all().filter { it.interfaceLanguage }.map { it.code }
        val stored =
            languages
                .flatMap { lang -> listOf("SENTENCE", "PARAGRAPH").flatMap { unit -> versions.translations(current.versionId, unit, lang, null) } }
                .filter { it.text.isNotBlank() }
                .map(::key)
                .toSet()
        return wanted == stored
    }

    private fun requireReady(report: CheckReport) {
        if (!report.ready) {
            throw ConflictException(
                "The draft has problems to fix before review or publication",
                report.errors.take(20).map { FieldViolation(it.ref ?: it.code, it.detail ?: it.code) },
            )
        }
    }

    private fun requireStatus(
        draft: StoredDraft,
        status: DraftStatus,
    ) {
        if (draft.status != status) throw ConflictException("The draft is ${draft.status}, not $status")
    }

    private fun limits(
        document: ReadingDraft,
        questions: List<Question>,
    ) {
        val problems = mutableListOf<FieldViolation>()
        if (document.paragraphs.size > 200) problems += FieldViolation("paragraphs", "at most 200 paragraphs")
        if (document.paragraphs.any { it.sentences.size > 60 }) problems += FieldViolation("paragraphs", "at most 60 sentences per paragraph")
        val texts = document.paragraphs.flatMap { p -> p.sentences.flatMap { s -> listOf(s.text) + s.translations.values } + p.translations.values }
        if (texts.any { it.length > 4000 }) problems += FieldViolation("paragraphs", "a sentence or translation is longer than 4000 characters")
        if (questions.size > 30) problems += FieldViolation("questions", "at most 30 questions")
        if (document.topics.size > 12) problems += FieldViolation("topics", "at most 12 topics")
        if (problems.isNotEmpty()) throw ValidationException("Invalid draft", problems)
    }

    private suspend fun find(id: UUID): StoredDraft = store.find(id) ?: throw NotFoundException("Draft not found")

    // un solo borrador abierto por lectura (índice único): el segundo es un conflicto
    private suspend fun open(block: suspend () -> UUID): UUID =
        try {
            block()
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("This reading already has an open draft")
        }

    private suspend fun view(draft: StoredDraft): DraftView {
        val sentences =
            try {
                publisher.preview(draft.document).content.sentences.map { s ->
                    SentenceRef(s.id, s.text, s.tokens.filter { it.kind == TokenKind.WORD }.map { WordRef(it.id, it.text) })
                }
            } catch (_: WasichaiException) {
                emptyList()
            }
        return DraftView(
            id = draft.id,
            slug = draft.slug,
            language = draft.language,
            status = draft.status,
            baseVersion = draft.baseVersion,
            publishedVersion = draft.publishedVersion,
            revision = draft.revision,
            document = draft.document,
            questions = draft.questions,
            audio =
                draft.audio?.let {
                    DraftAudioView(it.file, "${AudioService.FILES}/${it.file}", it.format, it.durationMs, it.voice, it.license, it.timings.size)
                },
            sentences = sentences,
            createdBy = draft.createdBy,
            updatedBy = draft.updatedBy,
            createdAt = draft.createdAt,
            updatedAt = draft.updatedAt,
            events = store.events(draftId = draft.id),
        )
    }

    private fun StoredDraft.summary() =
        DraftSummary(
            id,
            slug,
            language,
            document.level,
            document.title,
            status,
            baseVersion,
            publishedVersion,
            questions.size,
            audio != null,
            updatedBy,
            updatedAt,
        )

    companion object {
        private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        private val KEY = Regex("^[a-z]{2,3}:[\\p{Ll}\\p{N}'’\\- ]+(#[a-z0-9-]+)?$")
        private val LEVELS = setOf("A1", "A2", "B1", "B2", "C1", "C2")
        val POS =
            setOf(
                "noun",
                "verb",
                "adjective",
                "adverb",
                "pronoun",
                "article",
                "preposition",
                "conjunction",
                "numeral",
                "interjection",
                "particle",
                "determiner",
                "proper-noun",
                "phrase",
                "contraction",
            )
        const val MAX_AUDIO_MS = 2 * 60 * 60 * 1000
        const val MAX_TIMINGS_CHARS = 2_000_000

        fun slugOf(text: String): String =
            java.text.Normalizer
                .normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(40)
                .ifEmpty { "voz" }
    }
}

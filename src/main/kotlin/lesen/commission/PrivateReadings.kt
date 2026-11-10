package lesen.commission

import kotlinx.coroutines.reactor.awaitSingle
import lesen.assessment.Question
import lesen.assessment.QuestionType
import lesen.common.Access
import lesen.common.LesenProperties
import lesen.generation.TargetWord
import lesen.reading.ReadingDraft
import lesen.reading.ReadingFormat
import lesen.reading.ReadingOwner
import lesen.reading.ReadingPublisher
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException
import wasichai.core.common.WasichaiException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// la cuota se agotó: se dice cuándo vuelve, nunca se encola a escondidas
class QuotaExceededException(
    message: String,
) : WasichaiException(HttpStatus.TOO_MANY_REQUESTS, message)

data class QuotaView(
    // lecturas a medida por día y libros ilustrados por mes (lesen.generation.quota)
    val dailyLimit: Int,
    val dailyUsed: Int,
    val monthlyBooksLimit: Int,
    val monthlyBooksUsed: Int,
    // cuándo vuelve a haber, si ahora no hay
    val resetsAt: Instant?,
)

// lo que el perfil de escritura sabe de quien aprende (ADR-0013): las palabras que está aprendiendo, lo que ya vio y lo
// que leyó hace poco. Nada que la identifique
@Repository
class LearnerKnowledgeStore(
    private val db: DatabaseClient,
) {
    suspend fun knowledge(
        organizationId: UUID,
        userId: UUID,
        language: String,
    ): LearnerKnowledge {
        val targets =
            db
                .sql(
                    """
                    SELECT e.key, e.lemma, COALESCE((SELECT array_agg(f.form ORDER BY f.position) FROM lesen.dictionary_form f WHERE f.entry_key = e.key), '{}') AS forms
                    FROM lesen.vocabulary_entry v JOIN lesen.dictionary_entry e ON e.key = v.entry_key
                    WHERE v.organization_id = :o AND v.user_id = :u AND v.language = :l AND v.status IN ('SAVED', 'LEARNING')
                    ORDER BY v.updated_at DESC LIMIT 5
                    """.trimIndent(),
                ).bind("o", organizationId)
                .bind("u", userId)
                .bind("l", language)
                .map { row, _ ->
                    TargetWord(
                        row.get("key", String::class.java)!!,
                        row.get("lemma", String::class.java)!!,
                        (row.get("forms", Array<String>::class.java) ?: emptyArray()).toList(),
                    )
                }.all()
                .collectList()
                .awaitSingle()
        val seen =
            db
                .sql(
                    """
                    SELECT e.key, e.lemma, count(*) AS n FROM lesen.vocabulary_encounter c JOIN lesen.dictionary_entry e ON e.key = c.entry_key
                    WHERE c.organization_id = :o AND c.user_id = :u AND c.language = :l
                    GROUP BY e.key, e.lemma ORDER BY n DESC, e.lemma LIMIT 400
                    """.trimIndent(),
                ).bind("o", organizationId)
                .bind("u", userId)
                .bind("l", language)
                .map { row, _ -> row.get("key", String::class.java)!! to row.get("lemma", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        val titles =
            db
                .sql(
                    """
                    SELECT v.title FROM lesen.reading_progress p JOIN lesen.reading r ON r.id = p.reading_id
                    JOIN lesen.reading_version v ON v.reading_id = r.id AND v.number = p.version_number
                    WHERE p.organization_id = :o AND p.user_id = :u AND r.language = :l
                    ORDER BY p.updated_at DESC LIMIT 10
                    """.trimIndent(),
                ).bind("o", organizationId)
                .bind("u", userId)
                .bind("l", language)
                .map { row, _ -> row.get(0, String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        return LearnerKnowledge(
            targetWords = targets,
            knownLemmas = seen.map { it.second }.distinct(),
            knownEntries = seen.map { it.first }.toSet(),
            recentTitles = titles,
            weakQuestionTypes = emptyList<QuestionType>(),
        )
    }
}

// una lectura a medida (ADR-0016): se publica como lectura privada de quien la pidió, con sus traducciones como de
// proveedor (nadie las revisó). Se lee con el lector, el audio, las preguntas y el progreso de siempre
@Component
class PrivateSink(
    private val publisher: ReadingPublisher,
    private val knowledge: LearnerKnowledgeStore,
    private val assessments: lesen.assessment.AssessmentService,
    private val versions: lesen.reading.ReadingVersions,
) : CommissionSink {
    override val target = JobTarget.PRIVATE

    override suspend fun knowledge(job: StoredJob): LearnerKnowledge = knowledge.knowledge(job.organizationId, job.userId, job.request.language)

    override suspend fun deliver(
        job: StoredJob,
        draft: ReadingDraft,
        questions: List<Question>,
    ): Delivered {
        val published = publisher.publish(draft, ReadingOwner(job.organizationId, job.userId), source = "PROVIDER")
        if (questions.isNotEmpty()) versions.versionId(published.readingId, published.version)?.let { assessments.load(it, questions) }
        return Delivered(readingSlug = draft.slug)
    }
}

// pedir una lectura a medida (ADR-0016): quien tiene cuaderno la pide, ve sus encargos y su cuota. Lo de otra persona
// no existe (404)
@RestController
@RequestMapping("/api/lesen/generations")
class PrivateReadingController(
    private val access: Access,
    private val service: CommissionService,
    private val store: CommissionStore,
    private val properties: LesenProperties,
    private val clock: Clock,
) {
    @GetMapping("/quota")
    suspend fun quota(): QuotaView {
        val me = access.generate()
        return quota(me.organizationId, me.userId)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun create(
        @RequestBody request: CommissionRequest,
    ): CommissionView {
        val me = access.generate()
        val valid = service.validate(request)
        val quota = quota(me.organizationId, me.userId)
        if (quota.dailyUsed >= quota.dailyLimit) throw QuotaExceededException("You have used today's readings; more tomorrow")
        if (valid.format == ReadingFormat.PICTURE_BOOK && quota.monthlyBooksUsed >= quota.monthlyBooksLimit) {
            throw QuotaExceededException("You have used this month's picture books")
        }
        return CommissionView.of(service.enqueue(me.organizationId, me.userId, me.user.email, JobTarget.PRIVATE, valid))
    }

    @GetMapping
    suspend fun mine(): List<CommissionView> {
        val me = access.generate()
        return store.mine(me.organizationId, me.userId, JobTarget.PRIVATE).map(CommissionView::of)
    }

    @GetMapping("/{id}")
    suspend fun one(
        @PathVariable id: UUID,
    ): CommissionView {
        val me = access.generate()
        val job =
            store.find(id)?.takeIf { it.target == JobTarget.PRIVATE && it.organizationId == me.organizationId && it.userId == me.userId }
                ?: throw NotFoundException("Generation not found")
        return CommissionView.of(job)
    }

    private suspend fun quota(
        organizationId: UUID,
        userId: UUID,
    ): QuotaView {
        val now = clock.instant()
        val day = now.atZone(ZoneOffset.UTC).toLocalDate()
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant()
        val monthStart = day.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val limits = properties.generation.quota
        val daily = store.countSince(organizationId, userId, JobTarget.PRIVATE, null, dayStart)
        val books = store.countSince(organizationId, userId, JobTarget.PRIVATE, ReadingFormat.PICTURE_BOOK, monthStart)
        val resets =
            when {
                daily >= limits.daily -> dayStart.plus(Duration.ofDays(1))
                books >= limits.monthlyBooks ->
                    day
                        .withDayOfMonth(1)
                        .plusMonths(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                else -> null
            }
        return QuotaView(limits.daily, daily, limits.monthlyBooks, books, resets)
    }
}

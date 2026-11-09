package lesen.assessment

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.Jsonb
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID

data class StoredAssessment(
    val id: UUID,
    val versionId: UUID,
    val questions: List<Question>,
    val fingerprint: String,
)

data class StoredAttempt(
    val id: UUID,
    val assessmentId: UUID,
    val versionId: UUID,
    val slug: String,
    val version: Int,
    val language: String,
    val submittedAt: Instant,
)

data class StoredAnswer(
    val questionId: String,
    val response: AnswerInput,
    val result: AnswerResult,
    val evaluator: Evaluator?,
    val justification: String?,
    val citations: List<String>,
)

data class EvaluationJob(
    val id: UUID,
    val organizationId: UUID,
    val userId: UUID,
    val attemptId: UUID,
    val questionId: String,
    val tries: Int,
)

// respuestas ya corregidas de una persona por tipo de pregunta y nivel de la lectura
data class ComprehensionCount(
    val type: QuestionType,
    val level: String,
    val correct: Int,
    val partial: Int,
    val incorrect: Int,
) {
    val answered get() = correct + partial + incorrect
}

@Repository
class AssessmentStore(
    private val db: DatabaseClient,
) {
    // solo el primer intento de cada cuestionario: repetirlo con la solución vista no mide la comprensión. Lo pendiente
    // o sin corregir no cuenta
    suspend fun comprehension(
        o: UUID,
        u: UUID,
        language: String,
    ): List<ComprehensionCount> =
        db
            .sql(
                """
                WITH first AS (
                  SELECT DISTINCT ON (a.assessment_id) a.id, r.level
                  FROM lesen.assessment_attempt a
                  JOIN lesen.assessment s ON s.id = a.assessment_id
                  JOIN lesen.reading_version v ON v.id = s.version_id
                  JOIN lesen.reading r ON r.id = v.reading_id
                  WHERE a.organization_id = :o AND a.user_id = :u AND r.language = :l
                  ORDER BY a.assessment_id, a.submitted_at, a.id
                )
                SELECT ans.question_type, f.level,
                       count(*) FILTER (WHERE ans.result = 'CORRECT') AS correct,
                       count(*) FILTER (WHERE ans.result = 'PARTIAL') AS partial,
                       count(*) FILTER (WHERE ans.result = 'INCORRECT') AS incorrect
                FROM first f JOIN lesen.assessment_answer ans ON ans.attempt_id = f.id
                WHERE ans.result IN ('CORRECT', 'PARTIAL', 'INCORRECT')
                GROUP BY ans.question_type, f.level
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("l", language)
            .map { row, _ ->
                ComprehensionCount(
                    QuestionType.valueOf(row.get("question_type", String::class.java)!!),
                    row.get("level", String::class.java)!!,
                    row.get("correct", Number::class.java)!!.toInt(),
                    row.get("partial", Number::class.java)!!.toInt(),
                    row.get("incorrect", Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun forVersion(versionId: UUID): StoredAssessment? =
        db
            .sql("SELECT id, version_id, questions::text AS questions, fingerprint FROM lesen.assessment WHERE version_id = :v")
            .bind("v", versionId)
            .map { row, _ -> assessment(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun byId(id: UUID): StoredAssessment? =
        db
            .sql("SELECT id, version_id, questions::text AS questions, fingerprint FROM lesen.assessment WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> assessment(row) }
            .one()
            .awaitSingleOrNull()

    // las preguntas de una versión: nuevas, o reemplazadas solo si cambió el origen y nadie ha respondido todavía
    suspend fun save(
        versionId: UUID,
        questions: List<Question>,
        fingerprint: String,
    ): Boolean =
        db
            .sql(
                """
                INSERT INTO lesen.assessment (version_id, questions, fingerprint) VALUES (:v, CAST(:q AS jsonb), :f)
                ON CONFLICT (version_id) DO UPDATE SET questions = EXCLUDED.questions, fingerprint = EXCLUDED.fingerprint
                WHERE lesen.assessment.fingerprint <> EXCLUDED.fingerprint
                  AND NOT EXISTS (SELECT 1 FROM lesen.assessment_attempt a WHERE a.assessment_id = lesen.assessment.id)
                """.trimIndent(),
            ).bind("v", versionId)
            .bind("q", Jsonb.of(questions))
            .bind("f", fingerprint)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun insertAttempt(
        o: UUID,
        u: UUID,
        assessmentId: UUID,
        language: String,
    ): UUID =
        db
            .sql("INSERT INTO lesen.assessment_attempt (organization_id, user_id, assessment_id, language) VALUES (:o, :u, :a, :l) RETURNING id")
            .bind("o", o)
            .bind("u", u)
            .bind("a", assessmentId)
            .bind("l", language)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitSingle()

    suspend fun insertAnswer(
        attemptId: UUID,
        type: QuestionType,
        answer: StoredAnswer,
    ) {
        db
            .sql(
                """
                INSERT INTO lesen.assessment_answer (attempt_id, question_id, question_type, response, result, evaluator, evaluated_at)
                VALUES (:a, :q, :t, CAST(:r AS jsonb), :result, :e, CASE WHEN CAST(:e AS text) IS NULL THEN NULL ELSE now() END)
                """.trimIndent(),
            ).bind("a", attemptId)
            .bind("q", answer.questionId)
            .bind("t", type.name)
            .bind("r", Jsonb.of(answer.response))
            .bind("result", answer.result.name)
            .let { if (answer.evaluator == null) it.bindNull("e", String::class.java) else it.bind("e", answer.evaluator.name) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun attempt(
        o: UUID,
        u: UUID,
        id: UUID,
    ): StoredAttempt? =
        db
            .sql(
                """
                $ATTEMPT
                WHERE t.id = :id AND t.organization_id = :o AND t.user_id = :u
                """.trimIndent(),
            ).bind("id", id)
            .bind("o", o)
            .bind("u", u)
            .map { row, _ -> attempt(row) }
            .one()
            .awaitSingleOrNull()

    // el intento más reciente de esa persona sobre esas preguntas
    suspend fun latestAttempt(
        o: UUID,
        u: UUID,
        assessmentId: UUID,
    ): StoredAttempt? =
        db
            .sql(
                """
                $ATTEMPT
                WHERE t.assessment_id = :a AND t.organization_id = :o AND t.user_id = :u
                ORDER BY t.submitted_at DESC LIMIT 1
                """.trimIndent(),
            ).bind("a", assessmentId)
            .bind("o", o)
            .bind("u", u)
            .map { row, _ -> attempt(row) }
            .one()
            .awaitSingleOrNull()

    // sin filtro de persona: solo para la cola, que ya sabe de quién es el trabajo
    suspend fun attemptForJob(id: UUID): StoredAttempt? =
        db
            .sql("$ATTEMPT WHERE t.id = :id")
            .bind("id", id)
            .map { row, _ -> attempt(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun answers(attemptId: UUID): List<StoredAnswer> =
        db
            .sql(
                "SELECT question_id, response::text AS response, result, evaluator, justification, citations FROM lesen.assessment_answer WHERE attempt_id = :a",
            ).bind("a", attemptId)
            .map { row, _ -> answer(row) }
            .all()
            .collectList()
            .awaitSingle()

    // el resultado de una respuesta abierta: del asistente (con su justificación y citas) o de la propia persona
    suspend fun grade(
        attemptId: UUID,
        questionId: String,
        result: AnswerResult,
        evaluator: Evaluator?,
        justification: String?,
        citations: List<String>,
        onlyIf: AnswerResult,
    ): Boolean =
        db
            .sql(
                """
                UPDATE lesen.assessment_answer SET result = :r, evaluator = :e, justification = :j, citations = :c,
                  evaluated_at = CASE WHEN CAST(:e AS text) IS NULL THEN NULL ELSE now() END
                WHERE attempt_id = :a AND question_id = :q AND result = :only
                """.trimIndent(),
            ).bind("r", result.name)
            .let { if (evaluator == null) it.bindNull("e", String::class.java) else it.bind("e", evaluator.name) }
            .let { if (justification == null) it.bindNull("j", String::class.java) else it.bind("j", justification) }
            .bind("c", citations.toTypedArray())
            .bind("a", attemptId)
            .bind("q", questionId)
            .bind("only", onlyIf.name)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun enqueue(
        o: UUID,
        u: UUID,
        attemptId: UUID,
        questionId: String,
    ) {
        db
            .sql("INSERT INTO lesen.evaluation_job (organization_id, user_id, attempt_id, question_id, status) VALUES (:o, :u, :a, :q, 'QUEUED')")
            .bind("o", o)
            .bind("u", u)
            .bind("a", attemptId)
            .bind("q", questionId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // cuántas correcciones asistidas pidió esa persona en la última hora (el límite por persona)
    suspend fun recentJobs(
        o: UUID,
        u: UUID,
    ): Long =
        db
            .sql("SELECT count(*) FROM lesen.evaluation_job WHERE organization_id = :o AND user_id = :u AND created_at > now() - interval '1 hour'")
            .bind("o", o)
            .bind("u", u)
            .map { row, _ -> row.get(0, Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    // toma hasta `limit` trabajos libres (o abandonados hace más de 5 minutos) sin bloquear a otro proceso que haga lo mismo
    suspend fun claim(limit: Int): List<EvaluationJob> =
        db
            .sql(
                """
                UPDATE lesen.evaluation_job j SET status = 'RUNNING', locked_at = now(), tries = j.tries + 1
                WHERE j.id IN (
                  SELECT id FROM lesen.evaluation_job
                  WHERE (status = 'QUEUED' AND run_after <= now()) OR (status = 'RUNNING' AND locked_at < now() - interval '5 minutes')
                  ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                RETURNING j.id, j.organization_id, j.user_id, j.attempt_id, j.question_id, j.tries
                """.trimIndent(),
            ).bind("limit", limit)
            .map { row, _ ->
                EvaluationJob(
                    row.get("id", UUID::class.java)!!,
                    row.get("organization_id", UUID::class.java)!!,
                    row.get("user_id", UUID::class.java)!!,
                    row.get("attempt_id", UUID::class.java)!!,
                    row.get("question_id", String::class.java)!!,
                    row.get("tries", Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun finish(
        id: UUID,
        status: String,
        error: String?,
        retryInSeconds: Long?,
    ) {
        db
            .sql(
                """
                UPDATE lesen.evaluation_job SET status = :s, error = :err, locked_at = NULL,
                  run_after = CASE WHEN CAST(:retry AS bigint) IS NULL THEN run_after ELSE now() + make_interval(secs => CAST(:retry AS bigint)) END
                WHERE id = :id
                """.trimIndent(),
            ).bind("s", status)
            .let { if (error == null) it.bindNull("err", String::class.java) else it.bind("err", error.take(500)) }
            .let { if (retryInSeconds == null) it.bindNull("retry", java.lang.Long::class.java) else it.bind("retry", retryInSeconds) }
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun assessment(row: Readable) =
        StoredAssessment(
            row.get("id", UUID::class.java)!!,
            row.get("version_id", UUID::class.java)!!,
            Jsonb.MAPPER.readValue(row.get("questions", String::class.java)!!),
            row.get("fingerprint", String::class.java)!!,
        )

    private fun attempt(row: Readable) =
        StoredAttempt(
            row.get("id", UUID::class.java)!!,
            row.get("assessment_id", UUID::class.java)!!,
            row.get("version_id", UUID::class.java)!!,
            row.get("slug", String::class.java)!!,
            row.get("number", Number::class.java)!!.toInt(),
            row.get("language", String::class.java)!!,
            row.get("submitted_at", Instant::class.java)!!,
        )

    private fun answer(row: Readable) =
        StoredAnswer(
            row.get("question_id", String::class.java)!!,
            Jsonb.read(row.get("response", String::class.java)!!),
            AnswerResult.valueOf(row.get("result", String::class.java)!!),
            row.get("evaluator", String::class.java)?.let { Evaluator.valueOf(it) },
            row.get("justification", String::class.java),
            (row.get("citations", Array<String>::class.java) ?: emptyArray()).toList(),
        )

    companion object {
        private val ATTEMPT =
            """
            SELECT t.id, t.assessment_id, a.version_id, r.slug, v.number, t.language, t.submitted_at
            FROM lesen.assessment_attempt t
            JOIN lesen.assessment a ON a.id = t.assessment_id
            JOIN lesen.reading_version v ON v.id = a.version_id
            JOIN lesen.reading r ON r.id = v.reading_id
            """.trimIndent()
    }
}

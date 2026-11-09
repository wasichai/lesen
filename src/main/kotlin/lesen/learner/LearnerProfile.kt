package lesen.learner

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.Access
import lesen.language.Languages
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

enum class Difficulty { COMFORTABLE, BALANCED, CHALLENGING }

// objetivos de aprendizaje: una lista cerrada que la interfaz traduce; el dominio no depende de su texto
enum class Goal { TRAVEL, WORK, STUDY, CULTURE, FAMILY, FUN, EXAM }

data class LearnerProfile(
    val interfaceLanguage: String,
    val targetLanguage: String,
    val level: String,
    val levelEstimated: Boolean,
    val goals: List<Goal>,
    val topics: List<String>,
    val dailyMinutes: Int,
    val difficulty: Difficulty,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class ProfileRequest(
    val interfaceLanguage: String,
    val targetLanguage: String,
    val level: String,
    val levelEstimated: Boolean = false,
    val goals: List<Goal> = emptyList(),
    val topics: List<String> = emptyList(),
    val dailyMinutes: Int = 10,
    val difficulty: Difficulty = Difficulty.BALANCED,
)

@Repository
class Profiles(
    private val db: DatabaseClient,
) {
    suspend fun find(
        organization: UUID,
        user: UUID,
    ): LearnerProfile? =
        db
            .sql("SELECT * FROM lesen.learner_profile WHERE organization_id = :o AND user_id = :u")
            .bind("o", organization)
            .bind("u", user)
            .map { row, _ ->
                @Suppress("UNCHECKED_CAST")
                LearnerProfile(
                    row.get("interface_language", String::class.java)!!,
                    row.get("target_language", String::class.java)!!,
                    row.get("level", String::class.java)!!,
                    row.get("level_estimated", java.lang.Boolean::class.java)!!.booleanValue(),
                    (row.get("goals", Array<String>::class.java) ?: emptyArray()).mapNotNull { g -> Goal.entries.firstOrNull { it.name == g } },
                    (row.get("topics", Array<String>::class.java) ?: emptyArray()).toList(),
                    row.get("daily_minutes", Number::class.java)!!.toInt(),
                    Difficulty.valueOf(row.get("difficulty", String::class.java)!!),
                    row.get("created_at", Instant::class.java),
                    row.get("updated_at", Instant::class.java),
                )
            }.one()
            .awaitSingleOrNull()

    suspend fun save(
        organization: UUID,
        user: UUID,
        p: ProfileRequest,
    ) {
        db
            .sql(
                """
                INSERT INTO lesen.learner_profile
                  (organization_id, user_id, interface_language, target_language, level, level_estimated, goals, topics, daily_minutes, difficulty)
                VALUES (:o, :u, :ui, :target, :level, :estimated, :goals, :topics, :minutes, :difficulty)
                ON CONFLICT (organization_id, user_id) DO UPDATE SET
                  interface_language = EXCLUDED.interface_language, target_language = EXCLUDED.target_language,
                  level = EXCLUDED.level, level_estimated = EXCLUDED.level_estimated, goals = EXCLUDED.goals,
                  topics = EXCLUDED.topics, daily_minutes = EXCLUDED.daily_minutes, difficulty = EXCLUDED.difficulty,
                  updated_at = now()
                """.trimIndent(),
            ).bind("o", organization)
            .bind("u", user)
            .bind("ui", p.interfaceLanguage)
            .bind("target", p.targetLanguage)
            .bind("level", p.level)
            .bind("estimated", p.levelEstimated)
            .bind(
                "goals",
                p.goals
                    .map { it.name }
                    .distinct()
                    .toTypedArray(),
            ).bind("topics", p.topics.distinct().toTypedArray())
            .bind("minutes", p.dailyMinutes)
            .bind("difficulty", p.difficulty.name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun topicSlugs(slugs: List<String>): Set<String> =
        db
            .sql("SELECT slug FROM lesen.topic WHERE slug = ANY(:s)")
            .bind("s", slugs.toTypedArray())
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()
}

@Service
class ProfileService(
    private val profiles: Profiles,
    private val languages: Languages,
) {
    suspend fun find(
        organization: UUID,
        user: UUID,
    ): LearnerProfile? = profiles.find(organization, user)

    suspend fun save(
        organization: UUID,
        user: UUID,
        request: ProfileRequest,
    ): LearnerProfile {
        val all = languages.all().associateBy { it.code }
        val problems = mutableListOf<FieldViolation>()
        if (all[request.interfaceLanguage]?.interfaceLanguage != true) problems += FieldViolation("interfaceLanguage", "must be an interface language")
        if (all[request.targetLanguage]?.study != true) problems += FieldViolation("targetLanguage", "must be a language that can be studied")
        if (request.interfaceLanguage == request.targetLanguage) problems += FieldViolation("targetLanguage", "must differ from the interface language")
        if (languages.levels().none { it.code == request.level }) problems += FieldViolation("level", "must be a CEFR level A1–C2")
        if (request.dailyMinutes !in 5..120) problems += FieldViolation("dailyMinutes", "must be between 5 and 120")
        if (request.topics.size > 24) problems += FieldViolation("topics", "at most 24 topics")
        if (request.goals.size > Goal.entries.size) problems += FieldViolation("goals", "too many goals")
        val known = profiles.topicSlugs(request.topics)
        request.topics
            .filter { it !in known }
            .takeIf { it.isNotEmpty() }
            ?.let { problems += FieldViolation("topics", "unknown topics: $it") }
        if (problems.isNotEmpty()) throw ValidationException("Invalid profile", problems)
        profiles.save(organization, user, request)
        return profiles.find(organization, user)!!
    }
}

// el perfil de aprendizaje (onboarding): idioma de interfaz y de estudio por separado, nivel, objetivos y temas
@RestController
@RequestMapping("/api/lesen/learner/profile")
class ProfileController(
    private val access: Access,
    private val service: ProfileService,
) {
    // 404 mientras la persona no completó el onboarding: la interfaz la lleva allí
    @GetMapping
    suspend fun get(): LearnerProfile {
        val me = access.notebook()
        return service.find(me.organizationId, me.userId) ?: throw NotFoundException("No learner profile yet")
    }

    @PutMapping
    suspend fun put(
        @RequestBody request: ProfileRequest,
    ): LearnerProfile {
        val me = access.writeNotebook()
        return service.save(me.organizationId, me.userId, request)
    }
}

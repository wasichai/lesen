package lesen.language

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

data class Language(
    val code: String,
    val nativeName: String,
    val interfaceLanguage: Boolean,
    val study: Boolean,
)

data class Level(
    val code: String,
    val position: Int,
    val wordsPerMinute: Int,
)

data class LanguageCatalog(
    val languages: List<Language>,
    val levels: List<Level>,
)

// los idiomas de interfaz y de estudio, y los niveles del MCER: datos, no código (ADR-0007)
@Repository
class Languages(
    private val db: DatabaseClient,
) {
    suspend fun all(): List<Language> =
        db
            .sql("SELECT code, native_name, interface, study FROM lesen.language ORDER BY position, code")
            .map { row, _ ->
                Language(
                    row.get("code", String::class.java)!!,
                    row.get("native_name", String::class.java)!!,
                    row.get("interface", java.lang.Boolean::class.java)!!.booleanValue(),
                    row.get("study", java.lang.Boolean::class.java)!!.booleanValue(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun levels(): List<Level> =
        db
            .sql("SELECT code, position, words_per_minute FROM lesen.cefr_level ORDER BY position")
            .map { row, _ ->
                Level(
                    row.get("code", String::class.java)!!,
                    row.get("position", Number::class.java)!!.toInt(),
                    row.get("words_per_minute", Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()
}

@RestController
@RequestMapping("/api/lesen/public/languages")
class LanguageController(
    private val languages: Languages,
) {
    @GetMapping
    suspend fun catalog(): ResponseEntity<LanguageCatalog> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(LanguageCatalog(languages.all(), languages.levels()))
}

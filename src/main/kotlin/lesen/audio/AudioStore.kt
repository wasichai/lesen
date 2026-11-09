package lesen.audio

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class Voice(
    val id: String,
    val provider: String,
    val language: String,
    val name: String,
    val license: String,
)

data class AudioAsset(
    val id: UUID,
    val versionId: UUID,
    val voice: Voice,
    val file: String,
    val format: String,
    val durationMs: Int,
    val origin: String,
)

@Repository
class AudioStore(
    private val db: DatabaseClient,
) {
    suspend fun saveVoice(voice: Voice) {
        db
            .sql(
                """
                INSERT INTO lesen.audio_voice (id, provider, language, name, license) VALUES (:id, :p, :l, :n, :lic)
                ON CONFLICT (id) DO UPDATE SET provider = EXCLUDED.provider, name = EXCLUDED.name, license = EXCLUDED.license
                """.trimIndent(),
            ).bind("id", voice.id)
            .bind("p", voice.provider)
            .bind("l", voice.language)
            .bind("n", voice.name)
            .bind("lic", voice.license)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun exists(
        versionId: UUID,
        voiceId: String,
    ): Boolean =
        db
            .sql("SELECT 1 FROM lesen.audio_asset WHERE version_id = :v AND voice_id = :voice")
            .bind("v", versionId)
            .bind("voice", voiceId)
            .map { _, _ -> true }
            .all()
            .collectList()
            .awaitSingle()
            .isNotEmpty()

    suspend fun insert(
        versionId: UUID,
        voiceId: String,
        file: String,
        format: String,
        durationMs: Int,
        origin: String,
        timings: List<WordTiming>,
        check: TimingCheck,
    ): UUID {
        val id =
            db
                .sql(
                    """
                    INSERT INTO lesen.audio_asset (version_id, voice_id, file, format, duration_ms, origin)
                    VALUES (:v, :voice, :file, :format, :duration, :origin) RETURNING id
                    """.trimIndent(),
                ).bind("v", versionId)
                .bind("voice", voiceId)
                .bind("file", file)
                .bind("format", format)
                .bind("duration", durationMs)
                .bind("origin", origin)
                .map { row, _ -> row.get(0, UUID::class.java)!! }
                .one()
                .awaitSingle()
        // en bloques: una lectura larga tiene cientos de palabras y cada INSERT por separado sería un viaje a la base
        timings.chunked(CHUNK).forEach { chunk ->
            val values = chunk.indices.joinToString(", ") { "(:a, :t$it, :s$it, :e$it)" }
            var spec = db.sql("INSERT INTO lesen.word_timing (asset_id, token_id, start_ms, end_ms) VALUES $values").bind("a", id)
            chunk.forEachIndexed { i, t -> spec = spec.bind("t$i", t.tokenId).bind("s$i", t.startMs).bind("e$i", t.endMs) }
            spec.fetch().rowsUpdated().awaitSingle()
        }
        db
            .sql("INSERT INTO lesen.audio_alignment_report (asset_id, unaligned, warnings) VALUES (:a, :u, :w)")
            .bind("a", id)
            .bind("u", check.unaligned.toTypedArray())
            .bind("w", check.warnings.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return id
    }

    suspend fun assets(versionId: UUID): List<AudioAsset> =
        db
            .sql(
                """
                SELECT a.id, a.version_id, a.file, a.format, a.duration_ms, a.origin, v.id AS voice_id, v.provider, v.language, v.name, v.license
                FROM lesen.audio_asset a JOIN lesen.audio_voice v ON v.id = a.voice_id
                WHERE a.version_id = :v ORDER BY a.created_at, a.id
                """.trimIndent(),
            ).bind("v", versionId)
            .map { row, _ ->
                AudioAsset(
                    row.get("id", UUID::class.java)!!,
                    row.get("version_id", UUID::class.java)!!,
                    Voice(
                        row.get("voice_id", String::class.java)!!,
                        row.get("provider", String::class.java)!!,
                        row.get("language", String::class.java)!!,
                        row.get("name", String::class.java)!!,
                        row.get("license", String::class.java)!!,
                    ),
                    row.get("file", String::class.java)!!,
                    row.get("format", String::class.java)!!,
                    row.get("duration_ms", Number::class.java)!!.toInt(),
                    row.get("origin", String::class.java)!!,
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun timings(assetId: UUID): List<WordTiming> =
        db
            .sql("SELECT token_id, start_ms, end_ms FROM lesen.word_timing WHERE asset_id = :a ORDER BY start_ms, token_id")
            .bind("a", assetId)
            .map { row, _ ->
                WordTiming(
                    row.get(0, String::class.java)!!,
                    row.get(1, Number::class.java)!!.toInt(),
                    row.get(2, Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    companion object {
        private const val CHUNK = 200
    }
}

package lesen.audio

import lesen.reading.ReadingVersions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.util.UUID

// el audio de una versión tal como lo pide el lector: sus archivos, los tiempos por palabra y los tramos derivados
data class VersionAudio(
    val assets: List<AssetView>,
)

data class AssetView(
    val id: UUID,
    val voice: Voice,
    val url: String,
    val format: String,
    val durationMs: Int,
    val origin: String,
    // [tokenId, inicio, fin], compacto: cientos de palabras por lectura
    val timings: List<List<Any>>,
    val sentences: List<TimeRange>,
    val paragraphs: List<TimeRange>,
)

// un audio que se quiere adjuntar a una versión: lo genera el contenido de demostración y, en la fase 6, el estudio
data class AudioSubmission(
    val voice: Voice,
    val file: String,
    val format: String,
    val durationMs: Int,
    val origin: String,
    val timings: List<WordTiming>,
)

@Service
class AudioService(
    private val store: AudioStore,
    private val versions: ReadingVersions,
    private val tx: TransactionalOperator,
) {
    private val log = LoggerFactory.getLogger(AudioService::class.java)

    // adjunta un audio a una versión publicada, después de validar sus tiempos contra el texto congelado. Idempotente: si
    // esa versión ya tiene audio de esa voz, no hace nada. Devuelve false si ya estaba
    suspend fun attach(
        slug: String,
        version: Int,
        submission: AudioSubmission,
    ): Boolean {
        val ref = versions.find(slug, version) ?: throw NotFoundException("Reading version not found")
        if (store.exists(ref.versionId, submission.voice.id)) return false
        if (submission.voice.language != ref.language) {
            throw ValidationException("Invalid audio", "voice", "speaks ${submission.voice.language}, the reading is ${ref.language}")
        }
        val check = Timings.check(versions.content(ref.versionId), submission.timings, submission.durationMs)
        if (!check.valid) throw ValidationException("Invalid timings", check.errors.take(20).map { FieldViolation("timings", it) })
        tx.executeAndAwait {
            store.saveVoice(submission.voice)
            store.insert(
                ref.versionId,
                submission.voice.id,
                submission.file,
                submission.format,
                submission.durationMs,
                submission.origin,
                submission.timings,
                check,
            )
        }
        if (check.warnings.isNotEmpty()) log.warn("audio {} v{}: {}", slug, version, check.warnings)
        return true
    }

    suspend fun forVersion(
        slug: String,
        version: Int,
    ): VersionAudio {
        val ref = versions.find(slug, version) ?: throw NotFoundException("Reading version not found")
        val assets = store.assets(ref.versionId)
        if (assets.isEmpty()) throw NotFoundException("No audio for this version")
        val content = versions.content(ref.versionId)
        return VersionAudio(
            assets.map { asset ->
                val timings = store.timings(asset.id)
                AssetView(
                    id = asset.id,
                    voice = asset.voice,
                    url = "$FILES/${asset.file}",
                    format = asset.format,
                    durationMs = asset.durationMs,
                    origin = asset.origin,
                    timings = timings.map { listOf(it.tokenId, it.startMs, it.endMs) },
                    sentences = Timings.sentenceRanges(content, timings),
                    paragraphs = Timings.paragraphRanges(content, timings),
                )
            },
        )
    }

    companion object {
        const val FILES = "/api/lesen/public/audio"
    }
}

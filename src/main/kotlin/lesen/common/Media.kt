package lesen.common

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.common.ValidationException
import wasichai.files.FileStore
import java.security.MessageDigest
import java.util.UUID

// ILLUSTRATION: la ilustración de una página de un libro (ADR-0014); por sus bytes es una imagen, como una portada
enum class MediaKind {
    AUDIO,
    COVER,
    ILLUSTRATION,
    ;

    val image: Boolean get() = this != AUDIO
}

data class MediaFile(
    val file: String,
    val kind: MediaKind,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val storeKey: String,
)

// los archivos subidos desde el estudio (ADR-0012): los bytes en el almacén de Wasichai (FileStore: disco o S3), lo que
// son en lesen.media. El tipo se comprueba por los primeros bytes, no por lo que dice el navegador; nunca SVG (podría
// llevar código). El nombre público sale de la huella: subir dos veces lo mismo devuelve el mismo archivo
@Repository
class Media(
    private val db: DatabaseClient,
    private val store: FileStore,
) {
    suspend fun save(
        kind: MediaKind,
        bytes: ByteArray,
        actor: String,
    ): MediaFile {
        val limit =
            when (kind) {
                MediaKind.AUDIO -> MAX_AUDIO
                MediaKind.COVER -> MAX_COVER
                MediaKind.ILLUSTRATION -> MAX_ILLUSTRATION
            }
        if (bytes.isEmpty() || bytes.size > limit) throw ValidationException("Invalid file", "file", "must be between 1 byte and ${limit / 1_048_576} MB")
        val type = sniff(bytes) ?: throw ValidationException("Invalid file", "file", "unsupported file type")
        if (type.kind.image != kind.image) throw ValidationException("Invalid file", "file", "expected ${kind.name.lowercase()}, got ${type.contentType}")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val prefix =
            when (kind) {
                MediaKind.AUDIO -> "audio"
                MediaKind.COVER -> "cover"
                MediaKind.ILLUSTRATION -> "illustration"
            }
        val file = "$prefix-${sha.take(24)}.${type.extension}"
        find(file)?.let { return it }
        // las claves del almacén son <uuid>/<uuid>: un espacio propio de Lesen (el contenido es de toda la instalación) y
        // un id que sale de la huella
        // una ilustración puede tener los mismos bytes que una portada: su clave lleva su tipo (las de audio y portada, las de siempre)
        val seed = if (kind == MediaKind.ILLUSTRATION) "illustration:$sha" else sha
        val key = FileStore.keyOf(NAMESPACE, UUID.nameUUIDFromBytes(seed.toByteArray()))
        store.put(key, bytes, type.contentType)
        db
            .sql(
                """
                INSERT INTO lesen.media (file, kind, content_type, size_bytes, sha256, store_key, created_by)
                VALUES (:file, :kind, :type, :size, :sha, :key, :actor) ON CONFLICT (file) DO NOTHING
                """.trimIndent(),
            ).bind("file", file)
            .bind("kind", kind.name)
            .bind("type", type.contentType)
            .bind("size", bytes.size.toLong())
            .bind("sha", sha)
            .bind("key", key)
            .bind("actor", actor)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return MediaFile(file, kind, type.contentType, bytes.size.toLong(), sha, key)
    }

    suspend fun find(file: String): MediaFile? =
        db
            .sql("SELECT file, kind, content_type, size_bytes, sha256, store_key FROM lesen.media WHERE file = :file")
            .bind("file", file)
            .map { row, _ ->
                MediaFile(
                    row.get("file", String::class.java)!!,
                    MediaKind.valueOf(row.get("kind", String::class.java)!!),
                    row.get("content_type", String::class.java)!!,
                    row.get("size_bytes", Number::class.java)!!.toLong(),
                    row.get("sha256", String::class.java)!!,
                    row.get("store_key", String::class.java)!!,
                )
            }.one()
            .awaitSingleOrNull()

    // los bytes de un archivo, para servirlo con rangos (unos pocos MB: el audio de una lectura o una portada)
    suspend fun bytes(file: MediaFile): ByteArray? {
        val joined = DataBufferUtils.join(store.open(file.storeKey)).awaitFirstOrNull() ?: return null
        return try {
            ByteArray(joined.readableByteCount()).also { joined.read(it) }
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    data class Sniffed(
        val kind: MediaKind,
        val contentType: String,
        val extension: String,
    )

    companion object {
        // el «organización» de las claves de Lesen en el almacén: fijo y distinto de cualquier organización real (v3, por nombre)
        val NAMESPACE: UUID = UUID.nameUUIDFromBytes("lesen:media".toByteArray())
        const val MAX_AUDIO = 30 * 1_048_576
        const val MAX_COVER = 3 * 1_048_576
        const val MAX_ILLUSTRATION = 5 * 1_048_576

        // los formatos que entiende cualquier navegador, por su firma
        fun sniff(bytes: ByteArray): Sniffed? {
            fun starts(vararg b: Int) = bytes.size >= b.size && b.indices.take(b.size).all { bytes[it] == b[it].toByte() }

            fun at(
                offset: Int,
                text: String,
            ) = bytes.size >= offset + text.length && text.indices.all { bytes[offset + it] == text[it].code.toByte() }
            return when {
                at(0, "ID3") || (bytes.size > 1 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) ->
                    Sniffed(MediaKind.AUDIO, "audio/mpeg", "mp3")
                at(0, "OggS") -> Sniffed(MediaKind.AUDIO, "audio/ogg", "ogg")
                at(4, "ftyp") && (at(8, "M4A") || at(8, "mp4") || at(8, "isom")) -> Sniffed(MediaKind.AUDIO, "audio/mp4", "m4a")
                starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Sniffed(MediaKind.COVER, "image/png", "png")
                starts(0xFF, 0xD8, 0xFF) -> Sniffed(MediaKind.COVER, "image/jpeg", "jpg")
                at(0, "RIFF") && at(8, "WEBP") -> Sniffed(MediaKind.COVER, "image/webp", "webp")
                else -> null
            }
        }
    }
}

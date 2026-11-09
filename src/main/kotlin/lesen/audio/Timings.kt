package lesen.audio

import lesen.reading.ReadingContent
import lesen.reading.TokenKind

// el tiempo en que se pronuncia una palabra, en milisegundos desde el inicio del audio
data class WordTiming(
    val tokenId: String,
    val startMs: Int,
    val endMs: Int,
)

data class TimeRange(
    val id: String,
    val startMs: Int,
    val endMs: Int,
)

data class TimingCheck(
    // palabras de la versión sin tiempo: lo dicho y lo escrito no casan (van al informe, no se inventan)
    val unaligned: List<String>,
    // problemas que impiden publicar los tiempos
    val errors: List<String>,
    val warnings: List<String>,
) {
    val valid: Boolean get() = errors.isEmpty()
}

// la validación de unos tiempos contra el texto congelado de una versión (ADR-0008). Pura: sin base de datos
object Timings {
    // cuántas palabras pueden quedar sin tiempo antes de rechazar el audio entero: un desfase grande no es un audio usable
    const val MAX_UNALIGNED_RATIO = 0.05

    fun check(
        content: ReadingContent,
        timings: List<WordTiming>,
        durationMs: Int,
    ): TimingCheck {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val words = content.words.map { it.id }
        val wordSet = words.toSet()
        val tokens = content.sentences.flatMap { it.tokens }.associateBy { it.id }
        val byId = timings.groupBy { it.tokenId }
        byId.filterValues { it.size > 1 }.keys.forEach { errors += "$it tiene más de un tiempo" }
        timings.forEach { t ->
            when {
                t.tokenId !in tokens -> errors += "${t.tokenId} no es un token de esta versión"
                tokens.getValue(t.tokenId).kind != TokenKind.WORD -> errors += "${t.tokenId} es un signo: los signos no llevan tiempo"
            }
            if (t.startMs < 0 || t.endMs <= t.startMs) errors += "${t.tokenId}: intervalo vacío o negativo"
            if (t.endMs > durationMs) errors += "${t.tokenId} termina después del audio"
        }
        // en el orden del texto, cada palabra empieza cuando terminó la anterior o después
        val ordered = words.mapNotNull { id -> byId[id]?.firstOrNull() }
        ordered.zipWithNext().forEach { (a, b) ->
            if (b.startMs < a.endMs) errors += "${b.tokenId} empieza antes de que termine ${a.tokenId}"
        }
        val unaligned = words.filter { it !in byId }
        if (words.isNotEmpty() && unaligned.size > words.size * MAX_UNALIGNED_RATIO) {
            errors += "${unaligned.size} de ${words.size} palabras sin tiempo"
        } else if (unaligned.isNotEmpty()) {
            warnings += "${unaligned.size} palabras sin tiempo"
        }
        if (timings.any { it.tokenId in wordSet && it.endMs - it.startMs > LONG_WORD_MS }) warnings += "hay palabras de más de ${LONG_WORD_MS / 1000} s"
        return TimingCheck(unaligned, errors.distinct(), warnings)
    }

    // el tramo de cada oración y de cada párrafo: del inicio de su primera palabra con tiempo al fin de la última
    fun sentenceRanges(
        content: ReadingContent,
        timings: List<WordTiming>,
    ): List<TimeRange> {
        val byId = timings.associateBy { it.tokenId }
        return content.sentences.mapNotNull { sentence ->
            val timed = sentence.tokens.mapNotNull { byId[it.id] }
            if (timed.isEmpty()) null else TimeRange(sentence.id, timed.first().startMs, timed.last().endMs)
        }
    }

    fun paragraphRanges(
        content: ReadingContent,
        timings: List<WordTiming>,
    ): List<TimeRange> {
        val sentences = sentenceRanges(content, timings).associateBy { it.id }
        return content.paragraphs.mapNotNull { paragraph ->
            val timed = paragraph.sentences.mapNotNull { sentences[it.id] }
            if (timed.isEmpty()) null else TimeRange(paragraph.id, timed.first().startMs, timed.last().endMs)
        }
    }

    private const val LONG_WORD_MS = 4000
}

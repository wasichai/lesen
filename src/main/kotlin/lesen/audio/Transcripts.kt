package lesen.audio

import lesen.common.Jsonb
import lesen.reading.ReadingContent
import lesen.reading.Tokenizer

// un tramo con tiempo de un archivo de subtítulos: lo dicho entre start y end
data class Cue(
    val text: String,
    val startMs: Int,
    val endMs: Int,
)

data class Alignment(
    val timings: List<WordTiming>,
    // tramos con varias palabras: no se reparte su tiempo entre ellas (sería inventarlo), sus palabras quedan sin tiempo
    val multiWordCues: Int,
    // palabras dichas que no están en el texto
    val extraWords: Int,
)

// los tiempos que sube una persona desde el estudio (ADR-0012): un WebVTT o SRT con una palabra por tramo (lo que dan
// los alineadores forzados y la transcripción con marcas por palabra) o el JSON de tools/generate_audio.py. Lo dicho se
// casa con lo escrito por la subsecuencia común más larga de las formas normalizadas: lo que no casa queda sin tiempo y lo
// valida Timings.check, nunca se estima
object Transcripts {
    private val TIME = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})")
    private val BOM = Char(0xFEFF)
    private val ARROW = Regex("^\\s*(\\S+)\\s*-->\\s*(\\S+)")

    fun align(
        text: String,
        content: ReadingContent,
    ): Alignment {
        val trimmed = text.trimStart(BOM).trim()
        if (trimmed.startsWith("{")) return Alignment(fromJson(trimmed, content), 0, 0)
        return align(cues(trimmed), content)
    }

    // el JSON de tools/generate_audio.py: {timings: {tokenId: [inicio, fin]}}
    private fun fromJson(
        text: String,
        content: ReadingContent,
    ): List<WordTiming> {
        val parsed =
            try {
                Jsonb.read<Map<String, Any?>>(text)
            } catch (_: Exception) {
                throw IllegalArgumentException("the timings file is not valid JSON")
            }

        @Suppress("UNCHECKED_CAST")
        val timings = parsed["timings"] as? Map<String, List<Number>> ?: throw IllegalArgumentException("the JSON has no timings")
        val order = content.words.mapIndexed { i, token -> token.id to i }.toMap()
        return timings
            .map { (token, range) ->
                if (range.size != 2) throw IllegalArgumentException("$token needs [start, end]")
                WordTiming(token, range[0].toInt(), range[1].toInt())
            }.sortedBy { order[it.tokenId] ?: Int.MAX_VALUE }
    }

    fun cues(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val match = ARROW.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val start = millis(match.groupValues[1])
            val end = millis(match.groupValues[2])
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && lines[i].isNotBlank()) body += lines[i++]
            if (start != null && end != null) {
                // sin etiquetas de estilo (<c>, <00:00:01.000>) ni identificadores de hablante
                cues += Cue(body.joinToString(" ").replace(Regex("<[^>]*>"), " ").trim(), start, end)
            }
        }
        if (cues.isEmpty()) throw IllegalArgumentException("no timed cues found: upload WebVTT, SRT or the JSON of generate_audio.py")
        return cues
    }

    fun align(
        cues: List<Cue>,
        content: ReadingContent,
    ): Alignment {
        // las palabras dichas, en orden: solo las de tramos de una palabra llevan tiempo propio
        data class Spoken(
            val form: String,
            val cue: Cue?,
        )
        var multi = 0
        val spoken =
            cues.flatMap { cue ->
                val forms =
                    Tokenizer.WORD_PATTERN
                        .findAll(cue.text)
                        .map { Tokenizer.normalize(it.value) }
                        .toList()
                if (forms.size > 1) multi++
                forms.map { Spoken(it, if (forms.size == 1) cue else null) }
            }
        val written = content.words
        val a = written.map { Tokenizer.normalize(it.text) }
        val b = spoken.map { it.form }
        // subsecuencia común más larga: cientos de palabras por lectura, cabe de sobra
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (x in a.indices.reversed()) {
            for (y in b.indices.reversed()) {
                lcs[x][y] = if (a[x] == b[y]) lcs[x + 1][y + 1] + 1 else maxOf(lcs[x + 1][y], lcs[x][y + 1])
            }
        }
        val timings = mutableListOf<WordTiming>()
        var x = 0
        var y = 0
        var matched = 0
        while (x < a.size && y < b.size) {
            when {
                a[x] == b[y] -> {
                    spoken[y].cue?.let { timings += WordTiming(written[x].id, it.startMs, it.endMs) }
                    matched++
                    x++
                    y++
                }
                lcs[x + 1][y] >= lcs[x][y + 1] -> x++
                else -> y++
            }
        }
        return Alignment(timings, multi, b.size - matched)
    }

    private fun millis(value: String): Int? {
        val m = TIME.matchEntire(value) ?: return null
        val hours = m.groupValues[1].ifEmpty { "0" }.toInt()
        return ((hours * 60 + m.groupValues[2].toInt()) * 60 + m.groupValues[3].toInt()) * 1000 + m.groupValues[4].toInt()
    }
}

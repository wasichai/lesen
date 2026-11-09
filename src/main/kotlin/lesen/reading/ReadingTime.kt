package lesen.reading

import kotlin.math.ceil
import kotlin.math.max

// minutos estimados de lectura atenta: palabras entre las palabras por minuto del nivel (lesen.cefr_level), hacia
// arriba y nunca menos de uno. Es una estimación para elegir qué leer, no una medida de la persona
object ReadingTime {
    fun minutes(
        words: Int,
        wordsPerMinute: Int,
    ): Int {
        require(wordsPerMinute > 0) { "wordsPerMinute must be positive" }
        return max(1, ceil(words.toDouble() / wordsPerMinute).toInt())
    }

    // minutos que faltan desde un porcentaje leído
    fun remaining(
        minutes: Int,
        percent: Int,
    ): Int = max(0, ceil(minutes * (100 - percent.coerceIn(0, 100)) / 100.0).toInt())
}

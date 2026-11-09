package lesen.library

import java.time.Instant

data class ProgressState(
    val version: Int,
    val position: String?,
    val percent: Int,
    val secondsRead: Int,
    val startedAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

// lo que informa el lector: dónde está la persona ahora, cuánto lleva y el tiempo total que el lector ha contado
data class ProgressReport(
    val version: Int,
    val position: String?,
    val percent: Int,
    val secondsRead: Int,
)

// cómo se combina un informe con lo guardado. Idempotente: el mismo informe dos veces deja lo mismo
// - posición: la última que se informó (reanudar donde la persona estaba, aunque haya vuelto atrás para releer)
// - porcentaje: lo más lejos que llegó en esa versión; con otra versión, empieza de nuevo desde lo informado
// - segundos: el total más alto que se informó (el lector manda el total, no un incremento, para que repetir no sume)
// - terminada: lo sigue estando; terminar la marca al 100 %
object ProgressPolicy {
    const val MAX_SECONDS = 24 * 60 * 60

    fun merge(
        current: ProgressState?,
        report: ProgressReport,
        now: Instant,
    ): ProgressState {
        val percent = report.percent.coerceIn(0, 100)
        val seconds = report.secondsRead.coerceIn(0, MAX_SECONDS)
        if (current == null) return ProgressState(report.version, report.position, percent, seconds, now, now, null)
        val sameVersion = current.version == report.version
        return current.copy(
            version = report.version,
            position = report.position ?: current.position,
            percent = if (sameVersion) maxOf(current.percent, percent) else percent,
            secondsRead = maxOf(current.secondsRead, seconds),
            updatedAt = now,
        )
    }

    fun complete(
        current: ProgressState?,
        version: Int,
        secondsRead: Int,
        now: Instant,
    ): ProgressState {
        val merged = merge(current, ProgressReport(version, current?.position, 100, secondsRead), now)
        return merged.copy(percent = 100, completedAt = current?.completedAt ?: now)
    }
}

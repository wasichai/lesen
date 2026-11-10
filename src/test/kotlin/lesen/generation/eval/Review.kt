package lesen.generation.eval

import lesen.generation.Outcome
import kotlin.math.ceil

// lo que leen las personas: el informe con los criterios de salida de la fase 1 y cada lectura con su hoja de revisión
object Review {
    fun markdown(
        case: EvalCase,
        outcome: Outcome.Ready,
    ): String =
        buildString {
            val c = case.context
            val draft = outcome.draft
            appendLine("# ${draft.title}")
            appendLine()
            appendLine("`${case.id}` · ${c.request.language} ${c.request.level} · ${c.request.minutes} min · ${c.request.kind.name.lowercase()} · ${case.mode}")
            c.request.prompt?.let { appendLine("\nPetición: «$it»") }
            if (c.targetWords.isNotEmpty()) appendLine("\nPalabras objetivo: ${c.targetWords.joinToString(", ") { it.lemma }}")
            appendLine("\nTemas: ${draft.topics.joinToString(", ")} · intentos: ${outcome.attempts.size}")
            appendLine()
            draft.subtitle?.let { appendLine("_${it}_\n") }
            appendLine("> ${draft.summary}")
            appendLine()
            draft.paragraphs.forEachIndexed { p, paragraph ->
                appendLine(paragraph.sentences.joinToString(" ") { it.text })
                appendLine()
                paragraph.sentences.forEachIndexed { s, sentence ->
                    sentence.translations.forEach { (language, text) -> appendLine("- p${p + 1}s${s + 1} [$language] $text") }
                }
                appendLine()
            }
            appendLine("## Preguntas")
            appendLine()
            outcome.generated.questions.forEach { q ->
                appendLine("**${q.id} · ${q.type}** (${q.refs.joinToString(", ")}) ${q.prompt["es"]}")
                q.options.forEach { appendLine("- ${it.id}${if (it.id in q.correct) " ✓" else ""}: ${it.text["es"]}") }
                q.answer?.let { appendLine("- respuesta: $it") }
                q.word?.let { appendLine("- palabra: $it") }
                if (q.blanks.isNotEmpty()) appendLine("- huecos: ${q.blanks.joinToString(", ")}")
                appendLine("- explicación: ${q.explanation["es"]}")
                appendLine()
            }
            appendLine("## Revisión")
            appendLine()
            appendLine("Revisor/a: · Nativo/a: sí / no")
            appendLine()
            appendLine("| Criterio | 1–5 | Notas |")
            appendLine("| --- | --- | --- |")
            appendLine("| Naturalidad del idioma | | |")
            appendLine("| Ajuste al nivel ${c.request.level} | | |")
            appendLine("| Interés y coherencia | | |")
            appendLine("| Traducciones | | |")
            appendLine("| Preguntas | | |")
            appendLine()
            appendLine("Errores de idioma (cita la oración):")
        }

    fun report(
        model: String,
        effort: String,
        results: List<EvalResult>,
    ): String =
        buildString {
            val ready = results.filter { it.outcome == "READY" }
            val atMostOne = ready.count { it.attempts <= 2 }
            appendLine("# Evaluación de lecturas a medida")
            appendLine()
            appendLine("Modelo `$model`, esfuerzo `$effort`, ${results.size} lecturas.")
            appendLine()
            appendLine("| Medida | Valor | Criterio de la fase 1 |")
            appendLine("| --- | --- | --- |")
            appendLine("| Listas | ${ready.size} (${pct(ready.size, results.size)}) | |")
            appendLine("| Listas con 1 corrección o menos | $atMostOne (${pct(atMostOne, results.size)}) | ≥ 90 % |")
            appendLine("| Negativas | ${results.count { it.reason == "REFUSED" }} | |")
            appendLine("| Errores de la API | ${results.count { it.outcome == "ERROR" }} | |")
            val costs = results.mapNotNull { it.costUsd }
            if (costs.isNotEmpty()) {
                appendLine("| Coste medio por petición | ${"%.3f".format(costs.average())} USD | medido |")
                appendLine("| Coste total | ${"%.2f".format(costs.sum())} USD | |")
            }
            val seconds = results.map { it.seconds }.sorted()
            if (seconds.isNotEmpty()) {
                appendLine(
                    "| Tiempo p50 / p90 | ${"%.0f".format(quantile(seconds, 0.5))} / ${"%.0f".format(quantile(seconds, 0.9))} s | |",
                )
            }
            appendLine()
            appendLine("## Fallos por comprobación (en cualquier intento)")
            appendLine()
            results
                .flatMap { r ->
                    r.findings
                        .flatten()
                        .map { it.substringBefore(" ").substringBefore(":") }
                        .distinct()
                }.groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .forEach { (code, n) -> appendLine("- $code: $n lecturas") }
            appendLine()
            appendLine("## Por lectura")
            appendLine()
            appendLine("| Lectura | Resultado | Intentos | Coste | s | Último fallo |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            results.forEach { r ->
                val last = r.findings.lastOrNull()?.firstOrNull() ?: r.error ?: ""
                appendLine(
                    "| ${r.id} | ${r.reason ?: r.outcome} | ${r.attempts} | ${r.costUsd?.let {
                        "%.3f".format(
                            it,
                        )
                    } ?: ""} | ${"%.0f".format(r.seconds)} | ${last.take(80).replace("|", "/")} |",
                )
            }
            appendLine()
            appendLine("La revisión humana (20 lecturas, ≥ 4 sobre 5 en naturalidad y nivel) se hace en `readings/`.")
        }

    private fun pct(
        n: Int,
        total: Int,
    ) = if (total == 0) "—" else "${n * 100 / total} %"

    private fun quantile(
        sorted: List<Double>,
        q: Double,
    ) = sorted[(ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
}

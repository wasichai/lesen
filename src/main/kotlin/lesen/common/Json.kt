package lesen.common

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

// jsonb ↔ objetos de Kotlin. Las columnas jsonb viajan como texto: se escriben con CAST(:x AS jsonb) y se leen con
// col::text, así nada depende del códec del driver de PostgreSQL
object Jsonb {
    val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    fun of(value: Any): String = MAPPER.writeValueAsString(value)

    inline fun <reified T> read(text: String): T = MAPPER.readValue(text)
}

// una cadena del idioma de la persona, con el respaldo en otro idioma si falta (nunca vacía si hay alguna)
fun Map<String, String>.localized(language: String): String = this[language] ?: this["en"] ?: this.values.firstOrNull() ?: ""

fun requireLength(
    value: String?,
    field: String,
    max: Int,
) {
    if (value != null && value.length > max) {
        throw wasichai.core.common.ValidationException("Too long", field, "must be at most $max characters")
    }
}

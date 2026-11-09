package lesen.dictionary

import lesen.common.Access
import lesen.common.requireLength
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/lesen/dictionary")
class DictionaryController(
    private val access: Access,
    private val service: DictionaryService,
) {
    // la palabra de una lectura, en su contexto
    @GetMapping("/lookup")
    suspend fun lookup(
        @RequestParam reading: String,
        @RequestParam version: Int,
        @RequestParam token: String,
        @RequestParam language: String?,
    ): LookupView {
        requireLength(token, "token", 32)
        return service.lookup(access.reader(), reading, version, token, language)
    }

    // en los dos sentidos: por lema o forma del idioma de estudio, o por traducción en el idioma de la persona.
    // record=true guarda la búsqueda en el historial (al confirmar, no en cada sugerencia)
    @GetMapping("/search")
    suspend fun search(
        @RequestParam q: String,
        @RequestParam study: String?,
        @RequestParam language: String?,
        @RequestParam limit: Int?,
        @RequestParam record: Boolean?,
    ): List<EntrySummary> {
        requireLength(q, "q", 64)
        val me = if (record == true) access.writeNotebook() else access.reader()
        return service.search(me, q, study, language, limit ?: 10, record == true)
    }

    @GetMapping("/entries/{key}")
    suspend fun entry(
        @PathVariable key: String,
        @RequestParam language: String?,
    ): EntryView {
        requireLength(key, "key", 120)
        return service.entry(access.reader(), key, language)
    }

    @GetMapping("/history")
    suspend fun history() = service.history(access.notebook())

    @DeleteMapping("/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun clearHistory() = service.clearHistory(access.writeNotebook())

    @GetMapping("/favorites")
    suspend fun favorites(
        @RequestParam language: String?,
    ) = service.favorites(access.notebook(), language)

    @PutMapping("/favorites/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun favorite(
        @PathVariable key: String,
    ) = service.favorite(access.writeNotebook(), key, true)

    @DeleteMapping("/favorites/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unfavorite(
        @PathVariable key: String,
    ) = service.favorite(access.writeNotebook(), key, false)
}

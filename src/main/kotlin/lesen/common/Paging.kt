package lesen.common

import wasichai.core.common.ValidationException

// la página de las listas de Lesen, con la forma de las de elearning-backend
data class Page<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class PageRequest(
    val page: Int,
    val size: Int,
) {
    val offset: Long get() = page.toLong() * size

    fun <T> of(
        content: List<T>,
        total: Long,
    ): Page<T> = Page(content, page, size, total, if (total == 0L) 0 else ((total + size - 1) / size).toInt())

    companion object {
        const val MAX_SIZE = 50

        fun of(
            page: Int?,
            size: Int?,
            defaultSize: Int = 12,
        ): PageRequest {
            val p = page ?: 0
            val s = size ?: defaultSize
            if (p < 0) throw ValidationException("Invalid page", "page", "must be 0 or more")
            if (s !in 1..MAX_SIZE) throw ValidationException("Invalid size", "size", "must be between 1 and $MAX_SIZE")
            return PageRequest(p, s)
        }
    }
}

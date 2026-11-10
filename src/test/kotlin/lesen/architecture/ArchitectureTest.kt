package lesen.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// los límites de CLAUDE.md sobre src/main/kotlin: nada bajo el paquete wasichai, nada bloqueante, ningún nombre de rol y
// cada capacidad usa solo las que le permite el grafo de docs/architecture/overview.md
class ArchitectureTest {
    private val sources =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.invariantSeparatorsPath to it.readText() }

    private fun capability(path: String): String? = Regex("src/main/kotlin/lesen/([a-z]+)/").find(path)?.groupValues?.get(1)

    @Test
    fun `finds the sources`() {
        assertThat(sources.keys).contains("src/main/kotlin/lesen/LesenApplication.kt", "src/main/kotlin/lesen/reading/Tokenizer.kt")
    }

    @Test
    fun `nothing lives under the wasichai package`() {
        assertThat(sources.filterValues { Regex("^package wasichai", RegexOption.MULTILINE).containsMatchIn(it) }.keys).isEmpty()
    }

    @Test
    fun `no jpa, spring data nor blocking jdbc`() {
        val forbidden = listOf("jakarta.persistence", "org.hibernate", "org.springframework.data.repository", "java.sql.", "JdbcTemplate")
        assertThat(sources.filterValues { text -> forbidden.any { it in text } }.keys).isEmpty()
    }

    @Test
    fun `no wasichai internal classes`() {
        val internals = listOf("RecordStore", "ObjectSchemaManager", "AuditService")
        assertThat(sources.filterValues { text -> internals.any { Regex("\\b$it\\b").containsMatchIn(text) } }.keys).isEmpty()
    }

    @Test
    fun `no role names in code`() {
        assertThat(sources.filterValues { Regex("\"(LECTOR|EDITOR|ADMIN)\"").containsMatchIn(it) }.keys).isEmpty()
    }

    @Test
    fun `each capability uses only what the graph allows`() {
        val violations =
            sources.flatMap { (path, text) ->
                val from = capability(path) ?: return@flatMap emptyList()
                Regex("^import lesen\\.([a-z]+)\\.", RegexOption.MULTILINE)
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .filter { it != from && it !in ALLOWED.getValue(from) }
                    .map { "$path usa lesen.$it" }
                    .toList()
            }
        assertThat(violations).isEmpty()
    }

    @Test
    fun `the graph has no cycles`() {
        fun reaches(
            from: String,
            target: String,
            seen: Set<String> = emptySet(),
        ): Boolean = ALLOWED.getValue(from).any { it == target || (it !in seen && reaches(it, target, seen + it)) }
        assertThat(ALLOWED.keys.filter { reaches(it, it) }).isEmpty()
    }

    companion object {
        // capacidad → las que puede importar (common es de todas). El mismo grafo que docs/architecture/overview.md §3
        val ALLOWED: Map<String, Set<String>> =
            mapOf(
                "common" to setOf(),
                "language" to setOf("common"),
                "learner" to setOf("common", "language"),
                "reading" to setOf("common"),
                "catalog" to setOf("common", "learner", "reading"),
                "dictionary" to setOf("common", "learner", "reading"),
                "library" to setOf("common", "catalog", "reading"),
                "vocabulary" to setOf("common", "dictionary", "reading"),
                "audio" to setOf("common", "reading"),
                "assessment" to setOf("common", "learner", "reading"),
                "progress" to setOf("common", "learner", "catalog", "vocabulary", "assessment"),
                "editorial" to setOf("common", "language", "reading", "audio", "assessment", "dictionary"),
                "demo" to setOf("common", "assessment", "audio", "catalog", "dictionary", "reading"),
                "generation" to setOf("common", "reading", "assessment"),
                "illustration" to setOf("common", "reading"),
                "commission" to setOf("common", "language", "reading", "assessment", "generation", "illustration", "editorial"),
                "export" to setOf("common", "reading", "dictionary", "library"),
            )
    }
}

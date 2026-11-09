package lesen.catalog

import java.time.Instant

data class Topic(
    val slug: String,
    val parent: String?,
    val labels: Map<String, String>,
    val position: Int,
)

data class TopicNode(
    val slug: String,
    val labels: Map<String, String>,
    val children: List<TopicNode>,
)

// el progreso de la persona en una lectura, si empezó
data class CardProgress(
    val percent: Int,
    val version: Int,
    val completed: Boolean,
    val updatedAt: Instant,
)

// una lectura en una lista: lo que hace falta para elegirla de un vistazo
data class ReadingCard(
    val slug: String,
    val language: String,
    val level: String,
    val topics: List<String>,
    val featured: Boolean,
    val version: Int,
    val title: String,
    val subtitle: String?,
    val summary: String,
    val cover: String?,
    val accent: String?,
    val wordCount: Int,
    val minutes: Int,
    val publishedAt: Instant,
    val bookmarked: Boolean,
    val progress: CardProgress?,
    // personas que la abrieron alguna vez (agregado anónimo, para ordenar por popularidad)
    val readers: Long,
)

data class CollectionView(
    val slug: String,
    val labels: Map<String, String>,
    val descriptions: Map<String, String>,
    val readings: List<ReadingCard>,
)

data class ReadingDetail(
    val reading: ReadingCard,
    val paragraphs: Int,
    val related: List<ReadingCard>,
    val collections: List<String>,
)

data class Recommendation(
    val reading: ReadingCard,
    // por qué se recomienda: LEVEL, COMPREHENSION (nivel ajustado por lo que entendió), TOPIC, HISTORY (tema de algo que
    // terminó), NEW y TRENDING (se lee esta semana). La interfaz lo explica con sus palabras
    val reasons: List<String>,
)

// lo que se lee esta semana: cuántas personas de la organización la terminaron en los últimos 7 días, sin decir quiénes
data class TrendingReading(
    val reading: ReadingCard,
    val readers: Int,
)

data class Discover(
    val continueReading: List<ReadingCard>,
    val featured: List<ReadingCard>,
    val recommended: List<Recommendation>,
    val thisWeek: List<TrendingReading>,
    val fresh: List<ReadingCard>,
    val collections: List<CollectionView>,
    val topics: List<TopicCount>,
)

data class TopicCount(
    val slug: String,
    val readings: Int,
)

enum class ReadingStatus { BOOKMARKED, IN_PROGRESS, COMPLETED, NOT_STARTED }

enum class ReadingSort { RECENT, POPULAR, LEVEL, DURATION, TITLE }

// filtros del catálogo; duración en minutos estimados (min/max inclusivos)
data class CatalogQuery(
    val language: String? = null,
    val levels: List<String> = emptyList(),
    val topic: String? = null,
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
    val text: String? = null,
    val status: ReadingStatus? = null,
    val featured: Boolean? = null,
    val sort: ReadingSort = ReadingSort.RECENT,
    // solo estas lecturas (las de una lista propia)
    val slugs: List<String>? = null,
)

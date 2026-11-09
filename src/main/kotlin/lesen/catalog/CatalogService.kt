package lesen.catalog

import lesen.common.Learner
import lesen.common.Page
import lesen.common.PageRequest
import lesen.learner.ProfileService
import org.springframework.stereotype.Service
import wasichai.core.common.NotFoundException
import java.time.Clock

@Service
class CatalogService(
    private val store: CatalogStore,
    private val profiles: ProfileService,
    private val clock: Clock,
    private val signals: List<LearnerSignalsSource>,
) {
    suspend fun topicTree(): List<TopicNode> {
        val topics = store.topics()
        val children = topics.groupBy { it.parent }

        fun node(t: Topic): TopicNode = TopicNode(t.slug, t.labels, children[t.slug].orEmpty().map(::node))
        return children[null].orEmpty().map(::node)
    }

    suspend fun search(
        me: Learner,
        query: CatalogQuery,
        page: PageRequest,
    ): Page<ReadingCard> {
        val (content, total) = store.cards(me.organizationId, me.userId, query, page)
        return page.of(content, total)
    }

    suspend fun detail(
        me: Learner,
        slug: String,
    ): ReadingDetail {
        val card = store.card(me.organizationId, me.userId, slug) ?: throw NotFoundException("Reading not found")
        val sameLanguage = store.cards(me.organizationId, me.userId, CatalogQuery(language = card.language), PageRequest(0, 50)).first
        val levels = listOf("A1", "A2", "B1", "B2", "C1", "C2")
        val related =
            sameLanguage
                .filter { it.slug != slug }
                .sortedWith(
                    compareByDescending<ReadingCard> { other ->
                        other.topics.count { t -> card.topics.any { it.substringBefore('/') == t.substringBefore('/') } }
                    }.thenBy { kotlin.math.abs(levels.indexOf(it.level) - levels.indexOf(card.level)) }
                        .thenBy { it.title },
                ).take(4)
        val collections =
            store
                .collectionReadings()
                .filterValues { slug in it }
                .keys
                .sorted()
        return ReadingDetail(card, store.paragraphs(slug), related, collections)
    }

    // las tarjetas de unas lecturas concretas, en el orden pedido; las que no existen se omiten
    suspend fun cards(
        me: Learner,
        slugs: List<String>,
    ): List<ReadingCard> {
        if (slugs.isEmpty()) return emptyList()
        val found =
            store
                .cards(me.organizationId, me.userId, CatalogQuery(slugs = slugs), PageRequest(0, slugs.size))
                .first
                .associateBy { it.slug }
        return slugs.mapNotNull { found[it] }
    }

    suspend fun discover(me: Learner): Discover {
        val profile = profiles.find(me.organizationId, me.userId) ?: throw NotFoundException("No learner profile yet")
        val target = profile.targetLanguage
        val all = store.cards(me.organizationId, me.userId, CatalogQuery(language = target), PageRequest(0, PageRequest.MAX_SIZE)).first
        val bySlug = all.associateBy { it.slug }
        val continueReading =
            store
                .cards(me.organizationId, me.userId, CatalogQuery(status = ReadingStatus.IN_PROGRESS), PageRequest(0, 6))
                .first
                .sortedByDescending { it.progress?.updatedAt }
        val collections =
            store.collections().mapNotNull { (slug, labels, descriptions) ->
                val readings = store.collectionReadings()[slug].orEmpty().mapNotNull { bySlug[it] }
                if (readings.isEmpty()) null else CollectionView(slug, labels, descriptions, readings)
            }
        val learned = signals.firstOrNull()?.signals(me.organizationId, me.userId, profile) ?: LearnerSignals.NONE
        return Discover(
            continueReading = continueReading,
            featured = all.filter { it.featured && it.progress?.completed != true }.ifEmpty { all.filter { it.featured } },
            recommended = Recommender.recommend(profile, all, clock.instant(), learned),
            thisWeek =
                all
                    .mapNotNull { card -> learned.trending[card.slug]?.let { TrendingReading(card, it) } }
                    .sortedWith(compareByDescending<TrendingReading> { it.readers }.thenBy { it.reading.title })
                    .take(6),
            fresh = all.sortedByDescending { it.publishedAt }.take(6),
            collections = collections,
            topics = store.topicCounts(target),
        )
    }
}

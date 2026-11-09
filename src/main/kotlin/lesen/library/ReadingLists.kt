package lesen.library

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.catalog.CatalogService
import lesen.catalog.ReadingCard
import lesen.common.Access
import lesen.common.Learner
import lesen.common.requireLength
import lesen.reading.ReadingVersions
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

data class ReadingListSummary(
    val id: UUID,
    val name: String,
    val count: Int,
    // si contiene la lectura por la que se preguntó (?reading=); null si no se preguntó
    val contains: Boolean?,
    val updatedAt: Instant,
)

data class ReadingListView(
    val id: UUID,
    val name: String,
    val updatedAt: Instant,
    // de la última añadida a la primera
    val readings: List<ReadingCard>,
)

data class ListName(
    val name: String,
)

// las listas propias: siempre filtradas por (organización, usuario) del token; la lista de otra persona no existe (404)
@Repository
class ReadingListStore(
    private val db: DatabaseClient,
) {
    suspend fun lists(
        o: UUID,
        u: UUID,
        reading: UUID?,
    ): List<ReadingListSummary> =
        db
            .sql(
                """
                SELECT l.id, l.name, l.updated_at, (SELECT count(*) FROM lesen.reading_list_item i WHERE i.list_id = l.id) AS n,
                       CASE WHEN CAST(:r AS uuid) IS NULL THEN NULL
                            ELSE EXISTS (SELECT 1 FROM lesen.reading_list_item i WHERE i.list_id = l.id AND i.reading_id = :r) END AS has
                FROM lesen.reading_list l WHERE l.organization_id = :o AND l.user_id = :u
                ORDER BY l.updated_at DESC, l.name
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .let { if (reading == null) it.bindNull("r", UUID::class.java) else it.bind("r", reading) }
            .map { row, _ ->
                ReadingListSummary(
                    row.get("id", UUID::class.java)!!,
                    row.get("name", String::class.java)!!,
                    row.get("n", Number::class.java)!!.toInt(),
                    row.get("has", java.lang.Boolean::class.java)?.booleanValue(),
                    row.get("updated_at", Instant::class.java)!!,
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun count(
        o: UUID,
        u: UUID,
    ): Int =
        db
            .sql("SELECT count(*) FROM lesen.reading_list WHERE organization_id = :o AND user_id = :u")
            .bind("o", o)
            .bind("u", u)
            .map { row, _ -> row.get(0, Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()

    suspend fun create(
        o: UUID,
        u: UUID,
        name: String,
    ): UUID =
        db
            .sql("INSERT INTO lesen.reading_list (organization_id, user_id, name) VALUES (:o, :u, :n) RETURNING id")
            .bind("o", o)
            .bind("u", u)
            .bind("n", name)
            .map { row, _ -> row.get(0, UUID::class.java)!! }
            .one()
            .awaitSingle()

    // la lista con sus lecturas (slugs, de la última añadida a la primera); null si no existe o es de otra persona
    suspend fun find(
        o: UUID,
        u: UUID,
        id: UUID,
    ): Triple<String, Instant, List<String>>? {
        val head =
            db
                .sql("SELECT name, updated_at FROM lesen.reading_list WHERE id = :id AND organization_id = :o AND user_id = :u")
                .bind("id", id)
                .bind("o", o)
                .bind("u", u)
                .map { row, _ -> row.get("name", String::class.java)!! to row.get("updated_at", Instant::class.java)!! }
                .one()
                .awaitSingleOrNull() ?: return null
        val slugs =
            db
                .sql(
                    """
                    SELECT r.slug FROM lesen.reading_list_item i JOIN lesen.reading r ON r.id = i.reading_id
                    WHERE i.list_id = :id ORDER BY i.added_at DESC, r.slug
                    """.trimIndent(),
                ).bind("id", id)
                .map { row, _ -> row.get(0, String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        return Triple(head.first, head.second, slugs)
    }

    suspend fun rename(
        o: UUID,
        u: UUID,
        id: UUID,
        name: String,
    ): Boolean =
        db
            .sql("UPDATE lesen.reading_list SET name = :n, updated_at = now() WHERE id = :id AND organization_id = :o AND user_id = :u")
            .bind("n", name)
            .bind("id", id)
            .bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun delete(
        o: UUID,
        u: UUID,
        id: UUID,
    ): Boolean =
        db
            .sql("DELETE FROM lesen.reading_list WHERE id = :id AND organization_id = :o AND user_id = :u")
            .bind("id", id)
            .bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun items(id: UUID): Int =
        db
            .sql("SELECT count(*) FROM lesen.reading_list_item WHERE list_id = :id")
            .bind("id", id)
            .map { row, _ -> row.get(0, Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()

    // la lista ya se comprobó como propia
    suspend fun put(
        id: UUID,
        reading: UUID,
        on: Boolean,
    ) {
        val sql =
            if (on) {
                "INSERT INTO lesen.reading_list_item (list_id, reading_id) VALUES (:id, :r) ON CONFLICT DO NOTHING"
            } else {
                "DELETE FROM lesen.reading_list_item WHERE list_id = :id AND reading_id = :r"
            }
        val changed =
            db
                .sql(sql)
                .bind("id", id)
                .bind("r", reading)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (changed > 0) {
            db
                .sql("UPDATE lesen.reading_list SET updated_at = now() WHERE id = :id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }
}

@Service
class ReadingListService(
    private val store: ReadingListStore,
    private val versions: ReadingVersions,
    private val catalog: CatalogService,
) {
    suspend fun lists(
        me: Learner,
        slug: String?,
    ): List<ReadingListSummary> {
        requireLength(slug, "reading", 120)
        val reading = slug?.let { versions.find(it)?.readingId ?: throw NotFoundException("Reading not found") }
        return store.lists(me.organizationId, me.userId, reading)
    }

    suspend fun create(
        me: Learner,
        request: ListName,
    ): ReadingListView {
        val name = name(request)
        if (store.count(me.organizationId, me.userId) >= MAX_LISTS) throw ValidationException("Too many lists", "name", "at most $MAX_LISTS lists")
        val id = unique { store.create(me.organizationId, me.userId, name) }
        return list(me, id)
    }

    suspend fun list(
        me: Learner,
        id: UUID,
    ): ReadingListView {
        val (name, updatedAt, slugs) = store.find(me.organizationId, me.userId, id) ?: throw NotFoundException("List not found")
        return ReadingListView(id, name, updatedAt, catalog.cards(me, slugs))
    }

    suspend fun rename(
        me: Learner,
        id: UUID,
        request: ListName,
    ): ReadingListView {
        val name = name(request)
        if (!unique { store.rename(me.organizationId, me.userId, id, name) }) throw NotFoundException("List not found")
        return list(me, id)
    }

    suspend fun delete(
        me: Learner,
        id: UUID,
    ) {
        if (!store.delete(me.organizationId, me.userId, id)) throw NotFoundException("List not found")
    }

    suspend fun put(
        me: Learner,
        id: UUID,
        slug: String,
        on: Boolean,
    ) {
        requireLength(slug, "reading", 120)
        val (_, _, slugs) = store.find(me.organizationId, me.userId, id) ?: throw NotFoundException("List not found")
        val reading = versions.find(slug) ?: throw NotFoundException("Reading not found")
        if (on && slug !in slugs && slugs.size >= MAX_ITEMS) throw ValidationException("Too many readings", "reading", "at most $MAX_ITEMS readings per list")
        store.put(id, reading.readingId, on)
    }

    private fun name(request: ListName): String {
        val name = request.name.trim()
        if (name.isEmpty() || name.length > 80) throw ValidationException("Invalid name", "name", "must have between 1 and 80 characters")
        return name
    }

    // dos listas propias no se llaman igual (sin distinguir mayúsculas)
    private suspend fun <T> unique(block: suspend () -> T): T =
        try {
            block()
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("A list with that name already exists")
        }

    companion object {
        const val MAX_LISTS = 50
        const val MAX_ITEMS = 200
    }
}

@RestController
@RequestMapping("/api/lesen/lists")
class ReadingListController(
    private val access: Access,
    private val service: ReadingListService,
) {
    // ?reading=slug añade si cada lista la contiene (para la ficha de una lectura)
    @GetMapping
    suspend fun lists(
        @RequestParam reading: String?,
    ): List<ReadingListSummary> = service.lists(access.notebook(), reading)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: ListName,
    ): ReadingListView = service.create(access.writeNotebook(), request)

    @GetMapping("/{id}")
    suspend fun list(
        @PathVariable id: UUID,
    ): ReadingListView = service.list(access.notebook(), id)

    @PatchMapping("/{id}")
    suspend fun rename(
        @PathVariable id: UUID,
        @RequestBody request: ListName,
    ): ReadingListView = service.rename(access.writeNotebook(), id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID,
    ) = service.delete(access.writeNotebook(), id)

    @PutMapping("/{id}/readings/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun add(
        @PathVariable id: UUID,
        @PathVariable slug: String,
    ) = service.put(access.writeNotebook(), id, slug, true)

    @DeleteMapping("/{id}/readings/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun remove(
        @PathVariable id: UUID,
        @PathVariable slug: String,
    ) = service.put(access.writeNotebook(), id, slug, false)
}

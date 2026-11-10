package lesen.editorial

import lesen.LesenApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.BodyInserters
import tools.jackson.databind.JsonNode
import java.util.UUID

// el criterio de salida de la fase 6: una editora publica una lectura nueva con audio y preguntas sin tocar JSON, la
// lectura se lee con su audio y sus preguntas, y una segunda versión deja historial. La base de prueba se reutiliza:
// cada ejecución usa su propio slug
class StudioApiTest : LesenApiTest() {
    private val run =
        UUID
            .randomUUID()
            .toString()
            .take(8)
            .filter { it.isLetter() }
            .padEnd(4, 'q')
    private val slug = "studio-test-${UUID.randomUUID().toString().take(8)}"

    // una palabra que el diccionario no tiene hasta que la estudio la añade
    private val word = "Lumor$run"

    private fun document(
        extra: String = "",
        withAudioWord: Boolean = true,
    ) = mapOf(
        "slug" to slug,
        "language" to "de",
        "level" to "A1",
        "topics" to listOf("nature"),
        "title" to "Der Garten",
        "summary" to "Mia ist im Garten.",
        "paragraphs" to
            listOf(
                mapOf(
                    "translations" to mapOf("es" to "Mia está en el jardín. El jardín es grande.", "en" to "Mia is in the garden. The garden is big."),
                    "sentences" to
                        listOf(
                            mapOf(
                                "text" to "Mia ist im Garten$extra.",
                                "translations" to mapOf("es" to "Mia está en el jardín.", "en" to "Mia is in the garden."),
                            ),
                            mapOf(
                                "text" to if (withAudioWord) "Der Garten ist $word." else "Der Garten ist groß.",
                                "translations" to mapOf("es" to "El jardín es grande.", "en" to "The garden is big."),
                            ),
                        ),
                ),
            ),
    )

    private val questions =
        listOf(
            mapOf(
                "id" to "q1",
                "type" to "TRUE_FALSE",
                "difficulty" to 1,
                "prompt" to mapOf("es" to "Mia está en el jardín.", "en" to "Mia is in the garden."),
                "explanation" to mapOf("es" to "Lo dice la primera oración.", "en" to "The first sentence says so."),
                "refs" to listOf("p1s1"),
                "spec" to mapOf("correct" to true),
            ),
            mapOf(
                "id" to "q2",
                "type" to "MULTIPLE_CHOICE",
                "difficulty" to 1,
                "prompt" to mapOf("es" to "¿Dónde está Mia?", "en" to "Where is Mia?"),
                "explanation" to mapOf("es" to "En el jardín.", "en" to "In the garden."),
                "refs" to listOf("p1s1"),
                "spec" to
                    mapOf(
                        "options" to
                            listOf(
                                mapOf("id" to "a", "text" to mapOf("es" to "En el jardín", "en" to "In the garden")),
                                mapOf(
                                    "id" to "b",
                                    "text" to mapOf("es" to "En casa", "en" to "At home"),
                                ),
                            ),
                        "correct" to listOf("a"),
                    ),
            ),
        )

    // un mp3 mínimo (cabecera ID3 y relleno): el servidor comprueba la firma, no lo reproduce
    private val mp3 = "ID3".toByteArray() + ByteArray(2048) { (it % 251).toByte() }
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    // una palabra por tramo, como la dan los alineadores
    private fun vtt(words: List<String>): String =
        buildString {
            append("WEBVTT\n\n")
            words.forEachIndexed { i, w -> append("00:00:0$i.100 --> 00:00:0$i.800\n$w\n\n") }
        }

    private fun multipart(
        path: String,
        token: String,
        parts: MultipartBodyBuilder,
        status: Int,
    ): JsonNode? {
        val body =
            client
                .post()
                .uri(path)
                .header("Authorization", token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        return body?.takeIf { it.isNotBlank() }?.let { json.readTree(it) }
    }

    private fun audio(
        token: String,
        id: String,
        file: ByteArray,
        timings: String,
        status: Int,
    ) = multipart(
        "/api/lesen/studio/drafts/$id/audio",
        token,
        MultipartBodyBuilder().apply {
            part("audio", file).filename("narracion.mp3").contentType(MediaType.parseMediaType("audio/mpeg"))
            part("timings", timings.toByteArray()).filename("tiempos.vtt").contentType(MediaType.TEXT_PLAIN)
            part("durationMs", "9000")
            part("voice", "Ana Pérez")
            part("license", "Grabación propia, cedida a Lesen")
        },
        status,
    )

    private fun save(
        token: String,
        draft: JsonNode,
        document: Map<String, Any?>,
        questions: List<Any> = this.questions,
        status: Int = 200,
    ) = send(
        "PUT",
        "/api/lesen/studio/drafts/${draft["id"].asString()}",
        token,
        mapOf("revision" to draft["revision"].asInt(), "document" to document, "questions" to questions),
        status,
    )

    @Test
    fun `an editor publishes a new reading with audio and questions, and a learner reads it`() {
        val editor = editor()
        val created =
            send(
                "POST",
                "/api/lesen/studio/drafts",
                editor,
                mapOf("slug" to slug, "language" to "de", "level" to "A1", "title" to "Der Garten"),
                201,
            )!!
        val id = created["id"].asString()
        assertThat(created["status"].asString()).isEqualTo("DRAFT")
        // otro borrador para la misma lectura, no
        send("POST", "/api/lesen/studio/drafts", editor, mapOf("slug" to slug, "language" to "de", "level" to "A1", "title" to "Otra"), 409)
        // recién creado le falta casi todo: no está listo
        assertThat(get("/api/lesen/studio/drafts/$id/check", editor)["ready"].asBoolean()).isFalse()

        // la portada y el texto, con sus traducciones y sus preguntas
        val cover =
            multipart(
                "/api/lesen/studio/covers",
                editor,
                MultipartBodyBuilder().apply { part("file", png).filename("portada.png").contentType(MediaType.IMAGE_PNG) },
                201,
            )!!
        assertThat(cover["file"].asString()).matches("cover-[0-9a-f]{24}\\.png")
        val saved = save(editor, created, document() + ("cover" to cover["file"].asString()))!!
        assertThat(saved["revision"].asInt()).isGreaterThan(created["revision"].asInt())
        // con la revisión vieja, alguien guardó antes: 409
        save(editor, created, document(), status = 409)
        assertThat(saved["sentences"].values().map { it["id"].asString() }).containsExactly("p1s1", "p1s2")

        // la palabra sin entrada, y la entrada nueva desde el estudio
        val before = get("/api/lesen/studio/drafts/$id/check", editor)
        assertThat(before["ready"].asBoolean()).isTrue()
        val unresolved = before["warnings"].values().filter { it["code"].asString() == "UNRESOLVED" }.map { it["ref"].asString() }
        assertThat(unresolved).contains(word)
        assertThat(before["warnings"].values().map { it["code"].asString() }).contains("NO_AUDIO")
        val entry =
            mapOf(
                "language" to "de",
                "entry" to
                    mapOf(
                        "key" to "de:${word.lowercase()}",
                        "lemma" to word.lowercase(),
                        "pos" to "adjective",
                        "senses" to listOf(mapOf("translations" to mapOf("es" to listOf("lumoroso"), "en" to listOf("lumorous")))),
                    ),
            )
        send("POST", "/api/lesen/studio/dictionary/entries", editor, entry, 201)
        send("POST", "/api/lesen/studio/dictionary/entries", editor, entry, 409)
        send(
            "POST",
            "/api/lesen/studio/dictionary/entries",
            editor,
            mapOf(
                "language" to "de",
                "entry" to mapOf("key" to "x", "lemma" to "", "pos" to "?", "senses" to emptyList<Any>()),
            ),
            400,
        )
        val after = get("/api/lesen/studio/drafts/$id/check", editor)
        assertThat(after["warnings"].values().filter { it["code"].asString() == "UNRESOLVED" }.map { it["ref"].asString() }).doesNotContain(word)

        // el audio, con tiempos por palabra subidos como WebVTT; lo que no es audio, no
        audio(editor, id, png, vtt(listOf("Mia")), 400)
        audio(editor, id, mp3, "esto no son tiempos", 400)
        val words = listOf("Mia", "ist", "im", "Garten", "Der", "Garten", "ist", word)
        val narrated = audio(editor, id, mp3, vtt(words), 200)!!
        assertThat(narrated["audio"]["timedWords"].asInt()).isEqualTo(8)
        assertThat(narrated["audio"]["url"].asString()).startsWith("/api/lesen/public/audio/audio-")
        val ready = get("/api/lesen/studio/drafts/$id/check", editor)
        assertThat(ready["ready"].asBoolean()).isTrue()
        assertThat(ready["alignedWords"].asInt()).isEqualTo(8)

        // revisión: en revisión no se edita; se devuelve con una nota y se envía otra vez
        send("POST", "/api/lesen/studio/drafts/$id/submit", redactora(), mapOf("note" to "lista"), 200)
        save(editor, get("/api/lesen/studio/drafts/$id", editor), document(), status = 409)
        send("POST", "/api/lesen/studio/drafts/$id/return", editor, mapOf("note" to ""), 400)
        send("POST", "/api/lesen/studio/drafts/$id/return", editor, mapOf("note" to "revisa el título"), 200)
        send("POST", "/api/lesen/studio/drafts/$id/submit", editor, null, 200)
        // quien redacta no publica
        send("POST", "/api/lesen/studio/drafts/$id/publish", redactora(), null, 403)
        val published = send("POST", "/api/lesen/studio/drafts/$id/publish", editor, mapOf("note" to "primera versión"), 200)!!
        assertThat(published["status"].asString()).isEqualTo("PUBLISHED")
        assertThat(published["publishedVersion"].asInt()).isEqualTo(1)
        assertThat(published["events"].values().map { it["action"].asString() })
            .contains("CREATED", "SAVED", "AUDIO_ATTACHED", "SUBMITTED", "RETURNED", "PUBLISHED")
        // ya publicado, no se vuelve a publicar
        send("POST", "/api/lesen/studio/drafts/$id/publish", editor, null, 409)

        // quien lee la encuentra con su portada, su audio y sus preguntas
        val learner = lena()
        ensureProfile(learner)
        val detail = get("/api/lesen/readings/$slug", learner)
        assertThat(detail["reading"]["title"].asString()).isEqualTo("Der Garten")
        val coverUrl = detail["reading"]["cover"].asString()
        client
            .get()
            .uri(coverUrl)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentType("image/png")
            .expectHeader()
            .valueEquals("X-Content-Type-Options", "nosniff")
        val content = get("/api/lesen/readings/$slug/versions/1/content", learner)
        val token = content["paragraphs"][0]["sentences"][1]["tokens"].values().first { it["text"].asString() == word }
        assertThat(token["entry"].asString()).isEqualTo("de:${word.lowercase()}")
        val sound = get("/api/lesen/readings/$slug/versions/1/audio", learner)["assets"][0]
        assertThat(sound["origin"].asString()).isEqualTo("UPLOADED")
        assertThat(sound["voice"]["name"].asString()).isEqualTo("Ana Pérez")
        assertThat(sound["timings"].size()).isEqualTo(8)
        val file =
            client
                .get()
                .uri(sound["url"].asString())
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody!!
        assertThat(file).isEqualTo(mp3)
        client
            .get()
            .uri(sound["url"].asString())
            .header("Range", "bytes=0-9")
            .exchange()
            .expectStatus()
            .isEqualTo(206)
        val quiz = get("/api/lesen/readings/$slug/versions/1/assessment", learner)
        assertThat(quiz["questions"].values().map { it["id"].asString() }).containsExactly("q1", "q2")
    }

    @Test
    fun `editing a published reading starts from its current version and leaves the old one readable, with history`() {
        val editor = editor()
        // la primera versión, publicada desde el estudio
        val first = send("POST", "/api/lesen/studio/drafts", editor, mapOf("slug" to slug, "language" to "de", "level" to "A1", "title" to "Der Garten"), 201)!!
        val firstId = first["id"].asString()
        save(editor, first, document(withAudioWord = false))
        send("POST", "/api/lesen/studio/drafts/$firstId/submit", editor, null, 200)
        send("POST", "/api/lesen/studio/drafts/$firstId/publish", editor, null, 200)

        // editarla: el borrador trae su texto, sus traducciones y sus preguntas
        val second = send("POST", "/api/lesen/studio/drafts", editor, mapOf("from" to slug), 201)!!
        val secondId = second["id"].asString()
        assertThat(second["baseVersion"].asInt()).isEqualTo(1)
        assertThat(second["document"]["title"].asString()).isEqualTo("Der Garten")
        assertThat(second["document"]["paragraphs"][0]["sentences"][0]["translations"]["es"].asString()).isEqualTo("Mia está en el jardín.")
        assertThat(second["questions"].size()).isEqualTo(2)
        send("POST", "/api/lesen/studio/drafts", editor, mapOf("from" to slug), 409)
        // sin cambiar el texto no hay versión nueva
        assertThat(get("/api/lesen/studio/drafts/$secondId/check", editor)["warnings"].values().map { it["code"].asString() }).contains("UNCHANGED")
        save(editor, second, document(extra = " heute", withAudioWord = false))
        send("POST", "/api/lesen/studio/drafts/$secondId/submit", editor, null, 200)
        assertThat(send("POST", "/api/lesen/studio/drafts/$secondId/publish", editor, null, 200)!!["publishedVersion"].asInt()).isEqualTo(2)

        // la versión 1 sigue ahí para quien la leyó; la vigente es la 2
        val learner = lena()

        fun words(version: Int) =
            get(
                "/api/lesen/readings/$slug/versions/$version/content",
                learner,
            )["paragraphs"][0]["sentences"][0]["tokens"].values().map { it["text"].asString() }
        assertThat(words(1)).containsExactly("Mia", "ist", "im", "Garten", ".")
        assertThat(words(2)).containsExactly("Mia", "ist", "im", "Garten", "heute", ".")
        assertThat(get("/api/lesen/readings/$slug", learner)["reading"]["version"].asInt()).isEqualTo(2)
        val history = get("/api/lesen/studio/readings/$slug/history", editor)
        assertThat(history["versions"].values().map { it["number"].asInt() to it["status"].asString() }).containsExactly(2 to "PUBLISHED", 1 to "RETIRED")
        assertThat(history["events"].values().filter { it["action"].asString() == "PUBLISHED" }.map { it["version"].asInt() }).containsExactly(2, 1)
        assertThat(history["events"].values().map { it["actor"].asString() }.toSet()).containsExactly("editor@demo.lesen.test")
        val listed = get("/api/lesen/studio/readings", editor).values().first { it["slug"].asString() == slug }
        assertThat(listed["currentVersion"].asInt()).isEqualTo(2)
        assertThat(listed["openDraft"].isNull).isTrue()
        assertThat(get("/api/lesen/studio/drafts?status=PUBLISHED", editor).values().map { it["id"].asString() }).contains(firstId, secondId)
    }

    @Test
    fun `an editor publishes a picture book with its illustrations, and a learner reads it page by page`() {
        val editor = editor()
        val created =
            send("POST", "/api/lesen/studio/drafts", editor, mapOf("slug" to slug, "language" to "de", "level" to "A1", "title" to "Der Garten"), 201)!!
        val id = created["id"].asString()
        // la ilustración de una página: quien solo lee no sube nada; lo que no es imagen, no
        val picture = png + "illustration".toByteArray()
        multipart(
            "/api/lesen/studio/illustrations",
            tomas(),
            MultipartBodyBuilder().apply { part("file", picture).filename("bild.png").contentType(MediaType.IMAGE_PNG) },
            403,
        )
        multipart(
            "/api/lesen/studio/illustrations",
            editor,
            MultipartBodyBuilder().apply { part("file", mp3).filename("bild.png").contentType(MediaType.IMAGE_PNG) },
            400,
        )
        val illustration =
            multipart(
                "/api/lesen/studio/illustrations",
                editor,
                MultipartBodyBuilder().apply { part("file", picture).filename("bild.png").contentType(MediaType.IMAGE_PNG) },
                201,
            )!!
        assertThat(illustration["file"].asString()).matches("illustration-[0-9a-f]{24}\\.png")
        assertThat(illustration["kind"].asString()).isEqualTo("ILLUSTRATION")

        // un libro: la maqueta del libro, una página con su ilustración pendiente y una sin texto que solo lleva imagen
        val alt = mapOf("es" to "Mia en el jardín", "en" to "Mia in the garden")

        fun book(file: String?) =
            document(withAudioWord = false) +
                mapOf(
                    "format" to "PICTURE_BOOK",
                    "layout" to "IMAGE_TOP",
                    "pages" to
                        listOf(
                            mapOf("span" to 1, "paragraphs" to 1, "illustration" to mapOf("file" to file, "alt" to alt, "scene" to "Mia im Garten")),
                            mapOf("span" to 2, "layout" to "IMAGE_ONLY", "paragraphs" to 0, "illustration" to mapOf("file" to file, "alt" to alt)),
                        ),
                )
        // una maqueta que no cabe en su ancho es un error del documento
        val wrong = book(null) + ("pages" to listOf(mapOf("span" to 2, "layout" to "VIGNETTE", "paragraphs" to 1)))
        val invalid = save(editor, created, wrong)!!
        assertThat(get("/api/lesen/studio/drafts/$id/check", editor)["errors"].values().map { it["ref"]?.asString() }).contains("pages[0].layout")
        val pending = save(editor, invalid, book(null))!!
        val check = get("/api/lesen/studio/drafts/$id/check", editor)
        assertThat(check["ready"].asBoolean()).isFalse()
        assertThat(check["errors"].values().filter { it["code"].asString() == "ILLUSTRATION_MISSING" }.map { it["ref"].asString() }).containsExactly("g1", "g2")
        save(editor, pending, book(illustration["file"].asString()))
        assertThat(get("/api/lesen/studio/drafts/$id/check", editor)["ready"].asBoolean()).isTrue()
        send("POST", "/api/lesen/studio/drafts/$id/submit", editor, null, 200)
        send("POST", "/api/lesen/studio/drafts/$id/publish", editor, null, 200)

        // quien lee ve un libro: páginas con su maqueta resuelta, los ids de sus párrafos y la dirección de la ilustración
        val learner = lena()
        ensureProfile(learner)
        assertThat(get("/api/lesen/readings/$slug", learner)["reading"]["format"].asString()).isEqualTo("PICTURE_BOOK")
        val content = get("/api/lesen/readings/$slug/versions/1/content", learner)
        assertThat(content["format"].asString()).isEqualTo("PICTURE_BOOK")
        assertThat(content["layout"].asString()).isEqualTo("IMAGE_TOP")
        val pages = content["pages"].values().toList()
        assertThat(pages.map { it["id"].asString() to it["layout"].asString() }).containsExactly("g1" to "IMAGE_TOP", "g2" to "IMAGE_ONLY")
        assertThat(pages[0]["paragraphs"].values().map { it.asString() }).containsExactly("p1")
        assertThat(pages[1]["span"].asInt()).isEqualTo(2)
        assertThat(pages[0]["illustration"]["alt"]["en"].asString()).isEqualTo("Mia in the garden")
        val src = pages[0]["illustration"]["src"].asString()
        assertThat(src).isEqualTo("/api/lesen/public/illustrations/${illustration["file"].asString()}")
        client
            .get()
            .uri(src)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentType("image/png")
        client
            .get()
            .uri("/api/lesen/public/illustrations/nada.png")
            .exchange()
            .expectStatus()
            .isNotFound
        // el catálogo filtra por formato; el libro de la demostración también es uno
        val books = get("/api/lesen/readings?format=PICTURE_BOOK&size=50", learner)["content"].values().map { it["slug"].asString() }
        assertThat(books).contains(slug, "ein-sonntag-bei-oma").doesNotContain("der-alte-leuchtturm")
        // volver a editarlo empieza por las mismas páginas
        val again = send("POST", "/api/lesen/studio/drafts", editor, mapOf("from" to slug), 201)!!
        assertThat(again["document"]["format"].asString()).isEqualTo("PICTURE_BOOK")
        assertThat(again["document"]["pages"].values().map { it["layout"]?.takeUnless { n -> n.isNull }?.asString() }).containsExactly(null, "IMAGE_ONLY")
        send("DELETE", "/api/lesen/studio/drafts/${again["id"].asString()}", editor, null, 204)
    }

    @Test
    fun `a draft can be discarded and the studio is only for the editorial roles`() {
        val created =
            send(
                "POST",
                "/api/lesen/studio/drafts",
                redactora(),
                mapOf("slug" to slug, "language" to "de", "level" to "A2", "title" to "Entwurf"),
                201,
            )!!
        val id = created["id"].asString()
        send("POST", "/api/lesen/studio/drafts", redactora(), mapOf("slug" to "Mal Slug", "language" to "xx", "level" to "Z9", "title" to ""), 400)
        send("POST", "/api/lesen/studio/drafts", redactora(), mapOf("from" to "no-such-reading"), 404)
        send(
            "POST",
            "/api/lesen/studio/drafts",
            redactora(),
            mapOf("slug" to "der-alte-leuchtturm", "language" to "de", "level" to "A2", "title" to "Ya existe"),
            409,
        )
        // quien lee no ve el estudio; quien redacta no devuelve
        send("GET", "/api/lesen/studio/drafts", tomas(), null, 403)
        send("GET", "/api/lesen/studio/drafts/$id", tomas(), null, 403)
        send("PUT", "/api/lesen/studio/drafts/$id", tomas(), mapOf("revision" to 1, "document" to document()), 403)
        send("POST", "/api/lesen/studio/drafts/$id/return", redactora(), mapOf("note" to "no"), 403)
        client
            .get()
            .uri("/api/lesen/studio/drafts")
            .exchange()
            .expectStatus()
            .isUnauthorized
        send("DELETE", "/api/lesen/studio/drafts/$id", redactora(), null, 204)
        assertThat(get("/api/lesen/studio/drafts/$id", editor())["status"].asString()).isEqualTo("DISCARDED")
        send("DELETE", "/api/lesen/studio/drafts/$id", redactora(), null, 409)
        send("GET", "/api/lesen/studio/drafts/${UUID.randomUUID()}", editor(), null, 404)
        // descartado, la lectura admite un borrador nuevo
        send("POST", "/api/lesen/studio/drafts", redactora(), mapOf("slug" to slug, "language" to "de", "level" to "A2", "title" to "Otra vez"), 201)
    }

    @Test
    fun `a reading edited without changes keeps its version, even if it came with the demo content`() {
        val editor = editor()
        val before = get("/api/lesen/readings/der-fuchs-im-garten", lena())["reading"]["version"].asInt()
        val draft = send("POST", "/api/lesen/studio/drafts", editor, mapOf("from" to "der-fuchs-im-garten"), 201)!!
        val id = draft["id"].asString()
        assertThat(draft["questions"].size()).isPositive()
        assertThat(draft["audio"]["timedWords"].asInt()).isPositive()
        val check = get("/api/lesen/studio/drafts/$id/check", editor)
        assertThat(check["ready"].asBoolean()).isTrue()
        assertThat(check["warnings"].values().map { it["code"].asString() }).contains("UNCHANGED")
        send("POST", "/api/lesen/studio/drafts/$id/submit", editor, null, 200)
        assertThat(send("POST", "/api/lesen/studio/drafts/$id/publish", editor, null, 200)!!["publishedVersion"].asInt()).isEqualTo(before)
        assertThat(get("/api/lesen/readings/der-fuchs-im-garten", lena())["reading"]["version"].asInt()).isEqualTo(before)
    }
}

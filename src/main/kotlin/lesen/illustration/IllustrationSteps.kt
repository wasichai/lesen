package lesen.illustration

import lesen.reading.PageLayout

// los pasos puros de la dirección de arte (ADR-0015). Los usan el agente de Embabel y el recorrido sin modelo; la
// coherencia sale de aquí: cada imagen se pide con la misma biblia, las hojas de los personajes que salen y lo que pasó
// en la página anterior, nunca solo con el texto de su página
object IllustrationSteps {
    // la proporción de la ilustración en cada maqueta (BookReader del frontend)
    fun aspect(
        layout: PageLayout,
        span: Int,
    ): Aspect =
        when (layout) {
            PageLayout.IMAGE_TOP, PageLayout.IMAGE_BOTTOM -> Aspect.LANDSCAPE
            PageLayout.VIGNETTE -> Aspect.SQUARE
            PageLayout.PANORAMA -> Aspect.PANORAMA
            PageLayout.SPREAD_IMAGE_LEFT, PageLayout.SPREAD_IMAGE_RIGHT -> Aspect.PORTRAIT
            PageLayout.TEXT_OVER_IMAGE, PageLayout.IMAGE_ONLY -> if (span == 2) Aspect.WIDE else Aspect.PORTRAIT
            PageLayout.TEXT_ONLY -> Aspect.PORTRAIT
        }

    // lo que la maqueta pide al encuadre: dónde queda sitio para el texto
    fun framing(layout: PageLayout): String =
        when (layout) {
            PageLayout.TEXT_OVER_IMAGE -> "keep the lower third calm and simple, the text sits over it"
            PageLayout.VIGNETTE -> "a small vignette with a plain background that fades at the edges"
            PageLayout.PANORAMA -> "a wide panorama, the action spread across the width"
            PageLayout.IMAGE_ONLY -> "a full-page picture that tells the moment on its own"
            else -> "the main action centred, nothing important at the edges"
        }

    // la biblia del libro, tal como la escribió la generación del texto: lo que sigue el recorrido sin modelo
    fun bibleFrom(art: Art): ArtBible =
        ArtBible(
            style = art.style.trim(),
            palette = art.palette.trim(),
            lighting = "soft, even daylight unless the scene says otherwise",
            setting = art.setting.trim(),
            characters = art.characters.map { CharacterSheet(it.name.trim(), it.look.trim()) },
        )

    // el plano de cada página sin modelo: su escena con lo que pasó antes y lo que viene, para que la continuidad se pida
    fun shotsFrom(brief: IllustrationBrief): ShotList =
        ShotList(
            brief.pages.mapIndexed { i, page ->
                val before = brief.pages.getOrNull(i - 1)?.scene
                val after = brief.pages.getOrNull(i + 1)?.scene
                val continuity =
                    listOfNotNull(
                        before?.let { "Just before: $it." },
                        after?.let { "Right after: $it." },
                    ).joinToString(" ")
                Shot(
                    page = page.index,
                    description = listOf(page.scene.trim(), continuity).filter { it.isNotBlank() }.joinToString(" "),
                    characters = page.characters,
                    framing = framing(page.layout),
                    aspect = aspect(page.layout, page.span),
                    alt = page.alt,
                )
            },
        )

    // lo que se le pide al proveedor de imágenes por una página
    fun imagePrompt(
        bible: ArtBible,
        shot: Shot,
        withPrevious: Boolean,
    ): String =
        buildString {
            appendLine("An illustration for one page of a children's picture book.")
            appendLine("Style: ${bible.style}. Palette: ${bible.palette}. Lighting: ${bible.lighting}. Setting: ${bible.setting}.")
            val cast = bible.characters.filter { c -> shot.characters.any { it.equals(c.name, ignoreCase = true) } }
            if (cast.isNotEmpty()) {
                appendLine("Characters, exactly as in their reference sheets:")
                cast.forEach { appendLine("- ${it.name}: ${it.look}") }
            }
            appendLine("The moment: ${shot.description}")
            appendLine("Composition: ${shot.framing}. Aspect ratio ${shot.aspect.ratio}.")
            if (withPrevious) appendLine("Keep continuity with the previous page's illustration, given as a reference.")
            append("No text, letters, numbers or signs anywhere in the picture.")
        }

    // la hoja de referencia de un personaje: de cuerpo entero, de frente, sobre fondo neutro, con el estilo del libro
    fun referencePrompt(
        bible: ArtBible,
        character: CharacterSheet,
    ): String =
        "A character reference sheet for a children's picture book: ${character.name}, ${character.look}. " +
            "Full body, front view, neutral plain background. Style: ${bible.style}. Palette: ${bible.palette}. " +
            "No text, letters or numbers."

    // el fotograma de estilo: el lugar del libro, sin personajes
    fun stylePrompt(bible: ArtBible): String =
        "A style frame for a children's picture book, showing only the setting: ${bible.setting}. Style: ${bible.style}. " +
            "Palette: ${bible.palette}. Lighting: ${bible.lighting}. No people, no text."

    // las referencias que acompañan una página: las hojas de quien sale, el estilo y la ilustración anterior aceptada
    fun referencesFor(
        shot: Shot,
        references: ReferenceSet,
        previous: Picture?,
        limit: Int = 4,
    ): List<Picture> {
        val sheets =
            shot.characters.mapNotNull { name ->
                references.characters.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value
            }
        return (sheets + listOfNotNull(previous, references.style)).take(limit)
    }

    // el texto alternativo de una página: el del plano, si lo trae en cada idioma; si no, el que trajo la generación
    fun alt(
        shot: Shot,
        page: BriefPage?,
        languages: List<String>,
    ): Map<String, String> =
        languages
            .associateWith { language -> shot.alt[language]?.takeIf { it.isNotBlank() } ?: page?.alt?.get(language).orEmpty() }
            .filterValues { it.isNotBlank() }
}

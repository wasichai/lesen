package lesen.reading

import java.util.UUID

// avisa de que una persona terminó una versión. Lo implementa quien necesita saberlo (vocabulary anota los encuentros)
// sin que library dependa de esa capacidad: library solo conoce esta interfaz, que vive en reading
interface ReadingCompletionListener {
    suspend fun completed(
        organizationId: UUID,
        userId: UUID,
        version: VersionRef,
    )
}

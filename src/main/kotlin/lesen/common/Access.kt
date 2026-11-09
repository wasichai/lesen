package lesen.common

import org.springframework.stereotype.Component
import wasichai.core.common.Actions
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService

// quién llama y qué puede hacer (ADR-0002 §3). Los permisos los decide Wasichai sobre dos objetos ancla de
// model/model.json, que no guardan datos: lectura (leer el catálogo) y cuaderno (lo personal de cada lector: perfil,
// progreso, marcadores, notas, vocabulario). Aquí nunca se mira un nombre de rol
@Component
class Access(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
) {
    // leer lecturas, temas, diccionario
    suspend fun reader(): Learner = require(Actions.READ, READING_ANCHOR)

    // leer lo propio
    suspend fun notebook(): Learner = require(Actions.READ, NOTEBOOK_ANCHOR)

    // escribir lo propio (crear, cambiar o borrar)
    suspend fun writeNotebook(): Learner = require(Actions.UPDATE, NOTEBOOK_ANCHOR)

    private suspend fun require(
        action: String,
        anchor: String,
    ): Learner {
        val user = currentUser.require()
        currentUser.requirePermission(user, action, metadata.loadDefinition(user.organizationId, anchor).obj.id)
        return Learner(user)
    }

    companion object {
        const val READING_ANCHOR = "lectura"
        const val NOTEBOOK_ANCHOR = "cuaderno"
    }
}

// la persona que llama, con su organización: toda consulta de lo personal filtra por los dos
data class Learner(
    val user: AuthenticatedUser,
) {
    val organizationId get() = user.organizationId
    val userId get() = user.userId
}

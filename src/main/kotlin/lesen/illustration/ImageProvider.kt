package lesen.illustration

import kotlinx.coroutines.reactor.awaitSingle
import lesen.common.Jsonb
import lesen.common.LesenProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs

// lo que devuelve un proveedor de imágenes: la imagen, una negativa o un fallo. Nunca una imagen de relleno (ADR-0005)
sealed interface Rendered {
    data class Image(
        val picture: Picture,
    ) : Rendered

    data class Refused(
        val reason: String,
    ) : Rendered

    data class Failed(
        val reason: String,
    ) : Rendered
}

// un proveedor de imágenes detrás de una interfaz (ADR-0005, ADR-0015). references: las imágenes que debe seguir
// (hojas de personaje, la página anterior, el fotograma de estilo), que es lo que hace coherente un libro
interface ImageProvider {
    val name: String
    val available: Boolean

    suspend fun render(
        prompt: String,
        references: List<Picture>,
        aspect: Aspect,
    ): Rendered
}

// sin proveedor: no hay ilustraciones, y el libro las tiene pendientes
object NoImageProvider : ImageProvider {
    override val name = "none"
    override val available = false

    override suspend fun render(
        prompt: String,
        references: List<Picture>,
        aspect: Aspect,
    ): Rendered = Rendered.Failed("no image provider is configured")
}

// imágenes de prueba (desarrollo, pruebas y la API simulada): un PNG del tamaño pedido con un color que sale del texto
// pedido. Determinista: lo mismo da la misma imagen. No pretende ser una ilustración: lo dice su nombre
class FakeImageProvider : ImageProvider {
    override val name = "fake"
    override val available = true
    val calls = mutableListOf<Triple<String, Int, Aspect>>()

    override suspend fun render(
        prompt: String,
        references: List<Picture>,
        aspect: Aspect,
    ): Rendered {
        synchronized(calls) { calls += Triple(prompt, references.size, aspect) }
        val width = aspect.width * 64
        val height = aspect.height * 64
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        val hash = abs(prompt.hashCode())
        g.color = Color.getHSBColor((hash % 360) / 360f, 0.35f, 0.9f)
        g.fillRect(0, 0, width, height)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.getHSBColor(((hash / 7) % 360) / 360f, 0.5f, 0.6f)
        g.fillOval(width / 4, height / 3, width / 2, height / 2)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return Rendered.Image(Picture("image/png", out.toByteArray()))
    }
}

// OpenAI (gpt-image): sin referencias, una imagen nueva; con ellas, la edición que las toma como imágenes de partida
class OpenAiImageProvider(
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val timeout: Duration,
) : ImageProvider {
    override val name = "openai"
    override val available get() = apiKey.isNotBlank()
    private val client =
        WebClient
            .builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(32 * 1_048_576) }
            .build()

    // los tamaños que admite gpt-image
    private fun size(aspect: Aspect) =
        when {
            aspect.width == aspect.height -> "1024x1024"
            aspect.width > aspect.height -> "1536x1024"
            else -> "1024x1536"
        }

    override suspend fun render(
        prompt: String,
        references: List<Picture>,
        aspect: Aspect,
    ): Rendered =
        call {
            val request =
                if (references.isEmpty()) {
                    client
                        .post()
                        .uri("/v1/images/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Jsonb.of(mapOf("model" to model, "prompt" to prompt, "size" to size(aspect), "n" to 1)))
                } else {
                    val parts = MultipartBodyBuilder()
                    parts.part("model", model)
                    parts.part("prompt", prompt)
                    parts.part("size", size(aspect))
                    references.forEachIndexed { i, picture ->
                        parts
                            .part(
                                "image[]",
                                object : ByteArrayResource(picture.bytes) {
                                    override fun getFilename() = "reference-$i.${extension(picture.contentType)}"
                                },
                            ).contentType(MediaType.parseMediaType(picture.contentType))
                    }
                    client
                        .post()
                        .uri("/v1/images/edits")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(parts.build()))
                }
            val body =
                request
                    .header("Authorization", "Bearer $apiKey")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(timeout)
                    .awaitSingle()
            val data =
                Jsonb.MAPPER
                    .readTree(body)
                    .path("data")
                    .path(0)
                    .path("b64_json")
            if (data.isMissingNode ||
                data.isNull
            ) {
                Rendered.Failed("no image in the response")
            } else {
                Rendered.Image(Picture("image/png", Base64.getDecoder().decode(data.asString())))
            }
        }
}

// Gemini (el modelo de imagen de la API de Gemini): el texto y las referencias van en el mismo mensaje. Imagen
// (imagen-*:predict) no admite referencias: aquí no se usa, porque sin ellas no hay coherencia entre páginas
class GeminiImageProvider(
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val timeout: Duration,
) : ImageProvider {
    override val name = "gemini"
    override val available get() = apiKey.isNotBlank()
    private val client =
        WebClient
            .builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(32 * 1_048_576) }
            .build()

    override suspend fun render(
        prompt: String,
        references: List<Picture>,
        aspect: Aspect,
    ): Rendered =
        call {
            val parts =
                listOf(mapOf("text" to prompt)) +
                    references.map { mapOf("inline_data" to mapOf("mime_type" to it.contentType, "data" to Base64.getEncoder().encodeToString(it.bytes))) }
            val request =
                mapOf(
                    "contents" to listOf(mapOf("parts" to parts)),
                    "generationConfig" to mapOf("responseModalities" to listOf("IMAGE"), "imageConfig" to mapOf("aspectRatio" to geminiRatio(aspect))),
                )
            val body =
                client
                    .post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Jsonb.of(request))
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(timeout)
                    .awaitSingle()
            val tree = Jsonb.MAPPER.readTree(body)
            val blocked = tree.path("promptFeedback").path("blockReason")
            if (!blocked.isMissingNode && !blocked.isNull) return@call Rendered.Refused(blocked.asString())
            val image =
                tree
                    .path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .firstOrNull { it.has("inlineData") }
                    ?.path("inlineData")
            when {
                image != null ->
                    Rendered.Image(
                        Picture(image.path("mimeType").asString("image/png"), Base64.getDecoder().decode(image.path("data").asString())),
                    )
                tree
                    .path("candidates")
                    .path(0)
                    .path("finishReason")
                    .asString("") in setOf("SAFETY", "PROHIBITED_CONTENT", "IMAGE_SAFETY") ->
                    Rendered.Refused("the provider declined to draw this page")
                else -> Rendered.Failed("no image in the response")
            }
        }

    // las proporciones que admite: la más cercana a la de la maqueta
    private fun geminiRatio(aspect: Aspect) =
        when (aspect) {
            Aspect.PORTRAIT -> "3:4"
            Aspect.LANDSCAPE -> "4:3"
            Aspect.WIDE -> "3:2"
            Aspect.PANORAMA -> "21:9"
            Aspect.SQUARE -> "1:1"
        }
}

private val log = LoggerFactory.getLogger(ImageProvider::class.java)

// un fallo de la red o del proveedor es un fallo de esa página, no del libro: se dice y se sigue
private suspend fun call(block: suspend () -> Rendered): Rendered =
    try {
        block()
    } catch (error: WebClientResponseException) {
        log.warn("proveedor de imágenes: {} {}", error.statusCode, error.responseBodyAsString.take(300))
        if (error.statusCode == HttpStatusCode.valueOf(400) && error.responseBodyAsString.contains("safety", ignoreCase = true)) {
            Rendered.Refused("the provider declined to draw this page")
        } else {
            Rendered.Failed("the image provider answered ${error.statusCode.value()}")
        }
    } catch (error: Exception) {
        log.warn("proveedor de imágenes: {}", error.toString())
        Rendered.Failed("the image provider did not answer")
    }

private fun extension(contentType: String) =
    when (contentType) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

@Configuration
class ImageProviders {
    // el proveedor de lesen.illustration.provider; sin su clave, ninguno (las ilustraciones quedan pendientes)
    @Bean
    fun imageProvider(properties: LesenProperties): ImageProvider {
        val p = properties.illustration
        val timeout = Duration.ofSeconds(p.timeoutSeconds)
        val provider =
            when (p.provider) {
                "fake" -> FakeImageProvider()
                "openai" -> OpenAiImageProvider(p.openaiApiKey, p.model.ifBlank { "gpt-image-1" }, p.openaiBaseUrl, timeout)
                "gemini" -> GeminiImageProvider(p.geminiApiKey, p.model.ifBlank { "gemini-2.5-flash-image" }, p.geminiBaseUrl, timeout)
                else -> NoImageProvider
            }
        if (!provider.available && p.provider != "none") log.warn("ilustraciones: el proveedor {} no tiene clave; quedan pendientes", p.provider)
        return if (provider.available) provider else NoImageProvider
    }
}

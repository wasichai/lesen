package lesen.generation

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.future.await
import lesen.common.Jsonb
import lesen.common.LesenProperties
import org.springframework.stereotype.Component
import java.time.Duration

// los tokens de una llamada: el coste queda medido, no supuesto
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    operator fun plus(o: TokenUsage) = TokenUsage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite)
}

// lo que vuelve de un intento: la lectura, o por qué no la hay
sealed interface Written {
    val usage: TokenUsage

    data class Reading(
        val reading: GeneratedReading,
        override val usage: TokenUsage,
    ) : Written

    // el modelo se negó (stop_reason refusal): no es un fallo de la persona ni del sistema
    data class Refused(
        override val usage: TokenUsage,
    ) : Written

    // la salida no se pudo leer (cortada, o fuera del esquema)
    data class Unreadable(
        val reason: String,
        override val usage: TokenUsage,
    ) : Written
}

// una conversación con quien escribe: el primer intento y, si falla la comprobación, las correcciones
interface WritingSession {
    suspend fun write(): Written

    suspend fun correct(findings: List<Finding>): Written
}

// escribe lecturas a medida. Lanza si no puede llegar al proveedor: quien llama reintenta o dice que falló, nunca finge
// una lectura (ADR-0005)
interface ReadingWriter {
    val available: Boolean

    fun start(context: GenerationContext): WritingSession
}

// Claude por la API de mensajes (SDK oficial, cliente asíncrono): streaming porque la respuesta es larga, salida
// estructurada con el esquema de GenerationPrompt y caché del prefijo. Sin clave no está disponible
@Component
class ClaudeReadingWriter(
    properties: LesenProperties,
) : ReadingWriter {
    private val ai = properties.ai
    private val generation = properties.generation
    private val client: AnthropicClientAsync? =
        if (!ai.enabled) {
            null
        } else {
            AnthropicOkHttpClientAsync
                .builder()
                .apiKey(ai.apiKey)
                .timeout(Duration.ofSeconds(generation.timeoutSeconds))
                // los reintentos los lleva quien llama (la cola de la fase 2), con su propia espera
                .maxRetries(0)
                .apply { if (ai.baseUrl.isNotBlank()) baseUrl(ai.baseUrl) }
                .build()
        }

    override val available get() = client != null

    override fun start(context: GenerationContext): WritingSession {
        val client = checkNotNull(client) { "no writer configured" }
        val format =
            JsonOutputFormat
                .builder()
                .schema(
                    JsonOutputFormat.Schema
                        .builder()
                        .apply { GenerationPrompt.schema(context).forEach { (key, value) -> putAdditionalProperty(key, JsonValue.from(value)) } }
                        .build(),
                ).build()
        return Session(client, format, context)
    }

    private inner class Session(
        private val client: AnthropicClientAsync,
        private val format: JsonOutputFormat,
        context: GenerationContext,
    ) : WritingSession {
        // la conversación: el texto de cada vuelta de la aplicación y cada respuesta entera (con su razonamiento)
        private val turns = mutableListOf<Turn>(Turn.User(GenerationPrompt.message(context)))

        override suspend fun write(): Written = send()

        override suspend fun correct(findings: List<Finding>): Written {
            turns += Turn.User(GenerationPrompt.correction(findings))
            return send()
        }

        private suspend fun send(): Written {
            val params =
                MessageCreateParams
                    .builder()
                    .model(ai.model)
                    .maxTokens(generation.maxTokens)
                    .system(GenerationPrompt.SYSTEM)
                    // caché automática: el sistema, el esquema y, en una corrección, el intento anterior
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .outputConfig(
                        OutputConfig
                            .builder()
                            .effort(OutputConfig.Effort.of(generation.effort))
                            .format(format)
                            .build(),
                    ).apply {
                        turns.forEach { turn ->
                            when (turn) {
                                is Turn.User -> addUserMessage(turn.text)
                                is Turn.Assistant -> addMessage(turn.message)
                            }
                        }
                    }.build()
            val message =
                try {
                    stream(params)
                } catch (error: AnthropicServiceException) {
                    throw IllegalStateException("Claude ${error.statusCode()}: ${error.errorType().orElse(null)}", error)
                }
            turns += Turn.Assistant(message)
            val usage =
                message.usage().let {
                    TokenUsage(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens().orElse(0), it.cacheCreationInputTokens().orElse(0))
                }
            return when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> Written.Refused(usage)
                StopReason.END_TURN -> read(message, usage)
                else -> Written.Unreadable("stop reason ${message.stopReason().orElse(null)}", usage)
            }
        }

        private fun read(
            message: Message,
            usage: TokenUsage,
        ): Written {
            val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
            return try {
                Written.Reading(Jsonb.read<GeneratedReading>(text), usage)
            } catch (error: Exception) {
                Written.Unreadable("output does not match the schema: ${error.message?.take(200)}", usage)
            }
        }
    }

    private sealed interface Turn {
        data class User(
            val text: String,
        ) : Turn

        data class Assistant(
            val message: Message,
        ) : Turn
    }

    private suspend fun stream(params: MessageCreateParams): Message {
        val accumulator = MessageAccumulator.create()
        val response = checkNotNull(client).messages().createStreaming(params)
        try {
            response.subscribe(
                object : AsyncStreamResponse.Handler<RawMessageStreamEvent> {
                    override fun onNext(value: RawMessageStreamEvent) {
                        accumulator.accumulate(value)
                    }
                },
            )
            response.onCompleteFuture().await()
        } finally {
            response.close()
        }
        return accumulator.message()
    }

    @PreDestroy
    fun close() {
        client?.close()
    }
}

package lesen.generation.eval

import lesen.common.Jsonb
import lesen.generation.GenerationPrompt
import lesen.generation.TokenUsage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// el juego de evaluación se arma sin la API: 30 perfiles × 3 peticiones, con perfiles que existen en el contenido de
// demostración y un mensaje de tamaño razonable
class EvalSetTest {
    private val knowledge = DemoKnowledge()
    private val set = Jsonb.read<EvalSet>(checkNotNull(javaClass.getResource("/generation-eval/profiles.json")).readText())

    @Test
    fun `thirty profiles from A1 to C1 in both study languages, three requests each`() {
        assertThat(set.profiles).hasSize(30)
        assertThat(set.profiles.map { it.language to it.level }.toSet()).hasSize(10)
        val cases = knowledge.cases(set)
        assertThat(cases).hasSize(90)
        assertThat(cases.map { it.id }.toSet()).hasSize(90)
        assertThat(cases.filter { it.mode == "free" }.map { it.context.request.prompt }).doesNotContainNull()
        assertThat(cases.filter { it.mode == "guided" }.map { it.context.request.prompt }).containsOnlyNulls()
    }

    @Test
    fun `profiles come from what the learner read, and the message stays small`() {
        val case = knowledge.cases(set).first { it.id == "de-a1-2-mixed" }
        assertThat(case.context.knownLemmas).contains("Brot")
        assertThat(case.context.targetWords).hasSize(8)
        assertThat(case.context.recentTitles).isNotEmpty()
        assertThat(case.context.translateInto).containsExactly("es", "en")
        val largest = knowledge.cases(set).maxOf { GenerationPrompt.message(it.context).length }
        // unos 4 caracteres por token: el perfil y el diccionario de demostración caben en menos de 4.000 tokens
        assertThat(largest / 4).isLessThan(4000)
    }

    @Test
    fun `cost follows the price of the model`() {
        assertThat(cost("claude-opus-5-5", TokenUsage(input = 1_000_000))).isEqualTo(4.0)
        assertThat(cost("claude-opus-5-5", TokenUsage(output = 1_000_000, cacheRead = 1_000_000))).isEqualTo(20.4)
        assertThat(cost("otro", TokenUsage(input = 1))).isNull()
    }
}

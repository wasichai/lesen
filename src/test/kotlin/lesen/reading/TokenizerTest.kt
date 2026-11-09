package lesen.reading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TokenizerTest {
    private val tokenizer = UnicodeTokenizer()

    private fun words(text: String) = tokenizer.tokenize("p1s1", text).filter { it.kind == TokenKind.WORD }.map { it.text }

    @Test
    fun `splits words and punctuation and records trailing spaces`() {
        val tokens = tokenizer.tokenize("p1s1", "Mia wohnt am Meer.")
        assertThat(tokens.map { it.text }).containsExactly("Mia", "wohnt", "am", "Meer", ".")
        assertThat(tokens.map { it.kind }).containsExactly(TokenKind.WORD, TokenKind.WORD, TokenKind.WORD, TokenKind.WORD, TokenKind.PUNCT)
        assertThat(tokens.map { it.space }).containsExactly(true, true, true, false, false)
        assertThat(tokens.map { it.id }).containsExactly("p1s1t1", "p1s1t2", "p1s1t3", "p1s1t4", "p1s1t5")
    }

    @Test
    fun `offsets rebuild the exact text`() {
        val text = "„Hallo!“, sagt sie — und lacht."
        val tokens = tokenizer.tokenize("p1s1", text)
        assertThat(tokens.joinToString("") { it.text + if (it.space) " " else "" }.trim()).isEqualTo(text)
        tokens.forEach { assertThat(text.substring(it.start, it.end)).isEqualTo(it.text) }
    }

    @Test
    fun `contractions, apostrophes and hyphens stay inside the word`() {
        assertThat(words("I don't know, it’s fine.")).containsExactly("I", "don't", "know", "it’s", "fine")
        assertThat(words("Die E-Mail kam per Nord-Ostsee-Kanal.")).containsExactly("Die", "E-Mail", "kam", "per", "Nord-Ostsee-Kanal")
        assertThat(words("Ein Wort - und noch eins")).containsExactly("Ein", "Wort", "und", "noch", "eins")
    }

    @Test
    fun `unicode, umlauts, eszett and combining marks`() {
        assertThat(words("Die Straße führt über Brücken.")).containsExactly("Die", "Straße", "führt", "über", "Brücken")
        // e + acento combinado (U+0301): una sola palabra
        assertThat(words("café noir")).containsExactly("café", "noir")
        assertThat(words("im Jahr 1989")).containsExactly("im", "Jahr", "1989")
    }

    @Test
    fun `normalizes for the dictionary without locale rules`() {
        assertThat(Tokenizer.normalize("Straße")).isEqualTo("straße")
        assertThat(Tokenizer.normalize("It’s")).isEqualTo("it's")
        assertThat(Tokenizer.normalize("café")).isEqualTo("café")
    }
}

package lesen.reading

import java.text.Normalizer

// separa una oración en palabras y signos (ADR-0004). Una palabra es una secuencia de letras, marcas o dígitos, que
// puede llevar dentro apóstrofos o guiones entre letras: don't, l'eau, E-Mail, Nord-Ostsee-Kanal quedan enteras;
// «— ¿Y?» son signos. Los espacios no son tokens: cada token dice si lo sigue un espacio. La regla es la misma para
// todos los idiomas de hoy (de, en, es); un idioma que necesite otra (japonés, chino) registra su propio tokenizador
fun interface Tokenizer {
    fun tokenize(
        sentenceId: String,
        text: String,
    ): List<Token>

    companion object {
        // la misma expresión que tools/check_demo.py
        val WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")

        // la forma con que se busca en el diccionario: NFC, apóstrofo recto y minúsculas sin depender de la región
        fun normalize(form: String): String = Normalizer.normalize(form, Normalizer.Form.NFC).replace('’', '\'').lowercase()
    }
}

class UnicodeTokenizer : Tokenizer {
    override fun tokenize(
        sentenceId: String,
        text: String,
    ): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 0

        fun add(
            start: Int,
            end: Int,
            kind: TokenKind,
        ) {
            index++
            val space = end < text.length && text[end].isWhitespace()
            tokens += Token("${sentenceId}t$index", text.substring(start, end), kind, start, end, space)
        }
        var cursor = 0
        for (match in Tokenizer.WORD_PATTERN.findAll(text)) {
            punctuation(text, cursor, match.range.first, ::add)
            add(match.range.first, match.range.last + 1, TokenKind.WORD)
            cursor = match.range.last + 1
        }
        punctuation(text, cursor, text.length, ::add)
        return tokens
    }

    // cada signo es un token; los espacios se saltan (quedan en el space del token anterior)
    private fun punctuation(
        text: String,
        from: Int,
        to: Int,
        add: (Int, Int, TokenKind) -> Unit,
    ) {
        var i = from
        while (i < to) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            if (!Character.isWhitespace(cp)) add(i, i + width, TokenKind.PUNCT)
            i += width
        }
    }
}

// el tokenizador de cada idioma; hoy todos usan la regla Unicode
class Tokenizers(
    private val byLanguage: Map<String, Tokenizer> = emptyMap(),
    private val default: Tokenizer = UnicodeTokenizer(),
) {
    fun forLanguage(language: String): Tokenizer = byLanguage[language] ?: default
}

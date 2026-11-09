package lesen.editorial

import lesen.common.Media
import lesen.common.MediaKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// el tipo de un archivo subido sale de sus primeros bytes; un SVG o un texto no pasan
class MediaSniffTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `audio and images are recognized by their signature`() {
        assertThat(Media.sniff("ID3abc".toByteArray())?.extension).isEqualTo("mp3")
        assertThat(Media.sniff(bytes(0xFF, 0xFB, 0x90, 0x00))?.contentType).isEqualTo("audio/mpeg")
        assertThat(Media.sniff("OggS\u0000".toByteArray())?.kind).isEqualTo(MediaKind.AUDIO)
        assertThat(Media.sniff(bytes(0, 0, 0, 0x20) + "ftypM4A ".toByteArray())?.extension).isEqualTo("m4a")
        assertThat(Media.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0))?.extension).isEqualTo("png")
        assertThat(Media.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0))?.kind).isEqualTo(MediaKind.COVER)
        assertThat(Media.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray())?.extension).isEqualTo("webp")
    }

    @Test
    fun `svg, html and text are refused`() {
        assertThat(Media.sniff("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray())).isNull()
        assertThat(Media.sniff("<html><script>".toByteArray())).isNull()
        assertThat(Media.sniff(ByteArray(0))).isNull()
    }
}

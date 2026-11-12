package com.printbook.app.data.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebpFormatTest {

    private fun webp(vararg chunks: Pair<String, Int>): ByteArray {
        val out = mutableListOf<Byte>()
        "RIFF".forEach { out += it.code.toByte() }
        repeat(4) { out += 0 }
        "WEBP".forEach { out += it.code.toByte() }
        chunks.forEach { (name, size) ->
            name.forEach { out += it.code.toByte() }
            out += (size and 0xFF).toByte(); out += ((size shr 8) and 0xFF).toByte(); out += 0; out += 0
            repeat(size + (size and 1)) { out += 0 }
        }
        repeat(8) { out += 0 }
        return out.toByteArray()
    }

    @Test
    fun `sem perda`() {
        assertThat(WebpFormat.kind(webp("VP8L" to 4))).isEqualTo(WebpFormat.Kind.LOSSLESS)
    }

    @Test
    fun `com perda`() {
        assertThat(WebpFormat.kind(webp("VP8 " to 4))).isEqualTo(WebpFormat.Kind.LOSSY)
    }

    @Test
    fun `formato estendido pula o bloco VP8X`() {
        assertThat(WebpFormat.kind(webp("VP8X" to 10, "VP8 " to 4))).isEqualTo(WebpFormat.Kind.LOSSY)
    }

    @Test
    fun `nao e webp`() {
        assertThat(WebpFormat.kind(ByteArray(32) { 1 })).isEqualTo(WebpFormat.Kind.UNKNOWN)
    }
}

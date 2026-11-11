package com.printbook.app.capture.profile

import com.google.common.truth.Truth.assertThat
import com.printbook.app.data.db.entity.SourceApp
import org.junit.Test

class CropMathTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val dark = 0xFF111111.toInt()

    private fun canvas(w: Int, h: Int, fill: Int = white) = IntArray(w * h) { fill }

    private fun IntArray.fillRect(w: Int, l: Int, t: Int, r: Int, b: Int, color: Int) {
        for (y in t until b) for (x in l until r) this[y * w + x] = color
    }

    @Test
    fun `normalizado para pixels`() {
        val r = CropMath.toPixels(NormRect(0f, 0.1f, 1f, 0.9f), 1000, 2000)
        assertThat(r).isEqualTo(PixelRect(0, 200, 1000, 1800))
    }

    @Test
    fun `kindle corta o rodape`() {
        val w = 108; val h = 234
        val r = CropMath.profileRect(CaptureProfile.forApp(SourceApp.KINDLE), canvas(w, h, black), w, h)
        assertThat(r.top).isEqualTo(0)
        assertThat(r.bottom).isEqualTo(222) // 0.95 * 234 ≈ 222
    }

    @Test
    fun `minha biblioteca corta barras so quando visiveis`() {
        val w = 108; val h = 234
        val profile = CaptureProfile.forApp(SourceApp.MINHA_BIBLIOTECA)

        val immersive = canvas(w, h)
        assertThat(CropMath.profileRect(profile, immersive, w, h)).isEqualTo(PixelRect(0, 0, w, h))

        val withBars = canvas(w, h).apply {
            fillRect(w, 0, 0, w, 24, dark)      // barra superior escura (até ~0.10)
            fillRect(w, 0, 206, w, h, dark)     // barra inferior
        }
        val r = CropMath.profileRect(profile, withBars, w, h)
        assertThat(r.top).isEqualTo(24)  // 0.1009 * 234 ≈ 23.6
        assertThat(r.bottom).isEqualTo(205) // 0.8782 * 234 ≈ 205.5
    }

    @Test
    fun `remove margens brancas ao redor da moldura da pagina`() {
        val w = 200; val h = 300
        val px = canvas(w, h).apply { fillRect(w, 40, 60, 160, 240, black) }
        val r = CropMath.trimUniformMargins(px, w, PixelRect(0, 0, w, h), step = 1, padding = 0)
        assertThat(r).isEqualTo(PixelRect(40, 60, 160, 240))
    }

    @Test
    fun `padding nao ultrapassa a regiao`() {
        val w = 100; val h = 100
        val px = canvas(w, h).apply { fillRect(w, 5, 5, 95, 95, black) }
        val r = CropMath.trimUniformMargins(px, w, PixelRect(0, 0, w, h), step = 1, padding = 12)
        assertThat(r).isEqualTo(PixelRect(0, 0, w, h))
    }

    @Test
    fun `sombra da barra e alca de rolagem nao impedem o corte das margens`() {
        // Caso real do Minha Biblioteca com as barras visíveis: logo abaixo da barra superior há
        // uma faixa de sombra cinza, e na borda direita uma alça de rolagem vertical.
        val w = 200; val h = 300
        val shadow = 0xFFDCDCDC.toInt()
        val handle = 0xFF9E9E9E.toInt()
        val px = canvas(w, h).apply {
            fillRect(w, 0, 0, w, 4, shadow)          // sombra da barra (topo da região)
            fillRect(w, 196, 90, 200, 160, handle)   // alça de rolagem na borda direita
            fillRect(w, 40, 60, 160, 240, black)     // página (moldura + texto)
        }
        val r = CropMath.trimUniformMargins(px, w, PixelRect(0, 0, w, h), step = 1, padding = 0)
        assertThat(r).isEqualTo(PixelRect(40, 60, 160, 240))
    }

    @Test
    fun `pagina toda uniforme e mantida`() {
        val w = 50; val h = 50
        val r = CropMath.trimUniformMargins(canvas(w, h), w, PixelRect(0, 0, w, h))
        assertThat(r).isEqualTo(PixelRect(0, 0, w, h))
    }
}

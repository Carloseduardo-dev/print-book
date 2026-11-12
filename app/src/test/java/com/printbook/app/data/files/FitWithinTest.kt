package com.printbook.app.data.files

import com.google.common.truth.Truth.assertThat
import com.printbook.app.data.files.PageFileStore.Companion.PAGE_IMPORT_MAX_HEIGHT
import com.printbook.app.data.files.PageFileStore.Companion.PAGE_IMPORT_MAX_WIDTH
import com.printbook.app.data.files.PageFileStore.Companion.fitWithin
import org.junit.Test

class FitWithinTest {

    private fun fit(w: Int, h: Int) = fitWithin(w, h, PAGE_IMPORT_MAX_WIDTH, PAGE_IMPORT_MAX_HEIGHT)

    @Test
    fun `imagem que ja cabe nao e reduzida`() {
        assertThat(fit(1080, 2400)).isNull()
        assertThat(fit(1600, 4000)).isNull()
    }

    @Test
    fun `foto larga reduz pela largura mantendo a proporcao`() {
        assertThat(fit(4000, 3000)).isEqualTo(1600 to 1200)
    }

    @Test
    fun `print longo de rolagem reduz pela altura`() {
        // 1440x30000 abriria inteiro só com o limite de largura (~172 MB).
        val (w, h) = fit(1440, 30000)!!
        assertThat(h).isEqualTo(4000)
        assertThat(w).isEqualTo(192)
    }

    @Test
    fun `panorama muito largo nunca fica com altura zero`() {
        val (w, h) = fit(100_000, 10)!!
        assertThat(w).isEqualTo(1600)
        assertThat(h).isAtLeast(1)
    }

    @Test
    fun `tamanho invalido nao reduz`() {
        assertThat(fit(0, 0)).isNull()
        assertThat(fit(-1, 500)).isNull()
    }
}

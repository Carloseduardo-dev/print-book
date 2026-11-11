package com.printbook.app.data.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextToolsTest {

    @Test
    fun `consulta vira prefixos sem acento`() {
        assertThat(TextTools.ftsQuery("Método de Euler")).isEqualTo("metodo* de* euler*")
    }

    @Test
    fun `operadores e aspas sao removidos`() {
        assertThat(TextTools.ftsQuery("\"x\" OR (aproximação)*")).isEqualTo("or* aproximacao*")
    }

    @Test
    fun `entrada vazia ou curta retorna null`() {
        assertThat(TextTools.ftsQuery("  ")).isNull()
        assertThat(TextTools.ftsQuery("a")).isNull()
    }

    @Test
    fun `detecta ingles`() {
        val en = "A class will often need to perform some initialization tasks at the point of creation. " +
            "These tasks can be implemented using constructors within the class."
        assertThat(TextTools.guessLanguage(en)).isEqualTo(TextTools.Language.EN)
    }

    @Test
    fun `detecta portugues`() {
        val pt = "Observe que cada entrada na última coluna torna-se a próxima entrada na terceira coluna. " +
            "Isso é semelhante ao método de Newton, no qual cada aproximação é usada para encontrar a seguinte."
        assertThat(TextTools.guessLanguage(pt)).isEqualTo(TextTools.Language.PT)
    }

    @Test
    fun `texto vazio assume portugues`() {
        assertThat(TextTools.guessLanguage("")).isEqualTo(TextTools.Language.PT)
    }

    @Test
    fun `limpa hifenizacao e quebras para fala`() {
        assertThat(TextTools.forSpeech("independente-\nmente das\nlinhas"))
            .isEqualTo("independentemente das linhas")
    }
}

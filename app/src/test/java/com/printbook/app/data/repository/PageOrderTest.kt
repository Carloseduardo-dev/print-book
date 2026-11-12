package com.printbook.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageOrderTest {

    private val ids = listOf(1L, 2L, 3L, 4L, 5L)

    @Test
    fun `inserir no fim`() {
        assertThat(PageOrder.insertAfter(ids, listOf(9L, 10L), null)).containsExactly(1L, 2L, 3L, 4L, 5L, 9L, 10L).inOrder()
    }

    @Test
    fun `inserir depois da terceira pagina`() {
        assertThat(PageOrder.insertAfter(ids, listOf(9L, 10L), 2)).containsExactly(1L, 2L, 3L, 9L, 10L, 4L, 5L).inOrder()
    }

    @Test
    fun `inserir no inicio`() {
        assertThat(PageOrder.insertAfter(ids, listOf(9L), -1)).containsExactly(9L, 1L, 2L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun `inserir quando a pagina nova ja foi anexada no fim`() {
        // Captura: addPage anexa no fim e depois reposiciona.
        val withNew = ids + 9L
        assertThat(PageOrder.insertAfter(withNew, listOf(9L), 1)).containsExactly(1L, 2L, 9L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun `inserir com ancora fora da lista vai para o fim`() {
        assertThat(PageOrder.insertAfter(ids, listOf(9L), 40)).containsExactly(1L, 2L, 3L, 4L, 5L, 9L).inOrder()
    }

    @Test
    fun `inserir em lista vazia`() {
        assertThat(PageOrder.insertAfter(emptyList(), listOf(9L), 0)).containsExactly(9L)
    }

    @Test
    fun `mover uma pagina para o inicio`() {
        assertThat(PageOrder.moveTo(ids, setOf(4L), 0)).containsExactly(4L, 1L, 2L, 3L, 5L).inOrder()
    }

    @Test
    fun `mover varias paginas para o fim mantendo a ordem entre elas`() {
        assertThat(PageOrder.moveTo(ids, setOf(4L, 1L), 99)).containsExactly(2L, 3L, 5L, 1L, 4L).inOrder()
    }

    @Test
    fun `mover para o meio`() {
        // Resto sem as selecionadas: [1, 3, 5]; alvo 1 = antes do 3.
        assertThat(PageOrder.moveTo(ids, setOf(2L, 4L), 1)).containsExactly(1L, 2L, 4L, 3L, 5L).inOrder()
    }

    @Test
    fun `mover sem selecao nao muda nada`() {
        assertThat(PageOrder.moveTo(ids, emptySet(), 2)).isEqualTo(ids)
    }

    @Test
    fun `arrastar para frente e para tras`() {
        assertThat(PageOrder.move(ids, 0, 3)).containsExactly(2L, 3L, 4L, 1L, 5L).inOrder()
        assertThat(PageOrder.move(ids, 4, 1)).containsExactly(1L, 5L, 2L, 3L, 4L).inOrder()
        assertThat(PageOrder.move(ids, 9, 1)).isEqualTo(ids)
    }
}

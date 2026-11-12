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

    // ---- Gravar a ordem do arrastar (reconcile) ----

    @Test
    fun `reconcile mantem a ordem da tela quando nada mudou no banco`() {
        assertThat(PageOrder.reconcile(ids, listOf(3L, 1L, 2L, 5L, 4L))).containsExactly(3L, 1L, 2L, 5L, 4L).inOrder()
    }

    @Test
    fun `reconcile manda para o fim a pagina capturada durante o arrasto`() {
        val current = ids + 6L
        assertThat(PageOrder.reconcile(current, listOf(2L, 1L, 3L, 4L, 5L))).containsExactly(2L, 1L, 3L, 4L, 5L, 6L).inOrder()
    }

    @Test
    fun `reconcile ignora pagina excluida e ids repetidos`() {
        val current = listOf(1L, 2L, 4L, 5L) // a 3 foi excluída
        assertThat(PageOrder.reconcile(current, listOf(5L, 3L, 1L, 1L, 2L, 4L))).containsExactly(5L, 1L, 2L, 4L).inOrder()
    }

    @Test
    fun `reconcile nunca repete nem perde paginas`() {
        val current = (1L..30L).toList()
        val ordered = listOf(30L, 99L, 7L, 7L, 1L)
        val result = PageOrder.reconcile(current, ordered)
        assertThat(result).hasSize(current.size)
        assertThat(result.toSet()).isEqualTo(current.toSet())
    }

    // ---- Mover para "antes da página N" ----

    @Test
    fun `antes da pagina N com uma selecionada que vem depois`() {
        // Mover a 5 para antes da página 2 → [1, 5, 2, 3, 4]
        val index = PageOrder.targetBefore(ids, setOf(5L), 2)
        assertThat(PageOrder.moveTo(ids, setOf(5L), index)).containsExactly(1L, 5L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `antes da pagina N com uma selecionada que vem antes`() {
        // Mover a 1 para antes da página 4 → [2, 3, 1, 4, 5]
        val index = PageOrder.targetBefore(ids, setOf(1L), 4)
        assertThat(PageOrder.moveTo(ids, setOf(1L), index)).containsExactly(2L, 3L, 1L, 4L, 5L).inOrder()
    }

    @Test
    fun `antes da pagina N quando a propria pagina N esta selecionada`() {
        // Selecionadas 2 e 3, antes da página 3 → ficam antes da 4: [1, 2, 3, 4, 5]
        val index = PageOrder.targetBefore(ids, setOf(2L, 3L), 3)
        assertThat(PageOrder.moveTo(ids, setOf(2L, 3L), index)).isEqualTo(ids)
    }

    @Test
    fun `antes da pagina 1 equivale ao inicio`() {
        assertThat(PageOrder.targetBefore(ids, setOf(4L), 1)).isEqualTo(0)
        assertThat(PageOrder.targetBefore(ids, setOf(4L), 0)).isEqualTo(0)
    }

    // ---- Captura inserindo num ponto X ----

    @Test
    fun `sessao de captura sem ponto entra no fim`() {
        assertThat(PageOrder.sessionInsertAfter(null, 0)).isNull()
        assertThat(PageOrder.sessionInsertAfter(null, 7)).isNull()
    }

    @Test
    fun `capturas depois da pagina 3 entram como 4, 5 e 6 em sequencia`() {
        // Simula a gravadora: cada captura é anexada no fim e reposicionada.
        var order = ids
        val captured = listOf(10L, 11L, 12L)
        captured.forEachIndexed { i, id ->
            order = PageOrder.insertAfter(order + id, listOf(id), PageOrder.sessionInsertAfter(2, i))
        }
        assertThat(order).containsExactly(1L, 2L, 3L, 10L, 11L, 12L, 4L, 5L).inOrder()
    }

    @Test
    fun `capturas no inicio mantem a ordem entre elas`() {
        var order = ids
        listOf(10L, 11L).forEachIndexed { i, id ->
            order = PageOrder.insertAfter(order + id, listOf(id), PageOrder.sessionInsertAfter(-1, i))
        }
        assertThat(order).containsExactly(10L, 11L, 1L, 2L, 3L, 4L, 5L).inOrder()
    }

    // ---- Destaque das páginas novas ----

    @Test
    fun `primeira carga nao destaca nada`() {
        assertThat(PageOrder.added(null, ids.toSet())).isEmpty()
    }

    @Test
    fun `destaca so as paginas que entraram`() {
        assertThat(PageOrder.added(ids.toSet(), (ids + listOf(8L, 9L)).toSet())).containsExactly(8L, 9L)
        assertThat(PageOrder.added(ids.toSet(), setOf(1L, 2L))).isEmpty()
    }
}

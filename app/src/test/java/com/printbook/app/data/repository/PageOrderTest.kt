package com.printbook.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageOrderTest {

    private val ids = listOf(1L, 2L, 3L, 4L, 5L)

    /**
     * Simula a transação `insertAt`: resolve a posição pela âncora (id) e abre espaço.
     * Devolve a nova ordem.
     */
    private fun insert(order: List<Long>, newId: Long, point: InsertPoint): List<Long> {
        val anchor = (point as? InsertPoint.After)?.let { a -> order.indexOf(a.pageId).takeIf { it >= 0 } }
        val pos = PageOrder.insertPosition(point, order.lastIndex, anchor)
        return order.toMutableList().apply { add(pos, newId) }
    }

    /** Uma sessão de inserção: cada página entra depois da anterior (como a gravadora faz). */
    private fun insertSequence(order: List<Long>, newIds: List<Long>, start: InsertPoint): Pair<List<Long>, InsertPoint> {
        var o = order
        var point = start
        newIds.forEach { id ->
            o = insert(o, id, point)
            point = PageOrder.chain(point, id)
        }
        return o to point
    }

    // ---- Posição de inserção ----

    @Test
    fun `inserir no fim, no inicio e depois de uma pagina`() {
        assertThat(insert(ids, 9L, InsertPoint.End)).containsExactly(1L, 2L, 3L, 4L, 5L, 9L).inOrder()
        assertThat(insert(ids, 9L, InsertPoint.Start)).containsExactly(9L, 1L, 2L, 3L, 4L, 5L).inOrder()
        assertThat(insert(ids, 9L, InsertPoint.After(3L))).containsExactly(1L, 2L, 3L, 9L, 4L, 5L).inOrder()
    }

    @Test
    fun `ancora que nao existe mais vai para o fim`() {
        assertThat(PageOrder.insertPosition(InsertPoint.After(99L), maxPosition = 4, anchorPosition = null)).isEqualTo(5)
    }

    @Test
    fun `inserir em livro vazio`() {
        assertThat(insert(emptyList(), 9L, InsertPoint.End)).containsExactly(9L)
        assertThat(insert(emptyList(), 9L, InsertPoint.Start)).containsExactly(9L)
    }

    @Test
    fun `capturas depois da pagina 3 entram como 4, 5 e 6 em sequencia`() {
        val (order, _) = insertSequence(ids, listOf(10L, 11L, 12L), InsertPoint.After(3L))
        assertThat(order).containsExactly(1L, 2L, 3L, 10L, 11L, 12L, 4L, 5L).inOrder()
    }

    @Test
    fun `capturas no inicio mantem a ordem entre elas`() {
        val (order, _) = insertSequence(ids, listOf(10L, 11L), InsertPoint.Start)
        assertThat(order).containsExactly(10L, 11L, 1L, 2L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun `sessao no fim continua no fim`() {
        assertThat(PageOrder.chain(InsertPoint.End, 42L)).isEqualTo(InsertPoint.End)
        assertThat(PageOrder.chain(InsertPoint.Start, 42L)).isEqualTo(InsertPoint.After(42L))
    }

    // ---- Casos do review: a âncora por página resiste a mudanças no meio do caminho ----

    @Test
    fun `retomar depois de interrupcao continua depois das ja inseridas`() {
        // Sessão 1: A e B depois da página 5. A tela bloqueia; a configuração guardou a âncora.
        val (afterFirst, saved) = insertSequence(ids, listOf(100L, 101L), InsertPoint.After(5L))
        // Sessão 2 ("Continuar captura") parte da âncora guardada: C e D vêm depois de B.
        val (order, _) = insertSequence(afterFirst, listOf(102L, 103L), saved)
        assertThat(order).containsExactly(1L, 2L, 3L, 4L, 5L, 100L, 101L, 102L, 103L).inOrder()
    }

    @Test
    fun `excluir a ultima inserida pela bolha reancora na anterior`() {
        val start = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        var (order, point) = insertSequence(start, listOf(100L, 101L), InsertPoint.After(4L)) // ..4, A, B, 5..
        // Exclui B (a âncora atual): o serviço passa a âncora para a página antes de B.
        val i = order.indexOf(101L)
        point = if (i == 0) InsertPoint.Start else InsertPoint.After(order[i - 1])
        order = order - 101L
        order = insert(order, 102L, point)
        assertThat(order).containsExactly(1L, 2L, 3L, 4L, 100L, 102L, 5L, 6L, 7L).inOrder()
    }

    @Test
    fun `reordenar no meio da sessao nao desloca o ponto`() {
        var (order, point) = insertSequence(ids, listOf(100L), InsertPoint.After(2L)) // 1, 2, A, 3, 4, 5
        order = PageOrder.moveTo(order, setOf(5L), 0) // a pessoa leva a 5 para o início
        order = insert(order, 101L, point)
        assertThat(order).containsExactly(5L, 1L, 2L, 100L, 101L, 3L, 4L).inOrder()
    }

    // ---- Gravar a ordem do arrastar (reconcile) ----

    @Test
    fun `reconcile mantem a ordem da tela quando nada mudou no banco`() {
        assertThat(PageOrder.reconcile(ids, listOf(3L, 1L, 2L, 5L, 4L))).containsExactly(3L, 1L, 2L, 5L, 4L).inOrder()
    }

    @Test
    fun `reconcile mantem no ponto X a pagina inserida durante o arrasto`() {
        // No banco, X (9) entrou depois da 2. Na tela, a pessoa arrastou a 5 para o início.
        val current = listOf(1L, 2L, 9L, 3L, 4L, 5L)
        assertThat(PageOrder.reconcile(current, listOf(5L, 1L, 2L, 3L, 4L))).containsExactly(5L, 1L, 2L, 9L, 3L, 4L).inOrder()
    }

    @Test
    fun `reconcile com pagina nova no inicio e outra que segue a sua ancora`() {
        // 8 entrou no início; 9 entrou depois da 3. A pessoa inverteu 1, 2, 3 na tela.
        val current = listOf(8L, 1L, 2L, 3L, 9L)
        assertThat(PageOrder.reconcile(current, listOf(3L, 2L, 1L))).containsExactly(8L, 3L, 9L, 2L, 1L).inOrder()
    }

    @Test
    fun `reconcile ignora pagina excluida e ids repetidos`() {
        val current = listOf(1L, 2L, 4L, 5L) // a 3 foi excluída
        assertThat(PageOrder.reconcile(current, listOf(5L, 3L, 1L, 1L, 2L, 4L))).containsExactly(5L, 1L, 2L, 4L).inOrder()
    }

    @Test
    fun `reconcile nunca repete nem perde paginas`() {
        val current = (1L..30L).toList()
        val result = PageOrder.reconcile(current, listOf(30L, 99L, 7L, 7L, 1L))
        assertThat(result).hasSize(current.size)
        assertThat(result.toSet()).isEqualTo(current.toSet())
    }

    // ---- Mover ----

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

    @Test
    fun `antes da pagina N com uma selecionada que vem depois`() {
        val index = PageOrder.targetBefore(ids, setOf(5L), 2)
        assertThat(PageOrder.moveTo(ids, setOf(5L), index)).containsExactly(1L, 5L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `antes da pagina N com uma selecionada que vem antes`() {
        val index = PageOrder.targetBefore(ids, setOf(1L), 4)
        assertThat(PageOrder.moveTo(ids, setOf(1L), index)).containsExactly(2L, 3L, 1L, 4L, 5L).inOrder()
    }

    @Test
    fun `antes da pagina N quando a propria pagina N esta selecionada`() {
        val index = PageOrder.targetBefore(ids, setOf(2L, 3L), 3)
        assertThat(PageOrder.moveTo(ids, setOf(2L, 3L), index)).isEqualTo(ids)
    }

    @Test
    fun `antes da pagina 1 equivale ao inicio`() {
        assertThat(PageOrder.targetBefore(ids, setOf(4L), 1)).isEqualTo(0)
        assertThat(PageOrder.targetBefore(ids, setOf(4L), 0)).isEqualTo(0)
    }

    // ---- Leitura acompanha a página ----

    private fun positions(order: List<Long>) = order.mapIndexed { i, id -> id to i }

    @Test
    fun `leitura segue a pagina quando outras sao movidas para antes dela`() {
        // Lendo a página 4 (id 4, posição 3); a 5 vai para o início.
        val newOrder = PageOrder.moveTo(ids, setOf(5L), 0)
        assertThat(PageOrder.followLastRead(positions(ids), 3, newOrder)).isEqualTo(4)
    }

    @Test
    fun `leitura nao muda quando a ordem antes dela nao muda`() {
        val newOrder = PageOrder.move(ids, 4, 3) // troca 4 e 5, lendo a 2
        assertThat(PageOrder.followLastRead(positions(ids), 1, newOrder)).isNull()
    }

    @Test
    fun `leitura numa pagina excluida vai para a anterior`() {
        // Lendo a posição 3 (id 4); a 4 foi excluída: as restantes ainda têm as posições antigas.
        val old = listOf(1L to 0, 2L to 1, 3L to 2, 5L to 4)
        assertThat(PageOrder.followLastRead(old, 3, listOf(1L, 2L, 3L, 5L))).isEqualTo(2)
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

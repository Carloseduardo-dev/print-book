package com.printbook.app.data.repository

/**
 * Onde entram páginas novas. Ancorado numa PÁGINA (id), não num número de posição: exclusões,
 * reordenações e retomadas no meio do caminho não deslocam o ponto escolhido.
 */
sealed interface InsertPoint {
    data object End : InsertPoint
    data object Start : InsertPoint
    data class After(val pageId: Long) : InsertPoint
}

/**
 * Ordem das páginas de um print-book: funções puras sobre a lista de ids (na ordem atual).
 * O resultado vai para `EditorTransactions.reorder`, que regrava as posições 0..n-1.
 */
object PageOrder {

    /**
     * Posição em que a página nova entra. [anchorPosition] é a posição atual da página de
     * [InsertPoint.After] (null se ela já não existe: aí a página vai para o fim).
     */
    fun insertPosition(point: InsertPoint, maxPosition: Int, anchorPosition: Int?): Int = when (point) {
        InsertPoint.End -> maxPosition + 1
        InsertPoint.Start -> 0
        is InsertPoint.After -> anchorPosition?.plus(1) ?: (maxPosition + 1)
    }

    /** Próximo ponto de uma sequência: cada página entra logo depois da anterior ([insertedId]). */
    fun chain(point: InsertPoint, insertedId: Long): InsertPoint =
        if (point == InsertPoint.End) InsertPoint.End else InsertPoint.After(insertedId)

    /**
     * Move as páginas [selected] (mantendo a ordem relativa entre elas) para que a primeira fique
     * na posição [targetIndex] da lista final. Valores fora do intervalo vão para o início/fim.
     */
    fun moveTo(ids: List<Long>, selected: Set<Long>, targetIndex: Int): List<Long> {
        val moving = ids.filter { it in selected }
        if (moving.isEmpty()) return ids
        val rest = ids.filterNot { it in selected }
        val at = targetIndex.coerceIn(0, rest.size)
        return rest.subList(0, at) + moving + rest.subList(at, rest.size)
    }

    /**
     * Ordem a gravar a partir da ordem montada na tela ([ordered]) e das páginas que existem agora
     * ([current]): ids que sumiram são ignorados e repetidos contam uma vez. Páginas que chegaram
     * depois (captura ou importação em andamento) ficam logo depois da página que as precedia no
     * banco, não no fim: uma inserção num ponto X continua no ponto X.
     */
    fun reconcile(current: List<Long>, ordered: List<Long>): List<Long> {
        val known = current.toSet()
        val result = ordered.filter { it in known }.distinct().toMutableList()
        val placed = result.toHashSet()
        current.forEachIndexed { i, id ->
            if (id in placed) return@forEachIndexed
            val before = current.getOrNull(i - 1)
            val at = if (before == null) 0 else result.indexOf(before) + 1
            result.add(at, id)
            placed += id
        }
        return result
    }

    /**
     * Índice para [moveTo] que deixa as selecionadas logo antes da página [pageNumber]
     * (numeração atual, 1-based): quantas páginas não selecionadas vêm antes dela.
     */
    fun targetBefore(ids: List<Long>, selected: Set<Long>, pageNumber: Int): Int =
        ids.take((pageNumber - 1).coerceAtLeast(0)).count { it !in selected }

    /**
     * Nova posição de leitura depois de reordenar: segue a página em que a pessoa estava.
     * [old] = (id, posição) antes da mudança; se a página da leitura foi excluída, segue a
     * anterior mais próxima. null = nada a mudar.
     */
    fun followLastRead(old: List<Pair<Long, Int>>, lastRead: Int, newOrder: List<Long>): Int? {
        val id = old.filter { it.second <= lastRead }.maxByOrNull { it.second }?.first ?: return null
        val index = newOrder.indexOf(id)
        return index.takeIf { it >= 0 && it != lastRead }
    }

    /** Ids que apareceram de uma emissão para a outra (páginas recém-inseridas). */
    fun added(previous: Set<Long>?, now: Set<Long>): Set<Long> = if (previous == null) emptySet() else now - previous

    /** Move uma página de [from] para [to] (arrastar e soltar). */
    fun move(ids: List<Long>, from: Int, to: Int): List<Long> {
        if (from !in ids.indices) return ids
        val list = ids.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return list
    }
}

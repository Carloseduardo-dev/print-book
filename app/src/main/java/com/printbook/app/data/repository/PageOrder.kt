package com.printbook.app.data.repository

/**
 * Ordem das páginas de um print-book: funções puras sobre a lista de ids (na ordem atual).
 * O resultado vai para `EditorTransactions.reorder`, que regrava as posições 0..n-1.
 */
object PageOrder {

    /**
     * Coloca [newIds] logo depois da página na posição [afterIndex] (0-based).
     * `null` = no fim; `-1` = no início. Ids novos que já estejam na lista são movidos.
     */
    fun insertAfter(ids: List<Long>, newIds: List<Long>, afterIndex: Int?): List<Long> {
        val rest = ids.filterNot { it in newIds }
        if (afterIndex == null) return rest + newIds
        // afterIndex se refere à lista original; recalcula na lista sem os ids novos.
        val anchor = ids.getOrNull(afterIndex)
        val at = when {
            afterIndex < 0 -> 0
            anchor == null -> rest.size
            anchor in newIds -> rest.size
            else -> rest.indexOf(anchor) + 1
        }
        return rest.subList(0, at) + newIds + rest.subList(at, rest.size)
    }

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
     * ([current]): ids que sumiram são ignorados, repetidos contam uma vez e páginas que chegaram
     * depois (captura em andamento) vão para o fim. Assim as posições nunca se repetem.
     */
    fun reconcile(current: List<Long>, ordered: List<Long>): List<Long> {
        val known = current.toSet()
        val kept = ordered.filter { it in known }.distinct()
        val keptSet = kept.toSet()
        return kept + current.filterNot { it in keptSet }
    }

    /**
     * Índice para [moveTo] que deixa as selecionadas logo antes da página [pageNumber]
     * (numeração atual, 1-based): quantas páginas não selecionadas vêm antes dela.
     */
    fun targetBefore(ids: List<Long>, selected: Set<Long>, pageNumber: Int): Int =
        ids.take((pageNumber - 1).coerceAtLeast(0)).count { it !in selected }

    /**
     * Posição de inserção da captura de número [index] (0-based) de uma sessão que começou a
     * inserir depois de [startAfter]: cada captura entra logo depois da anterior.
     */
    fun sessionInsertAfter(startAfter: Int?, index: Int): Int? = startAfter?.let { it + index }

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

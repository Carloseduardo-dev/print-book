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

    /** Move uma página de [from] para [to] (arrastar e soltar). */
    fun move(ids: List<Long>, from: Int, to: Int): List<Long> {
        if (from !in ids.indices) return ids
        val list = ids.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return list
    }
}

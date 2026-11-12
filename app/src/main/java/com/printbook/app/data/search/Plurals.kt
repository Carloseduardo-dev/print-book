package com.printbook.app.data.search

/** Contagens em português com singular/plural certos ("1 página", "2 páginas"). */
object Plurals {
    fun count(n: Number, singular: String, plural: String): String =
        "$n ${if (n.toLong() == 1L) singular else plural}"

    fun pages(n: Number) = count(n, "página", "páginas")
    fun books(n: Number) = count(n, "print-book", "print-books")

    /** "1 página capturada" / "3 páginas capturadas". */
    fun pagesCaptured(n: Number) = count(n, "página capturada", "páginas capturadas")
    fun pagesSaved(n: Number) = count(n, "página salva", "páginas salvas")
}

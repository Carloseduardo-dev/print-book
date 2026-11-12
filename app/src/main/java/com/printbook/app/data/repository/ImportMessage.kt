package com.printbook.app.data.repository

/** Texto do resultado de "Imagens da galeria" (usado no Detalhe e no Editor). */
fun importMessage(r: BookRepository.ImportResult): String = buildString {
    when {
        r.added == 0 -> append("Nenhuma imagem adicionada")
        r.added == 1 -> append("1 imagem adicionada")
        else -> append("${r.added} imagens adicionadas")
    }
    if (r.failed > 0) append(if (r.failed == 1) " · 1 não pôde ser aberta" else " · ${r.failed} não puderam ser abertas")
    append(".")
}

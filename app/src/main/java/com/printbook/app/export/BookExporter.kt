package com.printbook.app.export

import android.net.Uri

/**
 * Exporta um print-book para arquivo. Implementação real (PDF) só no flavor "pessoal";
 * o flavor "loja" usa [NoOpBookExporter] e o botão nem aparece.
 */
interface BookExporter {
    val available: Boolean

    /** Gera o PDF em [target] (URI do seletor de arquivos). [onProgress] recebe (feitas, total). */
    suspend fun exportPdf(bookId: Long, target: Uri, onProgress: (Int, Int) -> Unit): Result<Int>
}

class NoOpBookExporter : BookExporter {
    override val available: Boolean = false
    override suspend fun exportPdf(bookId: Long, target: Uri, onProgress: (Int, Int) -> Unit): Result<Int> =
        Result.failure(UnsupportedOperationException("Exportação indisponível nesta versão"))
}

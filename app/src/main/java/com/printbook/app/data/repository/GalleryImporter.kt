package com.printbook.app.data.repository

import android.net.Uri
import com.printbook.app.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Andamento/resultado de "Imagens da galeria" de um print-book. */
data class ImportStatus(
    val bookId: Long,
    val running: Boolean,
    val done: Int,
    val total: Int,
    val message: String,
) {
    val progress: Float get() = if (total > 0) done.toFloat() / total else 1f
}

/**
 * Importa imagens da galeria fora da tela: roda no escopo do app, então sair do Detalhe/Editor
 * no meio não interrompe a importação nem deixa de enfileirar o OCR.
 */
@Singleton
class GalleryImporter @Inject constructor(
    private val books: BookRepository,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<Map<Long, ImportStatus>>(emptyMap())
    val status: StateFlow<Map<Long, ImportStatus>> = _status.asStateFlow()

    fun start(bookId: Long, uris: List<Uri>, point: InsertPoint) {
        if (uris.isEmpty()) return
        set(ImportStatus(bookId, running = true, done = 0, total = uris.size, message = startMessage(uris.size)))
        scope.launch {
            val quality = settings.settings.first().pageQuality
            val result = runCatching {
                books.importImages(bookId, uris, point, quality) { done, total ->
                    set(ImportStatus(bookId, running = true, done = done, total = total, message = "Adicionando imagens: $done de $total"))
                }
            }
            val message = result.fold({ importMessage(it) }, { "Não foi possível adicionar as imagens." })
            set(ImportStatus(bookId, running = false, done = uris.size, total = uris.size, message = message))
        }
    }

    /** Some com a mensagem de resultado (a importação em andamento não é afetada). */
    fun dismiss(bookId: Long) = _status.update { m -> if (m[bookId]?.running == true) m else m - bookId }

    private fun set(s: ImportStatus) = _status.update { it + (s.bookId to s) }

    private fun startMessage(n: Int) = "Adicionando $n ${if (n == 1) "imagem" else "imagens"}…"
}

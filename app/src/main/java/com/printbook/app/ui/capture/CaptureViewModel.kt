package com.printbook.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.printbook.app.capture.PageTurner
import com.printbook.app.capture.service.CaptureConfig
import com.printbook.app.capture.service.ContinueRequest
import com.printbook.app.capture.service.CaptureStateHolder
import com.printbook.app.capture.service.CaptureUiState
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.SourceApp
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.data.prefs.PageQuality
import com.printbook.app.data.prefs.Settings
import com.printbook.app.data.prefs.SettingsRepository
import com.printbook.app.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val stateHolder: CaptureStateHolder,
    private val books: BookRepository,
    private val settingsRepo: SettingsRepository,
    private val pageTurner: PageTurner,
) : ViewModel() {

    /** Build com automação (flavor pessoal). */
    val supportsAutomation: Boolean = pageTurner.supportsAutomation

    /** Serviço de Acessibilidade ativo. */
    val automationAvailable: StateFlow<Boolean> = pageTurner.isAvailable

    val captureState: StateFlow<CaptureUiState> = stateHolder.state

    /** Pedido de "adicionar páginas" vindo do Detalhe/Editor. */
    val continueRequest: StateFlow<ContinueRequest?> = stateHolder.continueRequest
    fun cancelContinue() = stateHolder.requestContinue(null)

    val settings: StateFlow<Settings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    /** Livros com captura em andamento, para "continuar capturando". */
    val capturingBooks: StateFlow<List<BookEntity>> =
        books.observeCapturing().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setMode(mode: CaptureMode) = viewModelScope.launch { settingsRepo.setCaptureMode(mode) }
    fun setSourceApp(app: SourceApp) = viewModelScope.launch { settingsRepo.setLastSourceApp(app) }
    fun setAutoCrop(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoCrop(v) }
    fun setSkipDuplicates(v: Boolean) = viewModelScope.launch { settingsRepo.setSkipDuplicates(v) }
    fun setQuality(v: PageQuality) = viewModelScope.launch { settingsRepo.setPageQuality(v) }
    fun setTurbo(v: Boolean) = viewModelScope.launch { settingsRepo.setTurbo(v) }
    fun setWholeBook(v: Boolean) = viewModelScope.launch { settingsRepo.setWholeBook(v) }

    /**
     * Cria (ou reaproveita) o livro e deixa a configuração pronta para o CaptureService.
     * [onReady] é chamado na thread principal para então pedir o consentimento.
     */
    /** Livro criado agora para esta captura (apagado se o usuário cancelar o consentimento). */
    private var newlyCreatedBookId: Long? = null

    fun prepare(newTitle: String?, existingBookId: Long?, onReady: () -> Unit, insertAfterPosition: Int? = null) {
        viewModelScope.launch {
            val s = settings.value
            val bookId = existingBookId ?: books.createBook(newTitle, s.lastSourceApp)
            newlyCreatedBookId = if (existingBookId == null) bookId else null
            val book = books.getBook(bookId) ?: return@launch
            stateHolder.pendingConfig = CaptureConfig(
                bookId = bookId,
                bookTitle = book.title,
                sourceApp = book.sourceApp,
                mode = s.captureMode,
                autoCrop = s.autoCrop,
                skipDuplicates = s.skipDuplicates,
                quality = s.pageQuality,
                autoIntervalMs = s.autoIntervalMs,
                maxPages = s.maxPagesPerSession.takeIf { it > 0 },
                turbo = s.turbo && s.captureMode != CaptureMode.MANUAL,
                wholeBook = s.wholeBook && s.captureMode == CaptureMode.AUTO,
                insertAfterPosition = insertAfterPosition,
            )
            stateHolder.requestContinue(null)
            stateHolder.update { it.copy(warning = null, interrupted = false) }
            onReady()
        }
    }

    /** Consentimento concedido: o livro passa a ser da sessão de captura. */
    fun onConsentGranted() {
        newlyCreatedBookId = null
    }

    /**
     * O usuário cancelou o compartilhamento de tela: apaga o livro recém-criado se ainda estiver
     * vazio, para não deixar livros órfãos na biblioteca.
     */
    fun onConsentCancelled() {
        stateHolder.pendingConfig = null
        val id = newlyCreatedBookId ?: return
        newlyCreatedBookId = null
        viewModelScope.launch {
            if ((books.getBook(id)?.pageCount ?: 0) == 0) books.deleteBook(id)
        }
    }

    /** Reaproveita a última configuração (ex.: após a tela bloquear e parar a projeção). */
    fun prepareResume(onReady: () -> Unit) {
        val cfg = stateHolder.state.value.config ?: return
        stateHolder.pendingConfig = cfg
        stateHolder.update { it.copy(interrupted = false, warning = null) }
        onReady()
    }

    fun dismissResult() = stateHolder.update {
        it.copy(config = null, interrupted = false, captured = 0, duplicatesSkipped = 0, autoStopMessage = null)
    }
}

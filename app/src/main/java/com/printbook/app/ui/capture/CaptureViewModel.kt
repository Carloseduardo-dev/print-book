package com.printbook.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.printbook.app.capture.service.CaptureConfig
import com.printbook.app.capture.service.CaptureStateHolder
import com.printbook.app.capture.service.CaptureUiState
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.SourceApp
import com.printbook.app.data.prefs.CaptureMode
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
) : ViewModel() {

    val captureState: StateFlow<CaptureUiState> = stateHolder.state

    val settings: StateFlow<Settings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    /** Livros com captura em andamento, para "continuar capturando". */
    val capturingBooks: StateFlow<List<BookEntity>> =
        books.observeCapturing().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setMode(mode: CaptureMode) = viewModelScope.launch { settingsRepo.setCaptureMode(mode) }
    fun setSourceApp(app: SourceApp) = viewModelScope.launch { settingsRepo.setLastSourceApp(app) }
    fun setAutoCrop(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoCrop(v) }
    fun setSkipDuplicates(v: Boolean) = viewModelScope.launch { settingsRepo.setSkipDuplicates(v) }

    /**
     * Cria (ou reaproveita) o livro e deixa a configuração pronta para o CaptureService.
     * [onReady] é chamado na thread principal para então pedir o consentimento.
     */
    fun prepare(newTitle: String?, existingBookId: Long?, onReady: () -> Unit) {
        viewModelScope.launch {
            val s = settings.value
            val bookId = existingBookId ?: books.createBook(newTitle, s.lastSourceApp)
            val book = books.getBook(bookId) ?: return@launch
            stateHolder.pendingConfig = CaptureConfig(
                bookId = bookId,
                bookTitle = book.title,
                sourceApp = book.sourceApp,
                mode = s.captureMode,
                autoCrop = s.autoCrop,
                skipDuplicates = s.skipDuplicates,
            )
            stateHolder.update { it.copy(warning = null, interrupted = false) }
            onReady()
        }
    }

    /** Reaproveita a última configuração (ex.: após a tela bloquear e parar a projeção). */
    fun prepareResume(onReady: () -> Unit) {
        val cfg = stateHolder.state.value.config ?: return
        stateHolder.pendingConfig = cfg
        stateHolder.update { it.copy(interrupted = false, warning = null) }
        onReady()
    }

    fun dismissResult() = stateHolder.update { it.copy(config = null, interrupted = false, captured = 0, duplicatesSkipped = 0) }
}

package com.printbook.app.capture.service

import java.io.File
import com.printbook.app.data.db.entity.SourceApp
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.data.prefs.PageQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Configuração escolhida na aba Capturar antes de pedir o consentimento. */
data class CaptureConfig(
    val bookId: Long,
    val bookTitle: String,
    val sourceApp: SourceApp,
    val mode: CaptureMode,
    val autoCrop: Boolean,
    val skipDuplicates: Boolean,
    val quality: PageQuality = PageQuality.MAXIMA,
    val autoIntervalMs: Long = 2_500L,
    /** null = sem limite. */
    val maxPages: Int? = 500,
)

data class CaptureUiState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val config: CaptureConfig? = null,
    /** Modo semiautomático ligado (a bolha alterna entre ligado e pausado). */
    val autoActive: Boolean = false,
    val captured: Int = 0,
    val bookPages: Int = 0,
    val duplicatesSkipped: Int = 0,
    val warning: String? = null,
    val lastEncodeMs: Long? = null,
    val lastFileBytes: Long? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** A projeção parou sozinha (tela bloqueada, chip "parar"): oferecer "continuar". */
    val interrupted: Boolean = false,
    /** Mensagem de encerramento do modo automático (fim do livro, limite...). */
    val autoStopMessage: String? = null,
    /** Páginas do livro em captura, da mais recente para a mais antiga (preview na bolha). */
    val recentPages: List<PageThumb> = emptyList(),
)

/** Miniatura de uma página capturada, para o painel de revisão da bolha. */
data class PageThumb(val id: Long, val file: File, val position: Int, val rotation: Int)

/** Ponte entre o CaptureService, a bolha flutuante e a aba Capturar. Sem estado estático. */
@Singleton
class CaptureStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** Lida pelo CaptureService ao iniciar. */
    @Volatile var pendingConfig: CaptureConfig? = null

    fun update(transform: (CaptureUiState) -> CaptureUiState) = _state.update(transform)
}

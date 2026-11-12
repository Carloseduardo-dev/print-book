package com.printbook.app.capture.service

import java.io.File
import com.printbook.app.data.repository.InsertPoint
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
    val turbo: Boolean = false,
    /** Automático: capturar até o fim, usando o total de páginas lido do leitor. */
    val wholeBook: Boolean = false,
    /**
     * Onde entram as capturas. Durante a sessão o serviço avança a âncora para a última página
     * gravada, então "Continuar captura" (após uma interrupção) segue do ponto certo.
     */
    val insertPoint: InsertPoint = InsertPoint.End,
)

/**
 * Pedido vindo do Detalhe/Editor: abrir a aba Capturar já adicionando páginas a um print-book.
 * [where] é o texto da faixa ("No fim", "Depois da página 5").
 */
data class ContinueRequest(val bookId: Long, val title: String, val point: InsertPoint, val where: String)

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
    /** Painel de revisão aberto na bolha (o automático fica pausado enquanto isso). */
    val reviewOpen: Boolean = false,
    /** "Capturar o livro todo": páginas previstas nesta sessão (null = desconhecido). */
    val targetPages: Int? = null,
    /** Progresso curto para a bolha (ex.: "213/1018" ou "37%"). */
    val progressLabel: String? = null,
    /** Tempo restante estimado (ex.: "~14 min"). */
    val eta: String? = null,
    /** Captura concluída pela bolha: mostra o cartão "Abrir livro" por alguns segundos. */
    val finished: CaptureFinished? = null,
)

/** Resumo mostrado no cartão de conclusão da bolha. */
data class CaptureFinished(val bookId: Long, val title: String, val pages: Int)

/** Miniatura de uma página capturada, para o painel de revisão da bolha. */
data class PageThumb(val id: Long, val file: File, val position: Int, val rotation: Int)

/** Ponte entre o CaptureService, a bolha flutuante e a aba Capturar. Sem estado estático. */
@Singleton
class CaptureStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** O print-book está na tela (MainActivity em primeiro plano)? Evita cartões duplicados. */
    @Volatile var appVisible: Boolean = false

    private val _continueRequest = MutableStateFlow<ContinueRequest?>(null)
    /** Pedido pendente de "adicionar páginas" (consumido pela aba Capturar). */
    val continueRequest: StateFlow<ContinueRequest?> = _continueRequest.asStateFlow()

    fun requestContinue(request: ContinueRequest?) {
        _continueRequest.value = request
    }

    /** Lida pelo CaptureService ao iniciar. */
    @Volatile var pendingConfig: CaptureConfig? = null

    fun update(transform: (CaptureUiState) -> CaptureUiState) = _state.update(transform)
}

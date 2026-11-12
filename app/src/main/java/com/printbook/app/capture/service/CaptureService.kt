package com.printbook.app.capture.service

import android.app.Activity
import com.printbook.app.capture.analysis.RecoveryGuard
import com.printbook.app.capture.analysis.GestureRetry
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import com.printbook.app.capture.analysis.IndicatorTracker
import com.printbook.app.capture.analysis.AdaptivePace
import com.printbook.app.data.search.Plurals
import com.printbook.app.data.repository.InsertPoint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.printbook.app.MainActivity
import com.printbook.app.capture.PageTurner
import com.printbook.app.capture.analysis.AutoCaptureTrigger
import com.printbook.app.capture.analysis.AutoTurnController
import com.printbook.app.capture.analysis.FrameSignature
import com.printbook.app.capture.analysis.PaceMeter
import com.printbook.app.capture.analysis.PageIndicator
import com.printbook.app.capture.analysis.SharpnessGate
import com.printbook.app.capture.overlay.AuraWindow
import com.printbook.app.capture.overlay.BubbleContent
import com.printbook.app.capture.overlay.OverlayWindowManager
import com.printbook.app.capture.processing.FooterReader
import com.printbook.app.capture.processing.PageProcessor
import com.printbook.app.capture.processing.ProcessedFrame
import com.printbook.app.capture.profile.CaptureProfile
import com.printbook.app.capture.profile.PixelRect
import com.printbook.app.capture.projection.ProjectionSession
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.data.repository.PageOrder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.random.Random

/**
 * Foreground service do tipo mediaProjection. Mantém uma única ProjectionSession enquanto a
 * captura estiver ativa, hospeda a bolha flutuante e grava as páginas no livro escolhido.
 *
 * Cada captura tem duas etapas:
 *  1. pegar o quadro + recortar + decidir (tela preta, repetida), em linha, rápido;
 *  2. gravar (encode WebP + banco) numa corrotina gravadora única, em paralelo com a próxima
 *     virada. A fila é curta (memória limitada) e preserva a ordem das páginas.
 */
@AndroidEntryPoint
class CaptureService : LifecycleService() {

    @Inject lateinit var stateHolder: CaptureStateHolder
    @Inject lateinit var books: BookRepository
    @Inject lateinit var pageTurner: PageTurner

    private var session: ProjectionSession? = null
    private var overlay: OverlayWindowManager? = null
    private var aura: AuraWindow? = null
    private var config: CaptureConfig? = null
    private var profile: CaptureProfile? = null

    private val captureMutex = Mutex()
    private var lastSavedSignature: FrameSignature? = null
    /** Assinatura da página salva antes da última (confere a página recuperada). */
    private var prevSavedSignature: FrameSignature? = null
    /** Confere o número da página a cada captura do automático (página pulada, leitor voltou). */
    private var tracker = IndicatorTracker()
    /** Desliga a recuperação na sessão se o rodapé estiver sendo lido errado (alarmes falsos próximos). */
    private var recoveryGuard = RecoveryGuard()
    /** Ritmo do Turbo ajustado ao leitor (vale para a sessão toda). */
    private var adaptive = AdaptivePace()
    /** Páginas que faltaram e não puderam ser recuperadas (avisadas em qualquer encerramento). */
    private val missingPages = mutableListOf<Int>()
    /** Último job normal enviado à fila / gravado (âncora da página recuperada). */
    private var lastEnqueuedJob: PageJob? = null
    private var lastWrittenJob: PageJob? = null
    /** Leituras seguidas do rodapé sem nenhum "N / total" (fora do livro todo, para de ler). */
    private var footerEmptyReads = 0
    private var footerOff = false
    private var trigger = AutoCaptureTrigger()
    private var autoJob: Job? = null
    private var userStopped = false
    private var stopping = false
    private var lastSavedPageId: Long? = null
    private var previewJob: Job? = null
    /** Páginas salvas nesta sessão (só elas descontam do contador ao excluir). */
    private val sessionPageIds = mutableSetOf<Long>()
    /** Exclusões em andamento (ignora toques repetidos). */
    private val deletingPages = mutableSetOf<Long>()

    /** Onde entra a próxima captura (avança para a última página gravada ao inserir num ponto X). */
    private var insertPoint: InsertPoint = InsertPoint.End

    /** Fila de gravação (encode + banco) e a corrotina que a consome. */
    private var writeQueue: Channel<PageJob>? = null
    private var writerJob: Job? = null

    /** A bolha aparece no quadro? (tela inteira: sim; captura de um app só: não). */
    private var bubbleInFrame = true

    /** "Capturar o livro todo": leitor do rodapé, progresso e ritmo. */
    private var footerReader: FooterReader? = null
    private var pendingFooter: Bitmap? = null
    private var pace = PaceMeter()
    private var indicator: PageIndicator? = null
    private var percentStart: Pair<Int, Long>? = null
    private var progressMessage: String? = null

    /** Gravações que falharam em sequência (ex.: armazenamento cheio): o (semi)automático pausa. */
    @Volatile private var writeFailures = 0
    /** Assinatura de cada página gravada recentemente (para a exclusão saber se era a "última"). */
    private val writtenSignatures = object : LinkedHashMap<Long, FrameSignature>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FrameSignature>?) = size > 8
    }

    /** Cartão "Captura concluída" mostrado na bolha depois de ✓. */
    private var summaryJob: Job? = null

    /** Resultado de uma tentativa de captura. */
    private enum class Outcome { SAVED, DUPLICATE, BLACK, FAILED, MID_TURN }

    /** Página já recortada, aguardando o encode/gravação. */
    private class PageJob(
        val bitmap: Bitmap,
        val crop: PixelRect,
        val signature: FrameSignature,
        /** Página recuperada (o leitor tinha pulado): entra logo antes da página deste job. */
        val before: PageJob? = null,
    )

    private val turbo: Boolean get() = config?.turbo == true
    private val sampleInterval: Long get() = if (turbo) TURBO_SAMPLE_INTERVAL_MS else SAMPLE_INTERVAL_MS

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent)
            else -> {
                userStopped = true
                // "Parar" da notificação com o cartão de conclusão na tela: só fecha o cartão.
                if (session == null && stateHolder.state.value.finished != null) closeSummary(stopService = true)
                else stopCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (session != null) return
        closeSummary(stopService = false) // nova captura enquanto o cartão anterior ainda aparecia
        // Android 14+: startForeground(mediaProjection) ANTES de obter o MediaProjection.
        startForeground(
            CaptureNotification.NOTIFICATION_ID,
            CaptureNotification.build(this, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        val cfg = stateHolder.pendingConfig
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val projection = data?.let {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it)
        }
        if (projection == null || cfg == null) {
            stateHolder.update { it.copy(warning = "Permissão de captura não concedida") }
            stopCapture()
            return
        }
        config = cfg
        profile = CaptureProfile.forApp(cfg.sourceApp)
        userStopped = false
        stopping = false
        lastSavedSignature = null
        prevSavedSignature = null
        adaptive = AdaptivePace()
        recoveryGuard = RecoveryGuard()
        missingPages.clear()
        lastEnqueuedJob = null
        lastWrittenJob = null
        lastSavedPageId = null
        sessionPageIds.clear()
        bubbleInFrame = true
        indicator = null
        percentStart = null
        progressMessage = null
        writeFailures = 0
        insertPoint = cfg.insertPoint
        writtenSignatures.clear()
        pace = PaceMeter()
        trigger = AutoCaptureTrigger(stableSamples = STABLE_SAMPLES)
        // Automático: o rodapé é lido a cada página (progresso, página pulada, leitor que voltou).
        footerReader = if (cfg.mode == CaptureMode.AUTO && pageTurner.supportsAutomation) FooterReader() else null

        val newSession = ProjectionSession(this, projection, onStopped = {
            lifecycleScope.launch { stopCapture() }
        })
        newSession.start()
        session = newSession
        stateHolder.update {
            CaptureUiState(
                running = true, config = cfg, missingPages = emptyList(),
                frameWidth = newSession.width, frameHeight = newSession.height,
            )
        }
        startWriter(cfg)

        // Últimas páginas do livro (mais recentes primeiro) e total, para o preview e a revisão na bolha.
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            combine(books.observeRecentPages(cfg.bookId, PREVIEW_LIMIT), books.observePageCount(cfg.bookId)) { pages, count ->
                pages.map { PageThumb(it.id, books.pageFile(cfg.bookId, it.fileName), it.position, it.rotation) } to count
            }.collect { (thumbs, count) ->
                stateHolder.update {
                    it.copy(bookPages = count, recentPages = thumbs, reviewOpen = it.reviewOpen && thumbs.isNotEmpty())
                }
            }
        }

        // Aura de captura: janela própria, intocável, só visível durante a varredura pós-salvamento.
        aura = AuraWindow(this).also { runCatching { it.show() } }

        overlay = OverlayWindowManager(this).also { manager ->
            manager.show { onDrag ->
                BubbleContent(
                    stateHolder = stateHolder,
                    onDrag = onDrag,
                    onPrimary = ::onBubblePrimary,
                    onToggleReview = ::toggleReview,
                    onDeletePage = ::deleteCapturedPage,
                    onFinish = {
                        userStopped = true
                        stopCapture(showSummary = true)
                    },
                    onOpenBook = ::openFinishedBook,
                    onCloseSummary = { closeSummary(stopService = true) },
                )
            }
        }
        lifecycleScope.launch { probeBubble() }
    }

    // ---- Gravação em paralelo ----

    /** Avança a âncora e a guarda na configuração, para "Continuar captura" seguir dela. */
    private fun setInsertPoint(point: InsertPoint) {
        if (point == insertPoint) return
        insertPoint = point
        stateHolder.update { it.copy(config = it.config?.copy(insertPoint = point)) }
    }

    private fun startWriter(cfg: CaptureConfig) {
        val queue = Channel<PageJob>(WRITE_QUEUE)
        writeQueue = queue
        writerJob = lifecycleScope.launch {
            for (job in queue) {
                try {
                    // Inserindo num ponto X: cada captura entra logo depois da anterior. A página
                    // recuperada entra logo antes da página que o leitor mostrou depois do pulo, se
                    // ela foi gravada; se a gravação dela falhou, a recuperada é que vira a última.
                    val anchorId = lastSavedPageId.takeIf { job.before != null && job.before === lastWrittenJob }
                    val point = anchorId?.let { books.pointBefore(cfg.bookId, it) } ?: insertPoint
                    val saved = books.addPage(cfg.bookId, job.bitmap, cfg.quality, point = point)
                    if (anchorId == null) {
                        setInsertPoint(PageOrder.chain(insertPoint, saved.pageId))
                        lastSavedPageId = saved.pageId
                        lastWrittenJob = job
                    } else {
                        Log.i(TAG, "Página recuperada gravada antes da página $anchorId")
                    }
                    writeFailures = 0
                    sessionPageIds += saved.pageId
                    writtenSignatures[saved.pageId] = job.signature
                    Log.i(TAG, "Página ${saved.position + 1}: ${job.crop.width}x${job.crop.height}, ${saved.bytes / 1024} KB, encode ${saved.encodeMs} ms")
                    stateHolder.update {
                        it.copy(captured = it.captured + 1, lastEncodeMs = saved.encodeMs, lastFileBytes = saved.bytes)
                    }
                    updateNotification(stateHolder.state.value.captured)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    writeFailures++
                    Log.e(TAG, "Falha ao gravar a página", e)
                    stateHolder.update { it.copy(warning = "Falha ao gravar a página: ${e.message}") }
                } finally {
                    job.bitmap.recycle()
                }
            }
        }
    }

    // ---- Sonda: a bolha aparece no quadro? ----

    /**
     * Compara a região da bolha no quadro com a bolha visível e escondida. Na captura de um app
     * só, a bolha não entra no quadro: dá para pular o "esconder" a cada página (mais rápido e
     * sem piscar). Na dúvida, continua escondendo.
     */
    private suspend fun probeBubble() {
        delay(PROBE_DELAY_MS)
        captureMutex.withLock {
            val s = session ?: return
            val o = overlay ?: return
            val r = o.windowRect() ?: return
            val visible = s.sampleRegion(r.left, r.top, r.right, r.bottom) ?: return
            val before = s.latestFrameNs
            o.setVisible(false)
            delay(PROBE_HIDE_MS)
            val hidden = s.sampleRegion(r.left, r.top, r.right, r.bottom)
            val newFrame = s.latestFrameNs > before
            o.setVisible(true)
            val diff = hidden?.let { visible.meanAbsDiff(it) } ?: Double.MAX_VALUE
            // Só conclui "a bolha não aparece" se um quadro novo chegou com ela escondida e nada mudou.
            bubbleInFrame = !(newFrame && diff < PROBE_SAME_THRESHOLD)
            Log.i(TAG, "Sonda da bolha: diferença ${"%.2f".format(diff)}, quadro novo=$newFrame → bolha no quadro=$bubbleInFrame")
        }
    }

    // ---- Revisão e exclusão (bolha) ----

    /** Abrir o painel de revisão pausa o (semi)automático: o gesto de virar poderia acertar o painel. */
    private fun toggleReview() {
        if (stopping) return
        val opening = !stateHolder.state.value.reviewOpen
        if (opening) {
            autoJob?.let { running ->
                autoJob = null
                running.cancel()
            }
        }
        stateHolder.update {
            it.copy(reviewOpen = opening && it.recentPages.isNotEmpty(), autoActive = if (opening) false else it.autoActive)
        }
    }

    /**
     * Exclui uma página pelo painel da bolha (ex.: saiu ruim ou entrou uma notificação).
     * Só desconta do contador da sessão se a página foi capturada nesta sessão. Roda até o fim
     * mesmo que a captura seja concluída logo em seguida (NonCancellable).
     */
    private fun deleteCapturedPage(pageId: Long) {
        val cfg = config ?: return
        if (stopping) return
        if (!deletingPages.add(pageId)) return // toque repetido na mesma página
        lifecycleScope.launch {
            try {
                // A âncora da inserção é a página excluída: passa para a anterior a ela.
                if (insertPoint == InsertPoint.After(pageId)) {
                    runCatching { books.pointBefore(cfg.bookId, pageId) }.getOrNull()?.let { setInsertPoint(it) }
                }
                withContext(NonCancellable) { books.deletePages(cfg.bookId, setOf(pageId)) }
            } finally {
                deletingPages.remove(pageId)
            }
            // Se a excluída é a página que o dedupe usa como "última", esquece para permitir recapturar.
            val deletedSig = writtenSignatures.remove(pageId)
            if (deletedSig != null && deletedSig === lastSavedSignature) lastSavedSignature = null
            if (deletedSig != null && deletedSig === prevSavedSignature) prevSavedSignature = null
            if (pageId == lastSavedPageId) lastSavedPageId = null
            val fromThisSession = sessionPageIds.remove(pageId)
            stateHolder.update {
                it.copy(captured = if (fromThisSession) (it.captured - 1).coerceAtLeast(0) else it.captured, warning = null)
            }
            if (fromThisSession) updateNotification(stateHolder.state.value.captured)
        }
    }

    // ---- Botão principal e modos ----

    /** Toque no botão principal: captura (manual) ou liga/pausa o semiautomático/automático. */
    private fun onBubblePrimary() {
        val cfg = config ?: return
        if (stopping) return // concluindo: a fila está esvaziando
        // Com o painel aberto, o botão principal só fecha a revisão (não religa o automático por baixo dele).
        if (stateHolder.state.value.reviewOpen) {
            stateHolder.update { it.copy(reviewOpen = false) }
            return
        }
        if (cfg.mode == CaptureMode.MANUAL) {
            lifecycleScope.launch { captureOnce() }
            return
        }
        val running = autoJob
        if (running?.isActive == true) {
            autoJob = null
            running.cancel()
            stateHolder.update { it.copy(autoActive = false, eta = null) }
            return
        }
        stateHolder.update { it.copy(autoActive = true, autoStopMessage = null, warning = null) }
        pace = PaceMeter() // pausa longa não pode distorcer o tempo restante
        autoJob = lifecycleScope.launch {
            val stop = if (cfg.mode == CaptureMode.AUTO) autoTurnLoop(cfg) else semiAutoLoop()
            onAutoEnded(stop)
        }
    }

    /** Fim do (semi)automático por decisão própria (não por cancelamento do usuário). */
    private data class AutoEnd(val message: String, val finished: Boolean)

    private fun onAutoEnded(end: AutoEnd) {
        autoJob = null
        Log.i(TAG, "Automático encerrado: ${end.message}")
        stateHolder.update {
            if (end.finished) it.copy(autoActive = false, autoStopMessage = end.message, warning = null, eta = null)
            else it.copy(autoActive = false, warning = end.message, eta = null)
        }
        if (end.finished) {
            progressMessage = end.message
            updateNotification(stateHolder.state.value.captured)
        }
    }

    private suspend fun sampleNow(): FrameSignature? = withContext(Dispatchers.Default) { session?.sampleSignature() }

    /**
     * Arma o gatilho com a página que FOI capturada ([captured], a amostra estável de antes da
     * captura), e não com uma amostra nova: se a pessoa já virou a página nesse meio-tempo, a
     * página nova não pode virar a "referência" (seria perdida).
     */
    private suspend fun armWith(captured: FrameSignature?) {
        val sig = captured ?: sampleNow()
        if (sig != null) trigger.armWith(sig) else trigger.resetReference()
    }

    /** Espera a página nova terminar de ficar nítida (leitores desenham uma versão borrada antes). */
    private suspend fun awaitSharp() {
        val gate = SharpnessGate(minStableMs = if (turbo) adaptive.sharpStableMs else SHARP_STABLE_MS)
        withTimeoutOrNull(if (turbo) TURBO_SHARP_TIMEOUT_MS else SHARP_TIMEOUT_MS) {
            while (true) {
                val v = withContext(Dispatchers.Default) { session?.sampleSharpness() }
                if (v == null || gate.onSample(v, SystemClock.elapsedRealtime())) break
                delay(sampleInterval)
            }
        }
    }

    /** Semiautomático: captura a página atual e depois cada página nova que estabilizar. */
    private suspend fun semiAutoLoop(): AutoEnd {
        val first = sampleNow()
        if (captureOnce() == Outcome.BLACK) return AutoEnd(AutoTurnController.Reason.BLOCKED.message, false)
        armWith(first)
        while (currentCoroutineContext().isActive) {
            delay(sampleInterval)
            val signature = sampleNow() ?: continue
            if (trigger.onSample(signature)) {
                awaitSharp()
                // Referência = a página nítida que vai ser capturada (amostrada ANTES da captura,
                // para uma virada feita logo depois não virar a referência).
                val capturedPage = sampleNow()
                when (captureOnce()) {
                    Outcome.BLACK -> return AutoEnd(AutoTurnController.Reason.BLOCKED.message, false)
                    Outcome.MID_TURN -> {
                        // Virada travada: espera terminar (no Turbo, com mais amostras por um tempo).
                        if (turbo) { adaptive.onMidTurn(); trigger.stableSamples = adaptive.stableSamples }
                        trigger.rejectSettled(sampleNow())
                        continue
                    }
                    Outcome.SAVED -> if (turbo) { adaptive.onCleanPage(); trigger.stableSamples = adaptive.stableSamples }
                    else -> Unit
                }
                armWith(capturedPage)
                saveFailureEnd()?.let { return it }
            }
        }
        return AutoEnd("Pausado", false)
    }

    /** Gravações falhando em sequência (ex.: armazenamento cheio): pausa em vez de seguir perdendo páginas. */
    private fun saveFailureEnd(): AutoEnd? =
        if (writeFailures >= MAX_WRITE_FAILURES) AutoEnd(SAVE_FAILED_MESSAGE, false) else null

    /**
     * Automático (flavor pessoal): captura a página atual, vira pela Acessibilidade, espera a nova
     * página estabilizar (e ficar nítida) e captura de novo, até o fim do livro, o limite de
     * páginas ou uma pausa. No Turbo, o ritmo se ajusta ao leitor ([AdaptivePace]). O rodapé de
     * cada página é conferido ([IndicatorTracker]): página pulada é recuperada antes de seguir.
     */
    private suspend fun autoTurnLoop(cfg: CaptureConfig): AutoEnd {
        if (!pageTurner.isAvailable.value) {
            return AutoEnd("Ative \"Virar páginas automaticamente\" na aba Capturar", false)
        }
        val readerPackage = pageTurner.foregroundPackage.value
        val readerActivity = pageTurner.foregroundActivity.value
        tracker = IndicatorTracker() // as posições recomeçam a cada retomada
        footerEmptyReads = 0
        footerOff = !recoveryGuard.enabled // proteção acionada antes da pausa continua valendo
        var position = 0 // posição da página salva: +1 só quando a página é gravada

        val first = sampleNow()
        var result = captureOnce(readFooter = readFooter).toTurnResult()
        val firstText = readFooterNow()
        // Ao retomar, a página atual já está salva (sai como repetida): não conta no que falta.
        val maxPages = if (cfg.wholeBook) setupWholeBook(firstText, alreadySaved = result == AutoTurnController.Result.DUPLICATE) else cfg.maxPages
        // A primeira página só entra na conferência se ela está salva (agora, ou antes de retomar).
        val firstSaved = result == AutoTurnController.Result.SAVED || result == AutoTurnController.Result.DUPLICATE
        if (firstSaved) firstText?.let { tracker.onReading(0, IndicatorTracker.candidates(it)) }
        val controller = AutoTurnController(maxPages = maxPages)
        armWith(first)
        var lastTurnAt = 0L
        var reading: Deferred<String>? = null // rodapé da última página salva, lido em paralelo

        while (currentCoroutineContext().isActive) {
            val decision = controller.onResult(result)
            // Progresso antes de decidir parar: a última página também aparece ("42/42").
            if (result == AutoTurnController.Result.SAVED) onProgress(controller.savedPages)
            when (decision) {
                is AutoTurnController.Decision.Stop ->
                    return AutoEnd(stopMessage(decision.reason, controller.savedPages), true)
                is AutoTurnController.Decision.Pause -> return AutoEnd(decision.reason.message, false)
                AutoTurnController.Decision.Continue -> Unit
            }
            saveFailureEnd()?.let { return it }

            // Ritmo: no Turbo, ajustado ao leitor; no normal, o intervalo escolhido.
            val interval = if (cfg.turbo) adaptive.intervalMs else cfg.autoIntervalMs
            val jitter = if (cfg.turbo) TURBO_JITTER_MS else JITTER_MS
            if (cfg.turbo) trigger.stableSamples = adaptive.stableSamples
            val wait = lastTurnAt + interval + Random.nextLong(0, jitter) - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)

            val current = pageTurner.foregroundPackage.value
            if (readerPackage != null && current != null && current != readerPackage) {
                return AutoEnd(AutoTurnController.Reason.LEFT_READER.message, false)
            }

            // Confere a página antes de virar (e só com o leitor na frente): se ele pulou uma, ela
            // é recuperada agora, enquanto a página seguinte ainda está na tela. A leitura do
            // rodapé correu durante o intervalo.
            reading?.let { job ->
                reading = null
                val text = withTimeoutOrNull(READING_WAIT_MS) { job.await() }
                if (text == null) job.cancel() else checkReading(text, position, controller, readerPackage)?.let { return it }
            }

            // Sem referência armada (ex.: virada anterior não mudou a tela): espera a tela estável.
            if (trigger.state != AutoCaptureTrigger.State.ARMED) {
                trigger.resetReference()
                withTimeoutOrNull(REFERENCE_TIMEOUT_MS) {
                    while (trigger.state == AutoCaptureTrigger.State.REFERENCE) {
                        sampleNow()?.let { trigger.onSample(it) }
                        delay(sampleInterval)
                    }
                }
            }

            lastTurnAt = SystemClock.elapsedRealtime()
            if (!pageTurner.nextPage()) {
                result = AutoTurnController.Result.FAILED
                continue
            }

            val turnedAt = SystemClock.elapsedRealtime()
            var settled = waitSettle(TURN_SETTLE_TIMEOUT_MS)
            var slow = false
            // A tela mudou mas ainda não parou (página carregando no leitor): espera mais, em vez de
            // tratar como "não mudou" e virar de novo, o que pularia essa página.
            if (!settled && trigger.state == AutoCaptureTrigger.State.SETTLING) {
                slow = true
                settled = waitSettle(SLOW_PAGE_EXTRA_MS)
                Log.i(TAG, "Página lenta: estabilizou=${settled} depois de ${SystemClock.elapsedRealtime() - turnedAt} ms")
            }

            // O app saiu da tela de leitura (ex.: fim da amostra abre a loja): fim, sem capturar.
            val activityNow = pageTurner.foregroundActivity.value
            if (readerActivity != null && activityNow != null && activityNow != readerActivity) {
                Log.i(TAG, "Tela mudou de $readerActivity para $activityNow")
                return AutoEnd("Fim do livro: o app saiu da tela de leitura. ${Plurals.pagesCaptured(controller.savedPages)}", true)
            }

            result = if (settled) {
                if (cfg.turbo) adaptive.onSettled(SystemClock.elapsedRealtime() - turnedAt)
                awaitSharp()
                val capturedPage = sampleNow() // referência = a página nítida capturada
                var outcome = captureOnce(readFooter = readFooter)
                var page = capturedPage
                var retries = 0
                // A virada travou no meio (o leitor ainda carregando a página): espera a tela mudar
                // de verdade a partir do quadro travado e captura de novo.
                while (outcome == Outcome.MID_TURN && retries < MAX_MID_TURN_RETRIES) {
                    retries++
                    trigger.rejectSettled(sampleNow())
                    if (!waitSettle(SLOW_PAGE_EXTRA_MS)) break
                    awaitSharp()
                    page = sampleNow()
                    outcome = captureOnce(readFooter = readFooter)
                }
                if (retries > 0) Log.i(TAG, "Virada travada no meio: $retries nova(s) tentativa(s), resultado $outcome")
                if (cfg.turbo) {
                    when {
                        retries > 0 -> adaptive.onMidTurn()
                        slow -> adaptive.onSlowPage()
                        outcome == Outcome.SAVED -> adaptive.onCleanPage()
                    }
                    if (outcome == Outcome.SAVED && (controller.savedPages + 1) % PACE_LOG_EVERY == 0) Log.i(TAG, "Ritmo Turbo: $adaptive")
                }
                // Só a página gravada avança a posição: uma que falhou (ou ficou no meio da virada)
                // aparece como pulada na próxima leitura do rodapé e é recuperada.
                if (outcome == Outcome.SAVED) position++
                if (outcome == Outcome.SAVED) reading = startFooterRead() else discardFooter()
                outcome.toTurnResult().also { armWith(page) }
            } else {
                // A tela não estabilizou: antes da próxima virada, espera uma referência estável.
                trigger.resetReference()
                AutoTurnController.Result.NO_CHANGE
            }
        }
        return AutoEnd("Pausado", false)
    }

    /** Espera a tela mudar e parar (o gatilho já armado com a página de antes). */
    private suspend fun waitSettle(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            delay(sampleInterval)
            val sig = sampleNow() ?: continue
            if (trigger.onSample(sig)) break
        }
        true
    } ?: false

    private fun stopMessage(reason: AutoTurnController.Reason, saved: Int): String =
        if (reason == AutoTurnController.Reason.PAGE_LIMIT && config?.wholeBook == true && indicator?.pagesToCapture != null) {
            "Fim do livro: ${Plurals.pagesCaptured(saved)}"
        } else {
            "${reason.message}: ${Plurals.pagesCaptured(saved)}"
        }

    private fun Outcome.toTurnResult(): AutoTurnController.Result = when (this) {
        Outcome.SAVED -> AutoTurnController.Result.SAVED
        Outcome.DUPLICATE -> AutoTurnController.Result.DUPLICATE
        Outcome.BLACK -> AutoTurnController.Result.BLACK
        Outcome.FAILED, Outcome.MID_TURN -> AutoTurnController.Result.FAILED
    }

    /** Lê o rodapé desta captura? (Automático; fora do livro todo, para se não houver "N / total".) */
    private val readFooter: Boolean get() = footerReader != null && !footerOff

    private fun addMissing(pages: List<Int>) {
        missingPages += pages
        stateHolder.update { it.copy(missingPages = missingPages.sorted()) }
    }

    // ---- Livro todo: total de páginas, progresso e tempo restante ----

    /** Lê o rodapé da primeira página e devolve o limite da sessão (páginas que faltam, contando a atual). */
    private fun setupWholeBook(footerText: String?, alreadySaved: Boolean): Int? {
        val read = footerText?.let { PageIndicator.parse(it) }
        indicator = read
        Log.i(TAG, "Indicador do leitor: $read")
        val target = read?.pagesToCapture?.let { if (alreadySaved) it - 1 else it }?.takeIf { it > 0 }
        // Ao retomar, a primeira página "nova" é a seguinte à que está na tela.
        if (alreadySaved && read?.current != null) indicator = read.copy(current = read.current + 1)
        when {
            target != null -> stateHolder.update { it.copy(targetPages = target, progressLabel = "0/$target", eta = null) }
            read?.percent != null -> {
                percentStart = read.percent to SystemClock.elapsedRealtime()
                stateHolder.update { it.copy(progressLabel = "${read.percent}%") }
            }
            read?.total != null -> stateHolder.update { it.copy(progressLabel = "início") } // capa/páginas iniciais
            else -> stateHolder.update {
                it.copy(warning = "Não achei o total de páginas na tela. Vou capturar até o fim do livro.")
            }
        }
        return target
    }

    /** Atualiza progresso e tempo restante na bolha e na notificação (após cada página salva). */
    private fun onProgress(saved: Int) {
        if (config?.wholeBook != true) return
        pace.onPage(SystemClock.elapsedRealtime())
        val ind = indicator
        val target = ind?.pagesToCapture
        if (target != null) {
            val eta = pace.etaMs(target - saved)?.let(PaceMeter::format)
            val page = (ind.current ?: (ind.total?.minus(target - 1))) ?.let { it + saved - 1 }
            val total = ind.total
            stateHolder.update { it.copy(progressLabel = "$saved/$target", eta = eta) }
            progressMessage = buildString {
                append(if (page != null && total != null) "Página $page de $total" else "$saved de $target páginas")
                if (eta != null) append(" · $eta restantes")
            }
        } else {
            val p = ind?.percent ?: return
            val start = percentStart
            val eta = if (start != null && p > start.first) {
                val elapsed = SystemClock.elapsedRealtime() - start.second
                PaceMeter.format(elapsed * (100 - p) / (p - start.first))
            } else null
            stateHolder.update { it.copy(progressLabel = "$p%", eta = eta) }
            progressMessage = "${Plurals.pages(saved)} · $p% do livro" + (eta?.let { " · $it restantes" } ?: "")
        }
        updateNotification(stateHolder.state.value.captured)
    }

    // ---- Conferência do rodapé e recuperação da página pulada ----

    /** Rodapé da página que acabou de ser capturada, lido já (primeira página). */
    private suspend fun readFooterNow(): String? {
        val footer = pendingFooter.also { pendingFooter = null } ?: return null
        val reader = footerReader ?: run { footer.recycle(); return null }
        return reader.readText(footer)
    }

    /** Lê o rodapé da última captura em paralelo (o resultado é conferido antes da próxima virada). */
    private fun startFooterRead(): Deferred<String>? {
        val footer = pendingFooter.also { pendingFooter = null } ?: return null
        val reader = footerReader ?: run { footer.recycle(); return null }
        return lifecycleScope.async { reader.readText(footer) }
    }

    private fun discardFooter() {
        pendingFooter?.recycle()
        pendingFooter = null
    }

    /**
     * Confere o número da página lido no rodapé da página na [position]. Página pulada: volta,
     * captura e insere no lugar certo. Atualiza a porcentagem (Kindle) e ancora o progresso.
     * @return [AutoEnd] para pausar (o leitor voltou sozinho), ou null para seguir.
     */
    private suspend fun checkReading(text: String, position: Int, controller: AutoTurnController, readerPackage: String?): AutoEnd? {
        PageIndicator.parse(text)?.percent?.let { p -> indicator = indicator?.copy(percent = p) }
        val candidates = IndicatorTracker.candidates(text)
        // Leitor sem "N / total" (Kindle, Play Livros): fora do livro todo, não adianta seguir lendo.
        if (candidates.isEmpty() && !tracker.confirmed) {
            footerEmptyReads++
            if (footerEmptyReads >= FOOTER_GIVE_UP && config?.wholeBook != true && !footerOff) {
                footerOff = true
                Log.i(TAG, "Rodapé sem número de página em $footerEmptyReads leituras: conferência desligada")
            }
        } else {
            footerEmptyReads = 0
        }
        when (val e = tracker.onReading(position, candidates)) {
            IndicatorTracker.Event.None -> Unit
            is IndicatorTracker.Event.Confirmed -> {
                Log.i(TAG, "Indicador confirmado: página ${e.page} de ${e.total} (posição $position)")
                // Começou na capa/páginas iniciais (só o total era conhecido): ancora o progresso.
                val ind = indicator
                if (ind != null && ind.current == null && ind.total == e.total) {
                    indicator = ind.copy(current = e.page - (controller.savedPages - 1))
                    Log.i(TAG, "Progresso ancorado: página ${e.page} na captura ${controller.savedPages}")
                }
            }
            is IndicatorTracker.Event.Skipped -> {
                Log.w(TAG, "Página pulada pelo leitor: ${e.missing} (mostrou a ${e.page})")
                if (!recoveryGuard.enabled) return null // leitura do rodapé desligada (não deve chegar aqui)
                val recovery = if (e.missing.size == 1) recoverPrevious(readerPackage) else Recovery.NOT_TRIED
                Log.i(TAG, "Recuperação da página ${e.missing}: $recovery")
                when (recovery) {
                    Recovery.RECOVERED -> {
                        recoveryGuard.onRecovered()
                        val decision = controller.onRecovered()
                        onProgress(controller.savedPages)
                        tracker.accept(position, e.page)
                        if (decision is AutoTurnController.Decision.Stop) {
                            return AutoEnd(stopMessage(decision.reason, controller.savedPages), true)
                        }
                    }
                    // Não faltou nada: o rodapé foi lido errado. A contagem continua a de antes. Se isso
                    // se repete em páginas próximas, a leitura não é confiável: para de conferir.
                    Recovery.FALSE_ALARM -> if (recoveryGuard.onFalseAlarm(position)) {
                        footerOff = true
                        Log.w(TAG, "Alarmes falsos seguidos: rodapé lido errado, conferência de páginas desligada nesta sessão")
                    }
                    Recovery.NOT_TRIED, Recovery.FAILED -> {
                        addMissing(e.missing)
                        tracker.accept(position, e.page)
                    }
                    Recovery.LOST -> return AutoEnd(
                        "Captura pausada: depois de buscar a página ${e.missing.first()}, o leitor não voltou para a página certa. Confira a página e toque em ▶ para continuar.",
                        false,
                    )
                }
            }
            is IndicatorTracker.Event.Jumped -> {
                // O leitor saltou para frente (sincronizou com a nuvem, por exemplo): segue dali.
                Log.i(TAG, "Leitor avançou: esperado ${e.expected}, lido ${e.page}")
                tracker.accept(position, e.page)
                indicator?.let { ind -> if (ind.current != null) indicator = ind.copy(current = e.page - (controller.savedPages - 1)) }
            }
            is IndicatorTracker.Event.WentBack -> {
                Log.w(TAG, "Leitor voltou: esperado ${e.expected}, lido ${e.page}")
                return AutoEnd("O app de leitura voltou sozinho para a página ${e.page}. Captura pausada: confira a página e toque em ▶ para continuar.", false)
            }
        }
        return null
    }

    private enum class Recovery { RECOVERED, FALSE_ALARM, NOT_TRIED, FAILED, LOST }

    /**
     * Volta uma página, captura a que o leitor tinha pulado (entra antes da última salva) e avança
     * de novo. Cada passo é conferido pela tela: a página recuperada tem que ser diferente das duas
     * últimas salvas, e no fim a tela tem que ser a mesma de antes de voltar.
     *
     * Não é interrompida por uma pausa no meio (o leitor não pode ficar uma página para trás) e
     * só mexe na tela com o app de leitura na frente.
     */
    private suspend fun recoverPrevious(readerPackage: String?): Recovery = withContext(NonCancellable) {
        withTimeoutOrNull(RECOVERY_TIMEOUT_MS) { recoverPreviousSteps(readerPackage) } ?: Recovery.LOST
    }

    private suspend fun recoverPreviousSteps(readerPackage: String?): Recovery {
        val inFront = pageTurner.foregroundPackage.value
        if (readerPackage != null && inFront != null && inFront != readerPackage) return Recovery.FAILED
        // A aura de confirmação da última captura não pode entrar na comparação de antes/depois.
        val here = sampleWithoutAura() ?: return Recovery.FAILED
        when (turnWithRetry(here) { pageTurner.previousPage() }) {
            GestureRetry.Result.SETTLED -> Unit
            // Nenhuma tentativa voltou a página: continua de onde estava.
            GestureRetry.Result.IGNORED -> {
                trigger.armWith(here)
                return Recovery.FAILED
            }
            // Mudou e não parou (leitor carregando): não dá para saber onde a tela está. Pausa.
            GestureRetry.Result.UNSETTLED -> return Recovery.LOST
        }
        awaitSharp()
        val outcome = captureOnce(recovery = true)

        // Avança de volta para a página em que estava (com nova tentativa se o leitor ignorar).
        val recovered = sampleNow()
        val back = recovered != null && turnWithRetry(recovered) { pageTurner.nextPage() } == GestureRetry.Result.SETTLED
        val now = if (back) sampleWithoutAura() else null
        val sameAsBefore = now != null && now.width == here.width && now.meanAbsDiff(here) < RETURN_THRESHOLD
        if (!sameAsBefore) return Recovery.LOST
        trigger.armWith(now!!)
        return when (outcome) {
            Outcome.SAVED -> Recovery.RECOVERED
            Outcome.DUPLICATE -> Recovery.FALSE_ALARM
            else -> Recovery.FAILED
        }
    }

    /**
     * Gesto da recuperação com nova tentativa quando o leitor o ignora (ex.: caiu durante uma
     * animação). [from] é a tela antes do gesto (referência do gatilho a cada tentativa).
     */
    private suspend fun turnWithRetry(from: FrameSignature, gesture: suspend () -> Boolean): GestureRetry.Result =
        GestureRetry.turn(
            attempts = RECOVERY_GESTURE_ATTEMPTS,
            retryDelayMs = RECOVERY_GESTURE_RETRY_MS,
            beforeAttempt = { trigger.armWith(from) },
            gesture = gesture,
            awaitStart = { awaitTurnStart() },
            awaitSettle = { waitSettleOrSlow() },
            onRetry = { Log.i(TAG, "Recuperação: o leitor ignorou o gesto, tentativa $it") },
        )

    /**
     * Espera a tela começar a mudar depois do gesto (gatilho armado com a tela de antes).
     * true = já mudou e parou; false = está mudando; null = nada mudou no prazo (gesto ignorado).
     */
    private suspend fun awaitTurnStart(): Boolean? = withTimeoutOrNull(TURN_START_TIMEOUT_MS) {
        var started: Boolean? = null
        while (started == null) {
            delay(sampleInterval)
            started = sampleNow()?.let(::turnStarted)
        }
        started
    } ?: sampleNow()?.let(::turnStarted) // o prazo pode ter acabado no meio de uma amostra: olha a tela mais uma vez

    /** Resultado de uma amostra depois do gesto: true = mudou e parou; false = mudando; null = igual a antes. */
    private fun turnStarted(sig: FrameSignature): Boolean? = when {
        trigger.onSample(sig) -> true
        trigger.state != AutoCaptureTrigger.State.ARMED -> false
        else -> null
    }

    /** Espera a tela parar, com a espera extra de página lenta se ela mudou e segue carregando. */
    private suspend fun waitSettleOrSlow(): Boolean {
        if (waitSettle(TURN_SETTLE_TIMEOUT_MS)) return true
        return trigger.state == AutoCaptureTrigger.State.SETTLING && waitSettle(SLOW_PAGE_EXTRA_MS)
    }

    /** Amostra da tela com a aura de confirmação apagada (na tela inteira ela entra na amostra). */
    private suspend fun sampleWithoutAura(): FrameSignature? {
        aura?.hideNow()
        delay(HIDE_SETTLE_MS)
        return sampleNow()
    }

    // ---- Captura ----

    /** Pega o quadro atual sem a bolha (só esconde a bolha se ela aparece no quadro). */
    private suspend fun grabFrame(currentSession: ProjectionSession): Bitmap? {
        if (!bubbleInFrame) {
            // Captura de um app só: a bolha e a aura não entram no quadro; usa o mais recente.
            return currentSession.captureAfter(0, timeoutMs = 0)
        }
        // Esconde a bolha e espera o compositor tirá-la da tela; só aceita quadros que chegarem
        // depois disso. Tela estática não gera quadros novos: após o timeout usa o mais recente,
        // que já é a composição sem a bolha.
        aura?.hideNow() // nunca deixar a aura entrar na imagem
        overlay?.setVisible(false)
        try {
            delay(HIDE_SETTLE_MS)
            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                currentSession.captureAfter(System.nanoTime(), timeoutMs = 250)
            }
        } finally {
            overlay?.setVisible(true)
        }
    }

    /**
     * Captura a tela. [recovery]: página que o leitor tinha pulado. Ela não pode ser igual às duas
     * últimas salvas (senão sai como repetida e não é gravada), entra antes da última na fila e não
     * vira a referência das próximas.
     */
    private suspend fun captureOnce(readFooter: Boolean = false, recovery: Boolean = false): Outcome = captureMutex.withLock {
        val currentSession = session ?: return@withLock Outcome.FAILED
        val cfg = config ?: return@withLock Outcome.FAILED
        val prof = profile ?: return@withLock Outcome.FAILED
        val queue = writeQueue ?: return@withLock Outcome.FAILED
        stateHolder.update { it.copy(busy = true, warning = null) }
        try {
            val frame = grabFrame(currentSession)
            if (frame == null) {
                stateHolder.update { it.copy(warning = "Nenhum quadro disponível") }
                return@withLock Outcome.FAILED
            }
            if (readFooter) {
                pendingFooter?.recycle()
                pendingFooter = footerReader?.footerOf(frame)
            }

            when (val processed = withContext(Dispatchers.Default) { PageProcessor.process(frame, prof, cfg.autoCrop) }) {
                ProcessedFrame.Black -> {
                    frame.recycle()
                    // Regra do projeto: nunca contornar FLAG_SECURE. Só avisar e pausar.
                    stateHolder.update { it.copy(warning = AutoTurnController.Reason.BLOCKED.message) }
                    Outcome.BLACK
                }
                is ProcessedFrame.Page -> {
                    if (processed.bitmap !== frame) frame.recycle()
                    if (processed.midTurn) {
                        // Duas folhas lado a lado: a virada travou no meio. Não salva.
                        processed.bitmap.recycle()
                        Log.i(TAG, "Quadro no meio da virada descartado")
                        stateHolder.update { it.copy(warning = MID_TURN_WARNING) }
                        return@withLock Outcome.MID_TURN
                    }
                    fun sameAs(other: FrameSignature?) = other != null &&
                        other.width == processed.signature.width &&
                        processed.signature.meanAbsDiff(other) < DUPLICATE_THRESHOLD
                    val isDuplicate = if (recovery) {
                        sameAs(lastSavedSignature) || sameAs(prevSavedSignature)
                    } else {
                        cfg.skipDuplicates && sameAs(lastSavedSignature)
                    }
                    if (isDuplicate) {
                        processed.bitmap.recycle()
                        if (!recovery) {
                            stateHolder.update { it.copy(duplicatesSkipped = it.duplicatesSkipped + 1, warning = "Página repetida ignorada") }
                        }
                        Outcome.DUPLICATE
                    } else {
                        // Fila curta: se o encode atrasar, a captura espera (memória limitada). O envio
                        // não é cancelável: uma página já capturada sempre entra na fila. Só depois
                        // ela vira a "última salva" do dedupe.
                        try {
                            val job = PageJob(processed.bitmap, processed.crop, processed.signature, before = if (recovery) lastEnqueuedJob else null)
                            withContext(NonCancellable) { queue.send(job) }
                            if (!recovery) lastEnqueuedJob = job
                        } catch (e: Exception) {
                            processed.bitmap.recycle() // fila fechada (concluindo)
                            throw e
                        }
                        if (!recovery) {
                            prevSavedSignature = lastSavedSignature
                            lastSavedSignature = processed.signature
                        }
                        // Confirma a captura aqui, entre uma captura e outra (nunca a partir da gravação
                        // em paralelo, que poderia acender a aura no meio do próximo quadro).
                        aura?.flash()
                        if (writeFailures > 0) Outcome.FAILED else Outcome.SAVED
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Falha na captura", e)
            stateHolder.update { it.copy(warning = "Falha na captura: ${e.message}") }
            Outcome.FAILED
        } finally {
            stateHolder.update { it.copy(busy = false) }
        }
    }

    private fun updateNotification(captured: Int) {
        if (session == null) return
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(CaptureNotification.NOTIFICATION_ID, CaptureNotification.build(this, captured, progressMessage))
        }
    }

    // ---- Encerrar ----

    /**
     * Encerra a captura. Espera a fila de gravação esvaziar (nenhuma página se perde) e, se o
     * usuário concluiu pela bolha, mostra o cartão "Captura concluída" com "Abrir livro".
     */
    private fun stopCapture(showSummary: Boolean = false) {
        if (stopping) return
        stopping = true
        autoJob?.cancel()
        autoJob = null
        lifecycleScope.launch(NonCancellable) {
            captureMutex.withLock { writeQueue?.close() } // espera a captura em andamento entrar na fila
            writerJob?.join()
            writeQueue = null
            writerJob = null
            teardown(showSummary)
            stopping = false
        }
    }

    private suspend fun teardown(showSummary: Boolean) {
        autoJob?.cancel()
        autoJob = null
        previewJob?.cancel()
        previewJob = null
        aura?.dismiss()
        aura = null
        pendingFooter?.recycle()
        pendingFooter = null
        footerReader?.close()
        footerReader = null
        val hadSession = session != null
        session?.release()
        session = null
        val cfg = config
        if (cfg != null && userStopped) {
            withContext(Dispatchers.IO) { books.markReady(cfg.bookId) }
        }
        val captured = stateHolder.state.value.captured
        stateHolder.update {
            it.copy(
                running = false, busy = false, autoActive = false, reviewOpen = false, eta = null,
                interrupted = hadSession && !userStopped,
            )
        }
        // Cartão flutuante só quando o print-book não está na tela (ele já mostra o próprio resumo).
        if (showSummary && cfg != null && captured > 0 && overlay != null && !stateHolder.appVisible) {
            // Continua em primeiro plano enquanto o cartão aparece: na One UI, um app sem serviço
            // em primeiro plano tem a interface pausada e os toques no cartão não são processados.
            val done = CaptureFinished(cfg.bookId, cfg.bookTitle, captured, missingPages.sorted())
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(FINISHED_NOTIFICATION_ID, CaptureNotification.finished(this, done.bookId, done.title, done.pages))
            }
            stateHolder.update { it.copy(finished = done) }
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(CaptureNotification.NOTIFICATION_ID, CaptureNotification.build(this, captured, "Captura concluída: ${Plurals.pages(captured)}"))
            }
            summaryJob = lifecycleScope.launch {
                delay(SUMMARY_MS)
                closeSummary(stopService = true)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            overlay?.dismiss()
            overlay = null
            stopSelf()
        }
    }

    /** Fecha o cartão de conclusão (e o serviço, se não houver outra captura começando). */
    private fun closeSummary(stopService: Boolean) {
        summaryJob?.cancel()
        summaryJob = null
        if (stateHolder.state.value.finished == null && session != null) return
        stateHolder.update { it.copy(finished = null) }
        if (session == null) {
            overlay?.dismiss()
            overlay = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopService) stopSelf()
        }
    }

    /** "Abrir livro" no cartão de conclusão: traz o print-book para a frente no Detalhe do livro. */
    private fun openFinishedBook() {
        val done = stateHolder.state.value.finished ?: return
        Log.i(TAG, "Abrir livro ${done.bookId} pelo cartão de conclusão")
        runCatching { startActivity(MainActivity.openBookIntent(this, done.bookId)) }
            .onFailure { Log.w(TAG, "Não foi possível abrir o app", it) }
        NotificationManagerCompat.from(this).cancel(FINISHED_NOTIFICATION_ID)
        closeSummary(stopService = true)
    }

    override fun onDestroy() {
        autoJob?.cancel()
        summaryJob?.cancel()
        overlay?.dismiss()
        overlay = null
        aura?.dismiss()
        aura = null
        pendingFooter?.recycle()
        pendingFooter = null
        footerReader?.close()
        footerReader = null
        session?.release()
        session = null
        stateHolder.update { it.copy(running = false, busy = false, autoActive = false, finished = null) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        /** Quantas miniaturas o painel de revisão da bolha mostra. */
        private const val PREVIEW_LIMIT = 80
        /** Tempo para a remoção da bolha passar pelo compositor até o VirtualDisplay (~6 quadros). */
        private const val HIDE_SETTLE_MS = 110L
        private const val CAPTURE_TIMEOUT_MS = 2_000L
        private const val SAMPLE_INTERVAL_MS = 150L
        /** Amostras estáveis seguidas (no Turbo são mais próximas: 3 × 60 ms). */
        private const val STABLE_SAMPLES = 3
        /** Gravações seguidas com falha antes de pausar. */
        private const val MAX_WRITE_FAILURES = 3
        private const val SAVE_FAILED_MESSAGE = "Captura pausada: não foi possível gravar as páginas. Verifique o espaço livre no aparelho."
        /** Variação aleatória somada ao intervalo da virada automática. */
        private const val JITTER_MS = 700L
        private const val REFERENCE_TIMEOUT_MS = 2_000L
        /** Tempo máximo para a página nova aparecer e estabilizar depois do gesto. */
        private const val TURN_SETTLE_TIMEOUT_MS = 6_000L
        /** Espera extra quando a página mudou mas segue carregando (leitor buscando a página). */
        private const val SLOW_PAGE_EXTRA_MS = 15_000L
        /** Novas tentativas quando a captura sai no meio da virada (leitor carregando a página). */
        private const val MAX_MID_TURN_RETRIES = 4
        /** Teto da espera pela nitidez (páginas sem texto não "ficam nítidas"). */
        private const val SHARP_TIMEOUT_MS = 1_200L
        /** Diferença média (0..255) abaixo da qual duas páginas são consideradas iguais. */
        private const val DUPLICATE_THRESHOLD = 1.0
        /** Páginas recortadas aguardando gravação (cada uma ~5–10 MB em memória). */
        private const val WRITE_QUEUE = 2

        // Turbo
        private const val TURBO_JITTER_MS = 250L
        private const val TURBO_SAMPLE_INTERVAL_MS = 60L
        private const val TURBO_SHARP_TIMEOUT_MS = 700L
        /** Tempo mínimo com a nitidez no máximo antes de capturar (versão borrada parada não passa). */
        private const val SHARP_STABLE_MS = 300L

        // Sonda da bolha
        private const val PROBE_DELAY_MS = 1_200L
        private const val PROBE_HIDE_MS = 300L
        private const val PROBE_SAME_THRESHOLD = 1.5

        /** Espera máxima pela leitura do rodapé antes da próxima virada (corre durante o intervalo). */
        private const val READING_WAIT_MS = 1_500L
        /** Diferença máxima (0..255) para considerar que a tela voltou à página de antes. */
        private const val RETURN_THRESHOLD = 2.0
        /** Recuperação: tentativas do gesto quando o leitor o ignora, e a espera entre elas. */
        private const val RECOVERY_GESTURE_ATTEMPTS = 3
        private const val RECOVERY_GESTURE_RETRY_MS = 500L
        /** Prazo para a tela começar a mudar depois do gesto; sem mudança, o gesto foi ignorado. */
        private const val TURN_START_TIMEOUT_MS = 2_000L
        /** Teto da recuperação de uma página pulada (voltar, capturar, avançar). */
        private const val RECOVERY_TIMEOUT_MS = 40_000L
        /** Fora do livro todo, para de ler o rodapé depois de N leituras sem "N / total". */
        private const val FOOTER_GIVE_UP = 5
        private const val MID_TURN_WARNING = "Página capturada no meio da virada: não foi salva. Espere a página parar e capture de novo."
        /** Registra o ritmo do Turbo no log a cada N páginas (medições). */
        private const val PACE_LOG_EVERY = 25
        /** Quanto tempo o cartão "Captura concluída" fica na bolha. */
        private const val SUMMARY_MS = 8_000L
        private const val FINISHED_NOTIFICATION_ID = 1002

        private const val ACTION_START = "com.printbook.app.capture.START"
        private const val ACTION_STOP = "com.printbook.app.capture.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        fun startIntent(context: android.content.Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)

        fun stopIntent(context: android.content.Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}

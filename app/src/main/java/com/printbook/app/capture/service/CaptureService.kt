package com.printbook.app.capture.service

import android.app.Activity
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
    /** Releitura do rodapé detectou que o leitor voltou sozinho: o automático pausa com esta mensagem. */
    @Volatile private var jumpMessage: String? = null

    /** Gravações que falharam em sequência (ex.: armazenamento cheio): o (semi)automático pausa. */
    @Volatile private var writeFailures = 0
    /** Assinatura de cada página gravada recentemente (para a exclusão saber se era a "última"). */
    private val writtenSignatures = object : LinkedHashMap<Long, FrameSignature>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FrameSignature>?) = size > 8
    }
    /** Cada execução do (semi)automático tem um número: resultados assíncronos antigos são descartados. */
    private var runId = 0

    /** Cartão "Captura concluída" mostrado na bolha depois de ✓. */
    private var summaryJob: Job? = null

    /** Resultado de uma tentativa de captura. */
    private enum class Outcome { SAVED, DUPLICATE, BLACK, FAILED }

    /** Página já recortada, aguardando o encode/gravação. */
    private class PageJob(val bitmap: Bitmap, val crop: PixelRect, val signature: FrameSignature)

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
        lastSavedPageId = null
        sessionPageIds.clear()
        bubbleInFrame = true
        indicator = null
        percentStart = null
        progressMessage = null
        jumpMessage = null
        writeFailures = 0
        insertPoint = cfg.insertPoint
        writtenSignatures.clear()
        pace = PaceMeter()
        trigger = AutoCaptureTrigger(stableSamples = STABLE_SAMPLES)
        footerReader = if (cfg.wholeBook) FooterReader() else null

        val newSession = ProjectionSession(this, projection, onStopped = {
            lifecycleScope.launch { stopCapture() }
        })
        newSession.start()
        session = newSession
        stateHolder.update {
            CaptureUiState(
                running = true, config = cfg,
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
                    // Inserindo num ponto X: cada captura entra logo depois da anterior.
                    val saved = books.addPage(cfg.bookId, job.bitmap, cfg.quality, point = insertPoint)
                    setInsertPoint(PageOrder.chain(insertPoint, saved.pageId))
                    writeFailures = 0
                    lastSavedPageId = saved.pageId
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
        jumpMessage = null
        runId++
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
        val gate = SharpnessGate(minStableMs = if (turbo) TURBO_SHARP_STABLE_MS else SHARP_STABLE_MS)
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
                if (captureOnce() == Outcome.BLACK) return AutoEnd(AutoTurnController.Reason.BLOCKED.message, false)
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
     * páginas ou uma pausa.
     */
    private suspend fun autoTurnLoop(cfg: CaptureConfig): AutoEnd {
        if (!pageTurner.isAvailable.value) {
            return AutoEnd("Ative \"Virar páginas automaticamente\" na aba Capturar", false)
        }
        val readerPackage = pageTurner.foregroundPackage.value
        val readerActivity = pageTurner.foregroundActivity.value
        val interval = if (cfg.turbo) TURBO_MIN_INTERVAL_MS else cfg.autoIntervalMs
        val jitter = if (cfg.turbo) TURBO_JITTER_MS else JITTER_MS

        val first = sampleNow()
        var result = captureOnce(readFooter = cfg.wholeBook).toTurnResult()
        // Ao retomar, a página atual já está salva (sai como repetida): não conta no que falta.
        val maxPages = if (cfg.wholeBook) setupWholeBook(alreadySaved = result == AutoTurnController.Result.DUPLICATE) else cfg.maxPages
        val controller = AutoTurnController(maxPages = maxPages)
        armWith(first)
        val myRun = runId
        var lastTurnAt = 0L

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

            // Ritmo: intervalo mínimo + variação aleatória entre viradas (no Turbo, bem menores).
            val wait = lastTurnAt + interval + Random.nextLong(0, jitter) - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)

            val current = pageTurner.foregroundPackage.value
            if (readerPackage != null && current != null && current != readerPackage) {
                return AutoEnd(AutoTurnController.Reason.LEFT_READER.message, false)
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

            val settled = withTimeoutOrNull(TURN_SETTLE_TIMEOUT_MS) {
                while (true) {
                    delay(sampleInterval)
                    val sig = sampleNow() ?: continue
                    if (trigger.onSample(sig)) break
                }
                true
            } ?: false

            // O app saiu da tela de leitura (ex.: fim da amostra abre a loja): fim, sem capturar.
            val activityNow = pageTurner.foregroundActivity.value
            if (readerActivity != null && activityNow != null && activityNow != readerActivity) {
                Log.i(TAG, "Tela mudou de $readerActivity para $activityNow")
                return AutoEnd("Fim do livro: o app saiu da tela de leitura. ${controller.savedPages} páginas capturadas", true)
            }

            jumpMessage?.let { return AutoEnd(it, false) }

            result = if (settled) {
                awaitSharp()
                val capturedPage = sampleNow() // referência = a página nítida capturada
                // Livro todo: relê o rodapé de tempos em tempos (porcentagem, ou conferir a página).
                val reread = cfg.wholeBook && indicator != null && (controller.savedPages + 1) % REREAD_EVERY == 0
                captureOnce(readFooter = reread).toTurnResult().also {
                    if (reread && it == AutoTurnController.Result.SAVED) rereadIndicatorAsync(controller.savedPages + 1, myRun)
                    armWith(capturedPage)
                }
            } else {
                // A tela não estabilizou: antes da próxima virada, espera uma referência estável.
                trigger.resetReference()
                AutoTurnController.Result.NO_CHANGE
            }
        }
        return AutoEnd("Pausado", false)
    }

    private fun stopMessage(reason: AutoTurnController.Reason, saved: Int): String =
        if (reason == AutoTurnController.Reason.PAGE_LIMIT && config?.wholeBook == true && indicator?.pagesToCapture != null) {
            "Fim do livro: $saved páginas capturadas"
        } else {
            "${reason.message}: $saved páginas capturadas"
        }

    private fun Outcome.toTurnResult(): AutoTurnController.Result = when (this) {
        Outcome.SAVED -> AutoTurnController.Result.SAVED
        Outcome.DUPLICATE -> AutoTurnController.Result.DUPLICATE
        Outcome.BLACK -> AutoTurnController.Result.BLACK
        Outcome.FAILED -> AutoTurnController.Result.FAILED
    }

    // ---- Livro todo: total de páginas, progresso e tempo restante ----

    /** Lê o rodapé da primeira página e devolve o limite da sessão (páginas que faltam, contando a atual). */
    private suspend fun setupWholeBook(alreadySaved: Boolean): Int? {
        val footer = pendingFooter.also { pendingFooter = null }
        val read = footer?.let { footerReader?.read(it) }
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
            progressMessage = "$saved páginas · $p% do livro" + (eta?.let { " · $it restantes" } ?: "")
        }
        updateNotification(stateHolder.state.value.captured)
    }

    /**
     * Releitura periódica do rodapé. Atualiza a porcentagem (Kindle) e confere a página: se o app
     * de leitura voltou para trás sozinho (ex.: sincronizou a posição com a nuvem), pausa em vez
     * de capturar páginas repetidas; se pulou para frente, recalcula o progresso.
     */
    private fun rereadIndicatorAsync(savedIncludingThis: Int, run: Int) {
        val footer = pendingFooter.also { pendingFooter = null } ?: return
        val reader = footerReader ?: return footer.recycle()
        lifecycleScope.launch {
            val read = reader.read(footer) ?: return@launch
            if (run != runId) return@launch // resultado de uma execução anterior (pausou e retomou)
            val current = indicator ?: return@launch
            if (read.percent != null) indicator = current.copy(percent = read.percent)
            val start = current.current ?: return@launch
            val now = read.current ?: return@launch
            if (read.total != current.total || read.isLocation) return@launch
            val expected = start + savedIncludingThis - 1
            when {
                now < expected - JUMP_TOLERANCE -> {
                    jumpMessage = "O app de leitura voltou sozinho para a página $now. Captura pausada: confira a página e toque em ▶ para continuar."
                    Log.w(TAG, "Leitor voltou: esperado $expected, lido $now")
                }
                now > expected + JUMP_TOLERANCE -> {
                    indicator = current.copy(current = now - (savedIncludingThis - 1))
                    Log.i(TAG, "Leitor avançou: esperado $expected, lido $now (progresso recalculado)")
                }
            }
        }
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

    private suspend fun captureOnce(readFooter: Boolean = false): Outcome = captureMutex.withLock {
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
                    val previous = lastSavedSignature
                    val isDuplicate = cfg.skipDuplicates && previous != null &&
                        previous.width == processed.signature.width &&
                        processed.signature.meanAbsDiff(previous) < DUPLICATE_THRESHOLD
                    if (isDuplicate) {
                        processed.bitmap.recycle()
                        stateHolder.update { it.copy(duplicatesSkipped = it.duplicatesSkipped + 1, warning = "Página repetida ignorada") }
                        Outcome.DUPLICATE
                    } else {
                        // Fila curta: se o encode atrasar, a captura espera (memória limitada). O envio
                        // não é cancelável: uma página já capturada sempre entra na fila. Só depois
                        // ela vira a "última salva" do dedupe.
                        try {
                            withContext(NonCancellable) { queue.send(PageJob(processed.bitmap, processed.crop, processed.signature)) }
                        } catch (e: Exception) {
                            processed.bitmap.recycle() // fila fechada (concluindo)
                            throw e
                        }
                        lastSavedSignature = processed.signature
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
            val done = CaptureFinished(cfg.bookId, cfg.bookTitle, captured)
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(FINISHED_NOTIFICATION_ID, CaptureNotification.finished(this, done.bookId, done.title, done.pages))
            }
            stateHolder.update { it.copy(finished = done) }
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(CaptureNotification.NOTIFICATION_ID, CaptureNotification.build(this, captured, "Captura concluída: $captured páginas"))
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
        /** Teto da espera pela nitidez (páginas sem texto não "ficam nítidas"). */
        private const val SHARP_TIMEOUT_MS = 1_200L
        /** Diferença média (0..255) abaixo da qual duas páginas são consideradas iguais. */
        private const val DUPLICATE_THRESHOLD = 1.0
        /** Páginas recortadas aguardando gravação (cada uma ~5–10 MB em memória). */
        private const val WRITE_QUEUE = 2

        // Turbo
        private const val TURBO_MIN_INTERVAL_MS = 900L
        private const val TURBO_JITTER_MS = 250L
        private const val TURBO_SAMPLE_INTERVAL_MS = 60L
        private const val TURBO_SHARP_TIMEOUT_MS = 700L
        /** Tempo mínimo com a nitidez no máximo antes de capturar (versão borrada parada não passa). */
        private const val SHARP_STABLE_MS = 300L
        private const val TURBO_SHARP_STABLE_MS = 150L

        // Sonda da bolha
        private const val PROBE_DELAY_MS = 1_200L
        private const val PROBE_HIDE_MS = 300L
        private const val PROBE_SAME_THRESHOLD = 1.5

        /** Livro todo: relê o rodapé a cada N páginas (porcentagem e conferência da página). */
        private const val REREAD_EVERY = 10
        /** Diferença de páginas tolerada na conferência (leitura do OCR, páginas duplas). */
        private const val JUMP_TOLERANCE = 2
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

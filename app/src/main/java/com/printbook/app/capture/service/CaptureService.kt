package com.printbook.app.capture.service

import android.app.Activity
import android.content.Context
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
import com.printbook.app.capture.PageTurner
import com.printbook.app.capture.analysis.AutoCaptureTrigger
import com.printbook.app.capture.analysis.AutoTurnController
import com.printbook.app.capture.analysis.FrameSignature
import com.printbook.app.capture.overlay.BubbleContent
import com.printbook.app.capture.overlay.OverlayWindowManager
import com.printbook.app.capture.processing.PageProcessor
import com.printbook.app.capture.processing.ProcessedFrame
import com.printbook.app.capture.profile.CaptureProfile
import com.printbook.app.capture.projection.ProjectionSession
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.data.repository.BookRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
 */
@AndroidEntryPoint
class CaptureService : LifecycleService() {

    @Inject lateinit var stateHolder: CaptureStateHolder
    @Inject lateinit var books: BookRepository
    @Inject lateinit var pageTurner: PageTurner

    private var session: ProjectionSession? = null
    private var overlay: OverlayWindowManager? = null
    private var config: CaptureConfig? = null
    private var profile: CaptureProfile? = null

    private val captureMutex = Mutex()
    private var lastSavedSignature: FrameSignature? = null
    private val trigger = AutoCaptureTrigger()
    private var autoJob: Job? = null
    private var userStopped = false

    /** Resultado de uma tentativa de captura. */
    private enum class Outcome { SAVED, DUPLICATE, BLACK, FAILED }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent)
            else -> {
                userStopped = true
                stopCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (session != null) return
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
        lastSavedSignature = null

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
        lifecycleScope.launch {
            val pages = books.getBook(cfg.bookId)?.pageCount ?: 0
            stateHolder.update { it.copy(bookPages = pages) }
        }

        overlay = OverlayWindowManager(this).also { manager ->
            manager.show { onDrag ->
                BubbleContent(
                    stateHolder = stateHolder,
                    onDrag = onDrag,
                    onPrimary = ::onBubblePrimary,
                    onFinish = {
                        userStopped = true
                        stopCapture()
                    },
                )
            }
        }
    }

    /** Toque no botão principal: captura (manual) ou liga/pausa o semiautomático/automático. */
    private fun onBubblePrimary() {
        val cfg = config ?: return
        if (cfg.mode == CaptureMode.MANUAL) {
            lifecycleScope.launch { captureOnce() }
            return
        }
        val running = autoJob
        if (running?.isActive == true) {
            autoJob = null
            running.cancel()
            stateHolder.update { it.copy(autoActive = false) }
            return
        }
        stateHolder.update { it.copy(autoActive = true, autoStopMessage = null, warning = null) }
        autoJob = lifecycleScope.launch {
            try {
                val stop = if (cfg.mode == CaptureMode.AUTO) autoTurnLoop(cfg) else semiAutoLoop()
                onAutoEnded(stop)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /** Fim do (semi)automático por decisão própria (não por cancelamento do usuário). */
    private data class AutoEnd(val message: String, val finished: Boolean)

    private fun onAutoEnded(end: AutoEnd) {
        autoJob = null
        Log.i(TAG, "Automático encerrado: ${end.message}")
        stateHolder.update {
            if (end.finished) it.copy(autoActive = false, autoStopMessage = end.message, warning = null)
            else it.copy(autoActive = false, warning = end.message)
        }
        if (end.finished) {
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    CaptureNotification.NOTIFICATION_ID,
                    CaptureNotification.build(this, stateHolder.state.value.captured, end.message),
                )
            }
        }
    }

    /** Semiautomático: captura a página atual e depois cada página nova que estabilizar. */
    private suspend fun semiAutoLoop(): AutoEnd {
        if (captureOnce() == Outcome.BLACK) return AutoEnd(AutoTurnController.Reason.BLOCKED.message, false)
        trigger.resetReference()
        while (currentCoroutineContext().isActive) {
            delay(SAMPLE_INTERVAL_MS)
            val signature = withContext(Dispatchers.Default) { session?.sampleSignature() } ?: continue
            if (trigger.onSample(signature)) {
                if (captureOnce() == Outcome.BLACK) return AutoEnd(AutoTurnController.Reason.BLOCKED.message, false)
                trigger.resetReference()
            }
        }
        return AutoEnd("Pausado", false)
    }

    /**
     * Automático (flavor pessoal): captura a página atual, vira pela Acessibilidade, espera a nova
     * página estabilizar e captura de novo, até o fim do livro, o limite de páginas ou uma pausa.
     */
    private suspend fun autoTurnLoop(cfg: CaptureConfig): AutoEnd {
        if (!pageTurner.isAvailable.value) {
            return AutoEnd("Ative \"Virar páginas automaticamente\" na aba Capturar", false)
        }
        val controller = AutoTurnController(maxPages = cfg.maxPages)
        val readerPackage = pageTurner.foregroundPackage.value
        val readerActivity = pageTurner.foregroundActivity.value
        var result = captureOnce().toTurnResult()
        var lastTurnAt = 0L

        while (currentCoroutineContext().isActive) {
            when (val decision = controller.onResult(result)) {
                is AutoTurnController.Decision.Stop ->
                    return AutoEnd("${decision.reason.message}: ${controller.savedPages} páginas capturadas", true)
                is AutoTurnController.Decision.Pause -> return AutoEnd(decision.reason.message, false)
                AutoTurnController.Decision.Continue -> Unit
            }

            // Ritmo humano: intervalo base + variação aleatória entre viradas.
            val wait = lastTurnAt + cfg.autoIntervalMs + Random.nextLong(0, JITTER_MS) - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)

            val current = pageTurner.foregroundPackage.value
            if (readerPackage != null && current != null && current != readerPackage) {
                return AutoEnd(AutoTurnController.Reason.LEFT_READER.message, false)
            }

            // Assinatura de referência da página atual (tela ao vivo) antes de virar.
            trigger.resetReference()
            withTimeoutOrNull(REFERENCE_TIMEOUT_MS) {
                while (trigger.state == AutoCaptureTrigger.State.REFERENCE) {
                    withContext(Dispatchers.Default) { session?.sampleSignature() }?.let { trigger.onSample(it) }
                    delay(SAMPLE_INTERVAL_MS)
                }
            }

            lastTurnAt = SystemClock.elapsedRealtime()
            if (!pageTurner.nextPage()) {
                result = AutoTurnController.Result.FAILED
                continue
            }

            val settled = withTimeoutOrNull(TURN_SETTLE_TIMEOUT_MS) {
                while (true) {
                    delay(SAMPLE_INTERVAL_MS)
                    val sig = withContext(Dispatchers.Default) { session?.sampleSignature() } ?: continue
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

            result = if (settled) captureOnce().toTurnResult() else AutoTurnController.Result.NO_CHANGE
        }
        return AutoEnd("Pausado", false)
    }

    private fun Outcome.toTurnResult(): AutoTurnController.Result = when (this) {
        Outcome.SAVED -> AutoTurnController.Result.SAVED
        Outcome.DUPLICATE -> AutoTurnController.Result.DUPLICATE
        Outcome.BLACK -> AutoTurnController.Result.BLACK
        Outcome.FAILED -> AutoTurnController.Result.FAILED
    }

    private suspend fun captureOnce(): Outcome = captureMutex.withLock {
        val currentSession = session ?: return@withLock Outcome.FAILED
        val cfg = config ?: return@withLock Outcome.FAILED
        val prof = profile ?: return@withLock Outcome.FAILED
        stateHolder.update { it.copy(busy = true, warning = null) }
        try {
            // Esconde a bolha e espera o compositor tirá-la da tela; só aceita quadros que
            // chegarem depois disso. Tela estática não gera quadros novos: após o timeout usa o
            // mais recente, que já é a composição sem a bolha.
            overlay?.setVisible(false)
            delay(HIDE_SETTLE_MS)
            val frame = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                currentSession.captureAfter(System.nanoTime(), timeoutMs = 250)
            }
            overlay?.setVisible(true)
            if (frame == null) {
                stateHolder.update { it.copy(warning = "Nenhum quadro disponível") }
                return@withLock Outcome.FAILED
            }

            when (val processed = withContext(Dispatchers.Default) { PageProcessor.process(frame, prof, cfg.autoCrop) }) {
                ProcessedFrame.Black -> {
                    frame.recycle()
                    // Regra do projeto: nunca contornar FLAG_SECURE. Só avisar e pausar.
                    stateHolder.update { it.copy(warning = AutoTurnController.Reason.BLOCKED.message) }
                    Outcome.BLACK
                }
                is ProcessedFrame.Page -> savePage(cfg, processed, frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Falha na captura", e)
            stateHolder.update { it.copy(warning = "Falha na captura: ${e.message}") }
            Outcome.FAILED
        } finally {
            overlay?.setVisible(true)
            stateHolder.update { it.copy(busy = false) }
        }
    }

    private suspend fun savePage(cfg: CaptureConfig, page: ProcessedFrame.Page, frame: Bitmap): Outcome {
        val previous = lastSavedSignature
        val isDuplicate = cfg.skipDuplicates && previous != null &&
            previous.width == page.signature.width &&
            page.signature.meanAbsDiff(previous) < DUPLICATE_THRESHOLD
        if (isDuplicate) {
            if (page.bitmap !== frame) page.bitmap.recycle()
            frame.recycle()
            stateHolder.update { it.copy(duplicatesSkipped = it.duplicatesSkipped + 1, warning = "Página repetida ignorada") }
            return Outcome.DUPLICATE
        }
        val saved = try {
            books.addPage(cfg.bookId, page.bitmap, cfg.quality)
        } finally {
            if (page.bitmap !== frame) page.bitmap.recycle()
            frame.recycle()
        }
        lastSavedSignature = page.signature
        Log.i(TAG, "Página ${saved.position + 1}: ${page.crop.width}x${page.crop.height}, ${saved.bytes / 1024} KB, encode ${saved.encodeMs} ms")
        stateHolder.update {
            it.copy(
                captured = it.captured + 1,
                bookPages = saved.position + 1,
                lastEncodeMs = saved.encodeMs,
                lastFileBytes = saved.bytes,
            )
        }
        updateNotification(stateHolder.state.value.captured)
        return Outcome.SAVED
    }

    private fun updateNotification(captured: Int) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(CaptureNotification.NOTIFICATION_ID, CaptureNotification.build(this, captured))
        }
    }

    private fun stopCapture() {
        autoJob?.cancel()
        autoJob = null
        overlay?.dismiss()
        overlay = null
        val hadSession = session != null
        session?.release()
        session = null
        val cfg = config
        if (cfg != null && userStopped) {
            lifecycleScope.launch(Dispatchers.IO) { books.markReady(cfg.bookId) }
        }
        stateHolder.update {
            it.copy(running = false, busy = false, autoActive = false, interrupted = hadSession && !userStopped)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        autoJob?.cancel()
        overlay?.dismiss()
        session?.release()
        stateHolder.update { it.copy(running = false, busy = false, autoActive = false) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        /** Tempo para a remoção da bolha passar pelo compositor até o VirtualDisplay (~6 quadros). */
        private const val HIDE_SETTLE_MS = 110L
        private const val CAPTURE_TIMEOUT_MS = 2_000L
        private const val SAMPLE_INTERVAL_MS = 150L
        /** Variação aleatória somada ao intervalo da virada automática. */
        private const val JITTER_MS = 700L
        private const val REFERENCE_TIMEOUT_MS = 2_000L
        /** Tempo máximo para a página nova aparecer e estabilizar depois do gesto. */
        private const val TURN_SETTLE_TIMEOUT_MS = 6_000L
        /** Diferença média (0..255) abaixo da qual duas páginas são consideradas iguais. */
        private const val DUPLICATE_THRESHOLD = 1.0
        private const val ACTION_START = "com.printbook.app.capture.START"
        private const val ACTION_STOP = "com.printbook.app.capture.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}

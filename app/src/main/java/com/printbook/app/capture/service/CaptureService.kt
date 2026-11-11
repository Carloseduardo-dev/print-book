package com.printbook.app.capture.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.printbook.app.capture.analysis.AutoCaptureTrigger
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Foreground service do tipo mediaProjection. Mantém uma única ProjectionSession enquanto a
 * captura estiver ativa, hospeda a bolha flutuante e grava as páginas no livro escolhido.
 */
@AndroidEntryPoint
class CaptureService : LifecycleService() {

    @Inject lateinit var stateHolder: CaptureStateHolder
    @Inject lateinit var books: BookRepository

    private var session: ProjectionSession? = null
    private var overlay: OverlayWindowManager? = null
    private var config: CaptureConfig? = null
    private var profile: CaptureProfile? = null

    private val captureMutex = Mutex()
    private var lastSavedSignature: FrameSignature? = null
    private val trigger = AutoCaptureTrigger()
    private var autoJob: Job? = null
    private var userStopped = false

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

    /** Toque no botão principal: captura (manual) ou liga/pausa o semiautomático. */
    private fun onBubblePrimary() {
        val cfg = config ?: return
        if (cfg.mode == CaptureMode.MANUAL) {
            lifecycleScope.launch { captureOnce() }
            return
        }
        if (autoJob?.isActive == true) {
            autoJob?.cancel()
            autoJob = null
            stateHolder.update { it.copy(autoActive = false) }
        } else {
            autoJob = lifecycleScope.launch { autoLoop() }
            stateHolder.update { it.copy(autoActive = true) }
        }
    }

    /** Semiautomático: captura a página atual e depois cada página nova que estabilizar. */
    private suspend fun autoLoop() {
        captureOnce()
        trigger.resetReference()
        while (lifecycleScope.isActive) {
            delay(SAMPLE_INTERVAL_MS)
            val signature = withContext(Dispatchers.Default) { session?.sampleSignature() } ?: continue
            if (trigger.onSample(signature)) {
                captureOnce()
                trigger.resetReference()
            }
        }
    }

    private suspend fun captureOnce() = captureMutex.withLock {
        val currentSession = session ?: return@withLock
        val cfg = config ?: return@withLock
        val prof = profile ?: return@withLock
        stateHolder.update { it.copy(busy = true, warning = null) }
        try {
            // Esconde a bolha e espera alguns vsyncs; só então aceita quadros novos.
            overlay?.setVisible(false)
            delay(HIDE_SETTLE_MS)
            // Tela estática não gera quadros novos: após o timeout usa o mais recente (já sem bolha).
            val frame = currentSession.captureAfter(System.nanoTime(), timeoutMs = 250)
            overlay?.setVisible(true)
            if (frame == null) {
                stateHolder.update { it.copy(warning = "Nenhum quadro disponível") }
                return@withLock
            }

            val processed = withContext(Dispatchers.Default) { PageProcessor.process(frame, prof, cfg.autoCrop) }
            when (processed) {
                ProcessedFrame.Black -> {
                    frame.recycle()
                    // Regra do projeto: nunca contornar FLAG_SECURE. Só avisar e pausar.
                    pauseAuto()
                    stateHolder.update { it.copy(warning = "Tela preta: este app bloqueia captura") }
                }
                is ProcessedFrame.Page -> savePage(cfg, processed, frame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha na captura", e)
            stateHolder.update { it.copy(warning = "Falha na captura: ${e.message}") }
        } finally {
            overlay?.setVisible(true)
            stateHolder.update { it.copy(busy = false) }
        }
    }

    private suspend fun savePage(cfg: CaptureConfig, page: ProcessedFrame.Page, frame: android.graphics.Bitmap) {
        val previous = lastSavedSignature
        val isDuplicate = cfg.skipDuplicates && previous != null &&
            previous.width == page.signature.width &&
            page.signature.meanAbsDiff(previous) < DUPLICATE_THRESHOLD
        if (isDuplicate) {
            if (page.bitmap !== frame) page.bitmap.recycle()
            frame.recycle()
            stateHolder.update { it.copy(duplicatesSkipped = it.duplicatesSkipped + 1, warning = "Página repetida ignorada") }
            return
        }
        val saved = try {
            books.addPage(cfg.bookId, page.bitmap)
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
    }

    private fun pauseAuto() {
        autoJob?.cancel()
        autoJob = null
        stateHolder.update { it.copy(autoActive = false) }
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
        /** Margem extra após os vsyncs, para a latência do compositor até o VirtualDisplay. */
        private const val HIDE_SETTLE_MS = 32L
        private const val SAMPLE_INTERVAL_MS = 150L
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

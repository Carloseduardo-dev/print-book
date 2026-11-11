package com.printbook.app.capture.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.printbook.app.capture.SpikePageStore
import com.printbook.app.capture.analysis.BlackFrameDetector
import com.printbook.app.capture.overlay.BubbleContent
import com.printbook.app.capture.overlay.OverlayWindowManager
import com.printbook.app.capture.projection.ProjectionSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Foreground service do tipo mediaProjection. Mantém uma única ProjectionSession enquanto a
 * captura estiver ativa e hospeda a bolha flutuante.
 */
@AndroidEntryPoint
class CaptureService : LifecycleService() {

    @Inject lateinit var stateHolder: CaptureStateHolder
    @Inject lateinit var store: SpikePageStore

    private var session: ProjectionSession? = null
    private var overlay: OverlayWindowManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopCapture()
            else -> stopCapture()
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
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val projection = data?.let {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it)
        }
        if (projection == null) {
            stateHolder.update { it.copy(warning = "Permissão de captura não concedida") }
            stopCapture()
            return
        }

        val newSession = ProjectionSession(this, projection, onStopped = {
            lifecycleScope.launch { stopCapture() }
        })
        newSession.start()
        session = newSession
        stateHolder.update {
            CaptureUiState(running = true, captured = store.count(), frameWidth = newSession.width, frameHeight = newSession.height)
        }

        overlay = OverlayWindowManager(this).also { manager ->
            manager.show { onDrag ->
                BubbleContent(
                    stateHolder = stateHolder,
                    onDrag = onDrag,
                    onCapture = ::captureOnce,
                    onFinish = ::stopCapture,
                )
            }
        }
    }

    private fun captureOnce() {
        val currentSession = session ?: return
        if (stateHolder.state.value.busy) return
        stateHolder.update { it.copy(busy = true, warning = null) }
        lifecycleScope.launch {
            try {
                overlay?.setVisible(false)
                val hiddenAt = System.nanoTime()
                delay(HIDE_SETTLE_MS) // ~2+ quadros para a bolha sumir da composição
                val bitmap = currentSession.captureAfter(hiddenAt)
                overlay?.setVisible(true)

                if (bitmap == null) {
                    stateHolder.update { it.copy(warning = "Nenhum quadro disponível") }
                    return@launch
                }
                if (BlackFrameDetector.isBlack(sample(bitmap))) {
                    bitmap.recycle()
                    // Regra do projeto: nunca contornar FLAG_SECURE. Só avisar.
                    stateHolder.update { it.copy(warning = "Tela preta: este app bloqueia captura") }
                    return@launch
                }
                val saved = withContext(Dispatchers.IO) {
                    try { store.save(bitmap) } finally { bitmap.recycle() }
                }
                Log.i(TAG, "Página ${saved.file.name}: ${saved.file.length() / 1024} KB, encode ${saved.encodeMs} ms")
                val captured = store.count()
                stateHolder.update {
                    it.copy(
                        captured = captured,
                        lastEncodeMs = saved.encodeMs,
                        lastFileBytes = saved.file.length(),
                        frameWidth = currentSession.width,
                        frameHeight = currentSession.height,
                    )
                }
                updateNotification(captured)
            } catch (e: Exception) {
                Log.e(TAG, "Falha na captura", e)
                stateHolder.update { it.copy(warning = "Falha na captura: ${e.message}") }
            } finally {
                overlay?.setVisible(true)
                stateHolder.update { it.copy(busy = false) }
            }
        }
    }

    /** Amostra uma grade de ~64x64 pixels para o detector de tela preta. */
    private fun sample(bitmap: Bitmap, grid: Int = 64): IntArray {
        val out = IntArray(grid * grid)
        val stepX = (bitmap.width / grid).coerceAtLeast(1)
        val stepY = (bitmap.height / grid).coerceAtLeast(1)
        var i = 0
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val x = (gx * stepX).coerceAtMost(bitmap.width - 1)
            val y = (gy * stepY).coerceAtMost(bitmap.height - 1)
            out[i++] = bitmap.getPixel(x, y)
        }
        return out
    }

    private fun updateNotification(captured: Int) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(CaptureNotification.NOTIFICATION_ID, CaptureNotification.build(this, captured))
        }
    }

    private fun stopCapture() {
        overlay?.dismiss()
        overlay = null
        session?.release()
        session = null
        stateHolder.update { it.copy(running = false, busy = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        overlay?.dismiss()
        session?.release()
        stateHolder.update { it.copy(running = false, busy = false) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val HIDE_SETTLE_MS = 80L
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

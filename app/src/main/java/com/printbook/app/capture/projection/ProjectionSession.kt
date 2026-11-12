package com.printbook.app.capture.projection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import com.printbook.app.capture.analysis.FrameSignature
import com.printbook.app.capture.analysis.Sharpness
import kotlinx.coroutines.delay

/**
 * Uma sessão = um MediaProjection + UM VirtualDisplay + ImageReader (regra do Android 14+:
 * o token não pode ser reutilizado nem createVirtualDisplay chamado duas vezes).
 *
 * O listener consome todos os quadros (para o produtor nunca travar) e guarda só o mais recente.
 */
class ProjectionSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("printbook-projection").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null
    private var latestArrivalNs = 0L
    private var released = false

    var width = 0; private set
    var height = 0; private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection parada pelo sistema/usuário")
            release()
            onStopped()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            // Captura de app único (Android 14+): o conteúdo pode ter tamanho diferente da tela.
            handler.post { resize(width, height) }
        }
    }

    fun start() {
        projection.registerCallback(callback, handler)
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        width = bounds.width()
        height = bounds.height()
        val dpi = context.resources.displayMetrics.densityDpi
        val r = newReader(width, height)
        reader = r
        display = projection.createVirtualDisplay(
            "printbook-capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source ->
                val img = try { source.acquireLatestImage() } catch (e: IllegalStateException) { null }
                    ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    latest?.close()
                    latest = img
                    latestArrivalNs = System.nanoTime()
                }
            }, handler)
        }

    private fun resize(w: Int, h: Int) {
        if (released || w <= 0 || h <= 0 || (w == width && h == height)) return
        Log.i(TAG, "Conteúdo capturado redimensionado para ${w}x$h")
        val old = reader
        val r = newReader(w, h)
        display?.resize(w, h, context.resources.displayMetrics.densityDpi)
        display?.surface = r.surface
        reader = r
        width = w
        height = h
        synchronized(lock) { latest?.close(); latest = null }
        old?.close()
    }

    /**
     * Devolve um Bitmap de um quadro que chegou depois de [sinceNs] (ex.: depois de esconder a bolha).
     * Se nenhum quadro novo chegar até [timeoutMs] (tela estática), usa o mais recente.
     */
    suspend fun captureAfter(sinceNs: Long, timeoutMs: Long = 600): Bitmap? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            synchronized(lock) {
                val img = latest
                if (img != null && latestArrivalNs >= sinceNs) return img.toBitmap()
            }
            delay(16)
        }
        synchronized(lock) { return latest?.toBitmap() }
    }

    /**
     * Assinatura do quadro mais recente lida direto do buffer (sem criar Bitmap), barata o
     * bastante para rodar a cada ~150 ms no modo semiautomático.
     */
    fun sampleSignature(): FrameSignature? = synchronized(lock) {
        val img = latest ?: return null
        val plane = img.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        FrameSignature.sample(img.width, img.height) { x, y ->
            val i = y * rowStride + x * pixelStride
            // RGBA_8888 -> ARGB
            val r = buffer.get(i).toInt() and 0xFF
            val g = buffer.get(i + 1).toInt() and 0xFF
            val b = buffer.get(i + 2).toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Nitidez de uma faixa central do quadro mais recente, em resolução real (texto já desenhado
     * por inteiro × versão provisória borrada). Lida direto do buffer, sem Bitmap.
     */
    fun sampleSharpness(): Double? = synchronized(lock) {
        val img = latest ?: return null
        val plane = img.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = minOf(SHARP_W, img.width)
        val h = minOf(SHARP_H, img.height)
        val x0 = (img.width - w) / 2
        val y0 = (img.height - h) / 2
        Sharpness.measure(w, h) { x, y ->
            val i = (y0 + y) * rowStride + (x0 + x) * pixelStride
            val r = buffer.get(i).toInt() and 0xFF
            val g = buffer.get(i + 1).toInt() and 0xFF
            val b = buffer.get(i + 2).toInt() and 0xFF
            (r * 77 + g * 150 + b * 29) shr 8
        }
    }

    /** Assinatura só de uma região do quadro mais recente (ex.: onde a bolha está). */
    fun sampleRegion(left: Int, top: Int, right: Int, bottom: Int): FrameSignature? = synchronized(lock) {
        val img = latest ?: return null
        val l = left.coerceIn(0, img.width - 1)
        val t = top.coerceIn(0, img.height - 1)
        val r = right.coerceIn(l + 1, img.width)
        val b = bottom.coerceIn(t + 1, img.height)
        val plane = img.planes[0]
        val buffer = plane.buffer
        FrameSignature.sample(r - l, b - t, 24, 24) { x, y ->
            val i = (t + y) * plane.rowStride + (l + x) * plane.pixelStride
            (0xFF shl 24) or ((buffer.get(i).toInt() and 0xFF) shl 16) or
                ((buffer.get(i + 1).toInt() and 0xFF) shl 8) or (buffer.get(i + 2).toInt() and 0xFF)
        }
    }

    /** Momento em que chegou o quadro mais recente (System.nanoTime). */
    val latestFrameNs: Long get() = synchronized(lock) { latestArrivalNs }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer.apply { rewind() }
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (padded.width == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    fun release() {
        if (released) return
        released = true
        synchronized(lock) { latest?.close(); latest = null }
        display?.release()
        reader?.close()
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "ProjectionSession"
        /** Faixa central usada para medir nitidez (px reais). */
        const val SHARP_W = 360
        const val SHARP_H = 64
    }
}

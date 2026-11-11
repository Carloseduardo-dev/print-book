package com.printbook.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Serviço de Acessibilidade do print-book (flavor pessoal).
 *
 * Faz só duas coisas:
 *  1. desliza na tela para virar a página, e apenas quando a captura automática está rodando;
 *  2. observa qual app está em primeiro plano, para pausar se você sair do app de leitura.
 * Não lê o conteúdo das telas (sem canRetrieveWindowContent).
 */
@AndroidEntryPoint
class PageTurnerAccessibilityService : AccessibilityService() {

    @Inject lateinit var bridge: AccessibilityBridge

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return
        bridge.onForeground(pkg, event.className?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        bridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bridge.detach(this)
        super.onDestroy()
    }

    /** Deslize em coordenadas normalizadas (0..1) da tela. */
    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val path = Path().apply {
            moveTo(fromX * w, fromY * h)
            lineTo(toX * w, toY * h)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    private companion object {
        /** Janelas do sistema que não indicam troca de app (diálogos, teclado, painel). */
        val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "android",
        )
    }
}

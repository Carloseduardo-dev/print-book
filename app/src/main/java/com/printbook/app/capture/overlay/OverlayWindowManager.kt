package com.printbook.app.capture.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Janela TYPE_APPLICATION_OVERLAY arrastável que hospeda a bolha em Compose. */
class OverlayWindowManager(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner()
    private var view: ComposeView? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 700
    }

    val isShowing: Boolean get() = view != null

    fun show(content: @Composable (onDrag: (Float, Float) -> Unit) -> Unit) {
        if (view != null) return
        owner.create()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent { content(::moveBy) }
        }
        windowManager.addView(composeView, params)
        owner.resume()
        view = composeView
    }

    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        windowManager.updateViewLayout(v, params)
    }

    /**
     * Esconde/mostra sem remover a janela (usado para tirar a bolha da captura).
     * Usa alpha da janela (aplicado pelo compositor) + visibilidade da view, e deixa a janela
     * "intocável" enquanto escondida. Ao esconder, suspende até o sistema desenhar alguns
     * quadros sem a bolha.
     */
    suspend fun setVisible(visible: Boolean) {
        val v = view ?: return
        v.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        params.alpha = if (visible) 1f else 0f
        params.flags = if (visible) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.updateViewLayout(v, params)
        if (!visible) awaitFrames(HIDE_FRAMES)
    }

    private suspend fun awaitFrames(count: Int) {
        repeat(count) {
            suspendCancellableCoroutine { cont ->
                val callback = Choreographer.FrameCallback { if (cont.isActive) cont.resume(Unit) }
                Choreographer.getInstance().postFrameCallback(callback)
                cont.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
            }
        }
    }

    private companion object {
        /** Quadros de vsync aguardados para a remoção da bolha chegar ao compositor. */
        const val HIDE_FRAMES = 3
    }

    fun dismiss() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        owner.destroy()
    }
}

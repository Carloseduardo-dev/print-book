package com.printbook.app.capture.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.printbook.app.ui.components.drawAuraRing
import com.printbook.app.ui.components.rememberReducedMotion
import com.printbook.app.ui.theme.AuraDark
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Aura de captura": contorna a tela com o gradiente da marca logo DEPOIS de uma página ser salva.
 *
 * Garantias:
 *  - a janela fica GONE fora da animação e é escondida no início de cada captura ([hideNow]),
 *    então a aura nunca aparece na imagem salva;
 *  - janela intocável com opacidade 0,8 (limite do Android para toques atravessarem sobreposições
 *    de outros apps), para não bloquear o app de leitura por baixo.
 */
class AuraWindow(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner()
    private var view: ComposeView? = null
    private val flashes = MutableStateFlow(0)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        alpha = 0.8f
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun show() {
        if (view != null) return
        owner.create()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            visibility = View.GONE
            setContent { AuraContent() }
        }
        windowManager.addView(composeView, params)
        owner.resume()
        view = composeView
    }

    /** Toca a varredura uma vez (chamar depois que a página foi salva). */
    fun flash() {
        val v = view ?: return
        v.visibility = View.VISIBLE
        flashes.value += 1
    }

    /** Esconde imediatamente (chamar antes de capturar um quadro). */
    fun hideNow() {
        view?.visibility = View.GONE
    }

    fun dismiss() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        owner.destroy()
    }

    @androidx.compose.runtime.Composable
    private fun AuraContent() {
        val flashId by flashes.collectAsStateWithLifecycle()
        val reduced = rememberReducedMotion()
        val progress = remember { Animatable(1f) }
        LaunchedEffect(flashId) {
            if (flashId == 0) return@LaunchedEffect
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if (reduced) 400 else 900, easing = LinearOutSlowInEasing))
            view?.visibility = View.GONE
        }
        Canvas(Modifier.fillMaxSize()) {
            val p = progress.value
            if (p >= 1f) return@Canvas
            // acende em 12%, mantém, apaga a partir de 75%
            val alpha = when {
                p < 0.12f -> p / 0.12f
                p > 0.75f -> (1f - p) / 0.25f
                else -> 1f
            }
            val angle = if (reduced) 40f else p * 300f
            drawAuraRing(AuraDark, angle, 3.dp.toPx(), 30.dp.toPx(), alpha, glow = true)
        }
    }
}

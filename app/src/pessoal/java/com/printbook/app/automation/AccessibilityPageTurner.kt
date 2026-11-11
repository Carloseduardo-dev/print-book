package com.printbook.app.automation

import com.printbook.app.capture.PageTurner
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vira a página com um deslize da direita para a esquerda, gesto validado via ADB no Kindle,
 * no Play Livros e no Minha Biblioteca. Fica abaixo do meio da tela e termina à direita da área
 * padrão da bolha flutuante.
 */
@Singleton
class AccessibilityPageTurner @Inject constructor(
    private val bridge: AccessibilityBridge,
) : PageTurner {
    override val supportsAutomation: Boolean = true
    override val isAvailable: StateFlow<Boolean> = bridge.connected
    override val foregroundPackage: StateFlow<String?> = bridge.foregroundPackage
    override val foregroundActivity: StateFlow<String?> = bridge.foregroundActivity

    override suspend fun nextPage(): Boolean {
        val service = bridge.service.value ?: return false
        return service.swipe(fromX = 0.88f, fromY = 0.70f, toX = 0.22f, toY = 0.70f, durationMs = 180)
    }
}

package com.printbook.app.capture

/**
 * Vira a página do app de leitura em primeiro plano. Implementação real (AccessibilityService)
 * só no flavor "pessoal", na Fase 3. Até lá, ambos os flavors usam [NoOpPageTurner].
 */
interface PageTurner {
    val isAvailable: Boolean
    suspend fun nextPage(): Boolean
}

class NoOpPageTurner : PageTurner {
    override val isAvailable: Boolean = false
    override suspend fun nextPage(): Boolean = false
}

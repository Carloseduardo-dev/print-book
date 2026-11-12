package com.printbook.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Vira a página do app de leitura em primeiro plano. Implementação real (AccessibilityService)
 * só no flavor "pessoal"; o flavor "loja" usa [NoOpPageTurner].
 */
interface PageTurner {
    /** Este build tem automação (flavor pessoal). */
    val supportsAutomation: Boolean

    /** O serviço de Acessibilidade está ativo e pronto para virar páginas. */
    val isAvailable: StateFlow<Boolean>

    /** Pacote do app em primeiro plano (via eventos de janela), ou null se desconhecido. */
    val foregroundPackage: StateFlow<String?>

    /** "pacote/Activity" da tela em primeiro plano (ex.: leitor vs. loja do mesmo app), ou null. */
    val foregroundActivity: StateFlow<String?>

    /** Vira para a próxima página. @return false se o gesto não pôde ser executado. */
    suspend fun nextPage(): Boolean

    /** Volta uma página (recuperar uma página que o leitor pulou). @return false se não deu. */
    suspend fun previousPage(): Boolean
}

class NoOpPageTurner : PageTurner {
    override val supportsAutomation: Boolean = false
    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val foregroundPackage: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override val foregroundActivity: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override suspend fun nextPage(): Boolean = false
    override suspend fun previousPage(): Boolean = false
}

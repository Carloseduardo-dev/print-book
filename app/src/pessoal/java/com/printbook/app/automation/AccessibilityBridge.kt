package com.printbook.app.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liga o serviço de Acessibilidade (criado pelo sistema) ao resto do app via Hilt, sem campos
 * estáticos. O serviço se registra ao conectar e sai ao desconectar.
 */
@Singleton
class AccessibilityBridge @Inject constructor() {
    private val _service = MutableStateFlow<PageTurnerAccessibilityService?>(null)
    val service: StateFlow<PageTurnerAccessibilityService?> = _service.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    fun attach(service: PageTurnerAccessibilityService) {
        _service.value = service
        _connected.value = true
    }

    fun detach(service: PageTurnerAccessibilityService) {
        if (_service.value === service) {
            _service.value = null
            _connected.value = false
        }
    }

    fun onForeground(packageName: String) {
        _foregroundPackage.value = packageName
    }
}

package com.printbook.app.capture.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class CaptureUiState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val captured: Int = 0,
    val warning: String? = null,
    val lastEncodeMs: Long? = null,
    val lastFileBytes: Long? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
)

/** Ponte entre o CaptureService, a bolha flutuante e a aba Capturar. Sem estado estático. */
@Singleton
class CaptureStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun update(transform: (CaptureUiState) -> CaptureUiState) = _state.update(transform)
}

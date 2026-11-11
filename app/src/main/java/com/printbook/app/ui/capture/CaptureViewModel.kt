package com.printbook.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.printbook.app.capture.SpikePageStore
import com.printbook.app.capture.service.CaptureStateHolder
import com.printbook.app.capture.service.CaptureUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpikeStats(val pages: Int = 0, val totalBytes: Long = 0)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    stateHolder: CaptureStateHolder,
    private val store: SpikePageStore,
) : ViewModel() {

    val captureState: StateFlow<CaptureUiState> = stateHolder.state

    private val _stats = MutableStateFlow(SpikeStats())
    val stats: StateFlow<SpikeStats> = _stats.asStateFlow()

    fun refreshStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _stats.value = SpikeStats(store.count(), store.totalBytes())
        }
    }

    fun clearSpike() {
        viewModelScope.launch(Dispatchers.IO) {
            store.clear()
            _stats.value = SpikeStats()
        }
    }
}

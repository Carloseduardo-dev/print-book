package com.printbook.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.printbook.app.BuildConfig
import com.printbook.app.data.prefs.AUTO_INTERVALS_MS
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.data.prefs.MAX_PAGES_OPTIONS
import com.printbook.app.data.prefs.PageQuality
import com.printbook.app.data.prefs.ReaderFilter
import com.printbook.app.data.prefs.Settings
import com.printbook.app.data.prefs.SettingsRepository
import com.printbook.app.data.prefs.ThemeMode
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.ui.components.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val books: BookRepository,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    private val _storage = MutableStateFlow(0L)
    val storage = _storage.asStateFlow()

    fun refreshStorage() = viewModelScope.launch(Dispatchers.IO) { _storage.value = books.storageBytes() }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { repo.setThemeMode(v) }
    fun setCaptureMode(v: CaptureMode) = viewModelScope.launch { repo.setCaptureMode(v) }
    fun setAutoCrop(v: Boolean) = viewModelScope.launch { repo.setAutoCrop(v) }
    fun setSkipDuplicates(v: Boolean) = viewModelScope.launch { repo.setSkipDuplicates(v) }
    fun setReaderFilter(v: ReaderFilter) = viewModelScope.launch { repo.setReaderFilter(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { repo.setKeepScreenOn(v) }
    fun setVolumeKeys(v: Boolean) = viewModelScope.launch { repo.setVolumeKeysTurnPages(v) }
    fun setQuality(v: PageQuality) = viewModelScope.launch { repo.setPageQuality(v) }
    fun setInterval(v: Long) = viewModelScope.launch { repo.setAutoIntervalMs(v) }
    fun setTurbo(v: Boolean) = viewModelScope.launch { repo.setTurbo(v) }
    fun setMaxPages(v: Int) = viewModelScope.launch { repo.setMaxPagesPerSession(v) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshStorage() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)

        Section("Aparência") {
            Text("Tema", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { FilterChip(s.themeMode == it, { viewModel.setTheme(it) }, { Text(it.label) }) }
            }
        }

        Section("Leitura") {
            Text("Cores das páginas", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFilter.entries.forEach { FilterChip(s.readerFilter == it, { viewModel.setReaderFilter(it) }, { Text(it.label) }) }
            }
            SwitchRow("Manter a tela ligada ao ler", s.keepScreenOn, viewModel::setKeepScreenOn)
            SwitchRow("Teclas de volume viram a página", s.volumeKeysTurnPages, viewModel::setVolumeKeys)
        }

        Section("Captura") {
            Text("Modo padrão", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureMode.entries.forEach { FilterChip(s.captureMode == it, { viewModel.setCaptureMode(it) }, { Text(it.label) }) }
            }
            SwitchRow("Recortar barras e margens", s.autoCrop, viewModel::setAutoCrop)
            SwitchRow("Ignorar páginas repetidas", s.skipDuplicates, viewModel::setSkipDuplicates)
            SwitchRow("Turbo (Semi e Automático)", s.turbo, viewModel::setTurbo)
            Text("Qualidade das páginas", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageQuality.entries.forEach { FilterChip(s.pageQuality == it, { viewModel.setQuality(it) }, { Text(it.label) }) }
            }
            Text("Modo automático: intervalo entre páginas", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AUTO_INTERVALS_MS.forEach { ms ->
                    FilterChip(s.autoIntervalMs == ms, { viewModel.setInterval(ms) }, { Text("${ms / 1000.0}s") })
                }
            }
            Text("Modo automático: limite de páginas por sessão", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MAX_PAGES_OPTIONS.forEach { n ->
                    FilterChip(s.maxPagesPerSession == n, { viewModel.setMaxPages(n) }, { Text(if (n == 0) "Sem limite" else "$n") })
                }
            }
        }

        Section("Armazenamento") {
            Text("Print-books ocupam ${formatBytes(storage)} neste aparelho.")
            Text(
                "As páginas ficam só dentro do app: não vão para a galeria nem para o backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Sobre") {
            Text("print-book ${BuildConfig.VERSION_NAME}")
            Text(
                "Para uso pessoal. Capture apenas conteúdo ao qual você tem acesso legítimo. " +
                    "O app nunca contorna bloqueios de captura dos apps de leitura.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

package com.printbook.app.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.printbook.app.capture.service.CaptureService
import com.printbook.app.data.db.entity.SourceApp
import com.printbook.app.data.prefs.CaptureMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(
    onOpenBook: (Long) -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val capture by viewModel.captureState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val capturingBooks by viewModel.capturingBooks.collectAsStateWithLifecycle()

    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var title by rememberSaveable { mutableStateOf("") }
    var continueBookId by rememberSaveable { mutableStateOf<Long?>(null) }

    LifecycleResumeEffect(Unit) {
        notificationsGranted = hasNotificationPermission(context)
        overlayGranted = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // Iniciado enquanto a Activity está em primeiro plano (exigência do Android 15+).
            ContextCompat.startForegroundService(context, CaptureService.startIntent(context, result.resultCode, data))
        }
    }
    val requestConsent = {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Capturar", style = MaterialTheme.typography.headlineMedium)

        if (!notificationsGranted || !overlayGranted) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permissões necessárias", style = MaterialTheme.typography.titleMedium)
                    PermissionRow("Notificações", notificationsGranted) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    PermissionRow("Sobrepor a outros apps", overlayGranted) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
            }
        }

        val cfg = capture.config
        when {
            capture.running && cfg != null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Capturando: ${cfg.bookTitle}", style = MaterialTheme.typography.titleMedium)
                        Text("${cfg.sourceApp.label} · ${cfg.mode.label}")
                        Text("${capture.captured} páginas nesta sessão · ${capture.bookPages} no livro")
                        if (capture.duplicatesSkipped > 0) Text("${capture.duplicatesSkipped} repetidas ignoradas")
                        capture.lastFileBytes?.let { Text("Última página: ${it / 1024} KB") }
                        Text(
                            if (cfg.mode == CaptureMode.SEMI_AUTO) {
                                "Abra o livro, toque ▶ na bolha e vá virando as páginas."
                            } else {
                                "Abra o livro e toque no botão da câmera a cada página."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { context.startService(CaptureService.stopIntent(context)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Concluir captura") }
            }

            capture.interrupted && cfg != null -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Captura interrompida", style = MaterialTheme.typography.titleMedium)
                        Text("A tela bloqueou ou o compartilhamento foi parado. ${capture.bookPages} páginas já estão salvas em \"${cfg.bookTitle}\".")
                        Button(onClick = { viewModel.prepareResume(requestConsent) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Continuar captura")
                        }
                        TextButton(onClick = { onOpenBook(cfg.bookId); viewModel.dismissResult() }) { Text("Ver livro") }
                    }
                }
            }

            cfg != null && capture.captured > 0 -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Captura concluída", style = MaterialTheme.typography.titleMedium)
                        Text("${capture.captured} páginas salvas em \"${cfg.bookTitle}\".")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenBook(cfg.bookId); viewModel.dismissResult() }) { Text("Abrir livro") }
                            TextButton(onClick = viewModel::dismissResult) { Text("Nova captura") }
                        }
                    }
                }
            }

            else -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Livro", style = MaterialTheme.typography.titleMedium)
                        if (capturingBooks.isNotEmpty()) {
                            Text("Continuar um livro em captura:", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                capturingBooks.forEach { book ->
                                    FilterChip(
                                        selected = continueBookId == book.id,
                                        onClick = { continueBookId = if (continueBookId == book.id) null else book.id },
                                        label = { Text("${book.title} (${book.pageCount})") },
                                    )
                                }
                            }
                        }
                        if (continueBookId == null) {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Nome do novo print-book") },
                                placeholder = { Text("Ex.: Cálculo vol. 2") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("App de leitura", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SourceApp.entries.forEach { app ->
                                    FilterChip(
                                        selected = settings.lastSourceApp == app,
                                        onClick = { viewModel.setSourceApp(app) },
                                        label = { Text(app.label) },
                                    )
                                }
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Modo", style = MaterialTheme.typography.titleMedium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            CaptureMode.entries.forEachIndexed { i, mode ->
                                SegmentedButton(
                                    selected = settings.captureMode == mode,
                                    onClick = { viewModel.setMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(i, CaptureMode.entries.size),
                                ) { Text(mode.label) }
                            }
                        }
                        Text(settings.captureMode.description, style = MaterialTheme.typography.bodySmall)
                        SwitchRow("Recortar barras e margens", settings.autoCrop, viewModel::setAutoCrop)
                        SwitchRow("Ignorar páginas repetidas", settings.skipDuplicates, viewModel::setSkipDuplicates)
                    }
                }

                Button(
                    onClick = { viewModel.prepare(title, continueBookId, requestConsent) },
                    enabled = notificationsGranted && overlayGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Iniciar captura") }
                Text(
                    "Na próxima tela, escolha \"Tela inteira\" ou o app de leitura.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        capture.warning?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Text(label, modifier = Modifier.weight(1f))
        if (!granted) TextButton(onClick = onRequest) { Text("Permitir") }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

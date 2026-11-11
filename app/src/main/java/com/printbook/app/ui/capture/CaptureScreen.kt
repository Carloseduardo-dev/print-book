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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun CaptureScreen(viewModel: CaptureViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val capture by viewModel.captureState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LifecycleResumeEffect(Unit) {
        notificationsGranted = hasNotificationPermission(context)
        overlayGranted = Settings.canDrawOverlays(context)
        viewModel.refreshStats()
        onPauseOrDispose { }
    }
    LaunchedEffect(capture.captured) { viewModel.refreshStats() }

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

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Capturar", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Abra o livro no seu app de leitura e use o botão flutuante para capturar cada página.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permissões", style = MaterialTheme.typography.titleMedium)
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

        if (capture.running) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Captura ativa", style = MaterialTheme.typography.titleMedium)
                    Text("${capture.captured} páginas nesta sessão")
                    Text("Quadro: ${capture.frameWidth}×${capture.frameHeight}")
                    capture.lastEncodeMs?.let { Text("Último encode: $it ms") }
                    capture.lastFileBytes?.let { Text("Último arquivo: ${it / 1024} KB") }
                }
            }
            OutlinedButton(
                onClick = { context.startService(CaptureService.stopIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Parar captura") }
        } else {
            Button(
                onClick = {
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                },
                enabled = notificationsGranted && overlayGranted,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Iniciar captura") }
        }

        capture.warning?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Teste de captura (Fase 1)", style = MaterialTheme.typography.titleMedium)
                Text("${stats.pages} páginas salvas · ${"%.1f".format(stats.totalBytes / 1_048_576.0)} MB")
                if (stats.pages > 0) {
                    Text("Média: ${stats.totalBytes / stats.pages / 1024} KB por página")
                    TextButton(onClick = viewModel::clearSpike, enabled = !capture.running) { Text("Apagar capturas de teste") }
                }
            }
        }
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

package com.printbook.app.ui.capture

import androidx.compose.foundation.layout.imePadding
import com.printbook.app.data.search.Plurals
import com.printbook.app.data.repository.InsertPoint
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.printbook.app.ui.components.AuraButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.printbook.app.ui.components.auraOnFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.printbook.app.ui.theme.MonoMeta
import com.printbook.app.ui.theme.PrintBook
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import com.printbook.app.capture.service.CaptureConfig
import com.printbook.app.capture.service.CaptureService
import com.printbook.app.capture.service.CaptureUiState
import com.printbook.app.data.db.entity.SourceApp
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.data.prefs.PageQuality

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
    val continueRequest by viewModel.continueRequest.collectAsStateWithLifecycle()
    val automationAvailable by viewModel.automationAvailable.collectAsStateWithLifecycle()

    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var title by rememberSaveable { mutableStateOf("") }
    var continueBookIdRaw by rememberSaveable { mutableStateOf<Long?>(null) }
    // Ignora a seleção se o livro saiu da lista "em captura" (ex.: captura concluída).
    // Um pedido do Detalhe/Editor ("Adicionar páginas") tem prioridade e vale para qualquer print-book.
    val continueBookId = continueRequest?.bookId ?: continueBookIdRaw?.takeIf { id -> capturingBooks.any { it.id == id } }
    var showA11yDisclosure by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

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
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            viewModel.onConsentCancelled()
        } else {
            viewModel.onConsentGranted()
            // Iniciado enquanto a Activity está em primeiro plano (exigência do Android 15+).
            ContextCompat.startForegroundService(context, CaptureService.startIntent(context, result.resultCode, data))
        }
    }
    val requestConsent = {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    val cfg = capture.config
    val showSetup = !(capture.running && cfg != null) &&
        !(capture.interrupted && cfg != null) &&
        !(cfg != null && capture.captured > 0)
    val autoNeedsService = settings.captureMode == CaptureMode.AUTO && !automationAvailable
    val modes = CaptureMode.entries.filter { it != CaptureMode.AUTO || viewModel.supportsAutomation }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
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

            when {
                capture.running && cfg != null -> RunningCard(capture, cfg) {
                    context.startService(CaptureService.stopIntent(context))
                }

                capture.interrupted && cfg != null -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Captura interrompida", style = MaterialTheme.typography.titleMedium)
                            Text("A tela bloqueou ou o compartilhamento foi parado. ${if (capture.bookPages == 1) "1 página já está salva" else "${capture.bookPages} páginas já estão salvas"} em \"${cfg.bookTitle}\".")
                            Button(onClick = { viewModel.prepareResume(requestConsent) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Continuar captura")
                            }
                            TextButton(onClick = { onOpenBook(cfg.bookId); viewModel.dismissResult() }) { Text("Abrir print-book") }
                        }
                    }
                }

                cfg != null && capture.captured > 0 -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Captura concluída", style = MaterialTheme.typography.titleMedium)
                            Text(capture.autoStopMessage ?: "${Plurals.pagesSaved(capture.captured)} em \"${cfg.bookTitle}\".")
                            if (capture.missingPages.isNotEmpty()) {
                                Text("${Plurals.missingPages(capture.missingPages)}: o leitor pulou e não deu para recuperar.", color = MaterialTheme.colorScheme.error)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onOpenBook(cfg.bookId); viewModel.dismissResult() }) { Text("Abrir print-book") }
                                TextButton(onClick = viewModel::dismissResult) { Text("Nova captura") }
                            }
                        }
                    }
                }

                else -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("print-book", style = MaterialTheme.typography.titleMedium)
                            continueRequest?.let { req ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Adicionando páginas a", style = MaterialTheme.typography.labelMedium)
                                        Text(req.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                        Text(
                                            req.where,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    IconButton(onClick = viewModel::cancelContinue) { Icon(Icons.Filled.Close, "Cancelar") }
                                }
                            }
                            if (continueRequest == null && capturingBooks.isNotEmpty()) {
                                Text("Continuar um print-book em captura:", style = MaterialTheme.typography.bodyMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    capturingBooks.forEach { book ->
                                        FilterChip(
                                            selected = continueBookId == book.id,
                                            onClick = { continueBookIdRaw = if (continueBookId == book.id) null else book.id },
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
                                    placeholder = { Text("Ex.: Receitas da família, Cálculo vol. 2") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focusManager.clearFocus() }),
                                    trailingIcon = {
                                        if (title.isNotBlank()) {
                                            TextButton(onClick = { keyboard?.hide(); focusManager.clearFocus() }) { Text("OK") }
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent),
                                    modifier = Modifier.fillMaxWidth().auraOnFocus(cornerRadius = 14.dp),
                                )
                                Text("De onde vem o conteúdo", style = MaterialTheme.typography.bodyMedium)
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
                                modes.forEachIndexed { i, mode ->
                                    SegmentedButton(
                                        selected = settings.captureMode == mode,
                                        onClick = { viewModel.setMode(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                    ) {
                                        Text(
                                            when (mode) {
                                                CaptureMode.MANUAL -> "Manual"
                                                CaptureMode.SEMI_AUTO -> "Semi"
                                                CaptureMode.AUTO -> "Auto"
                                            },
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            Text(settings.captureMode.description, style = MaterialTheme.typography.bodySmall)
                            SummaryList(
                                buildList {
                                    add(
                                        when (settings.captureMode) {
                                            CaptureMode.AUTO -> (if (settings.turbo) "Vira as páginas sozinho em cerca de 1 s (Turbo)"
                                                else "Vira as páginas sozinho a cada ${"%.1f".format(settings.autoIntervalMs / 1000.0)} s") +
                                                when {
                                                    settings.wholeBook && viewModel.supportsAutomation -> ", até o fim do livro"
                                                    settings.maxPagesPerSession > 0 -> ", até ${settings.maxPagesPerSession} páginas"
                                                    else -> ""
                                                }
                                            CaptureMode.SEMI_AUTO -> if (settings.turbo) "Captura logo que você vira a página (Turbo)"
                                                else "Captura quando você vira a página"
                                            CaptureMode.MANUAL -> "Captura a cada toque na bolha"
                                        } to true,
                                    )
                                    add("Recorta barras e margens" to settings.autoCrop)
                                    add("Ignora páginas repetidas" to settings.skipDuplicates)
                                    add("Qualidade ${settings.pageQuality.label.lowercase()}" to true)
                                },
                            )
                            HorizontalDivider()
                            SwitchRow("Recortar barras e margens", settings.autoCrop, viewModel::setAutoCrop)
                            SwitchRow("Ignorar páginas repetidas", settings.skipDuplicates, viewModel::setSkipDuplicates)
                            if (settings.captureMode != CaptureMode.MANUAL) {
                                SwitchRow("Turbo", settings.turbo, viewModel::setTurbo)
                                if (settings.turbo) {
                                    Text(
                                        "Vira e captura em cerca de 1 s por página. Viradas muito rápidas podem chamar a atenção do app de origem.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (settings.captureMode == CaptureMode.AUTO && viewModel.supportsAutomation) {
                                SwitchRow("Capturar o livro todo", settings.wholeBook, viewModel::setWholeBook)
                                if (settings.wholeBook) {
                                    Text(
                                        "Lê o total de páginas no app de leitura e mostra o progresso e o tempo restante. Comece na página onde quer iniciar.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text("Qualidade das páginas", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PageQuality.entries.forEach { q ->
                                    FilterChip(settings.pageQuality == q, { viewModel.setQuality(q) }, { Text(q.label) })
                                }
                            }
                            Text(settings.pageQuality.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (viewModel.supportsAutomation) {
                        AutomationCard(available = automationAvailable, onEnable = { showA11yDisclosure = true })
                    }
                }
            }

            capture.warning?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Botão fixo no rodapé: sempre visível, inclusive acima do teclado (imePadding).
        if (showSetup) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    AuraButton(
                        onClick = {
                            keyboard?.hide()
                            focusManager.clearFocus()
                            viewModel.prepare(title, continueBookId, requestConsent, continueRequest?.point ?: InsertPoint.End)
                            title = ""
                            continueBookIdRaw = null
                        },
                        enabled = notificationsGranted && overlayGranted && !autoNeedsService,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Iniciar captura") }
                    Text(
                        if (autoNeedsService) "Ative a virada automática acima para usar o modo Automático."
                        else "Na próxima tela, escolha \"Tela inteira\" ou o app com o conteúdo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    if (showA11yDisclosure) {
        AlertDialog(
            onDismissRequest = { showA11yDisclosure = false },
            title = { Text("Virar páginas automaticamente") },
            text = {
                Text(
                    "O print-book usa um serviço de Acessibilidade para:\n\n" +
                        "• deslizar a tela e virar a página do seu app de leitura, apenas quando você inicia a captura automática;\n" +
                        "• pausar se você sair do app de leitura.\n\n" +
                        "Ele NÃO lê o conteúdo das telas, senhas ou mensagens. Você pode desativar quando quiser nas configurações de Acessibilidade.\n\n" +
                        "Na próxima tela, abra \"Apps instalados\" > \"print-book: virar páginas\" e ative. " +
                        "Se a opção estiver bloqueada, vá em Configurações > Apps > print-book > ⋮ > \"Permitir configurações restritas\".",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showA11yDisclosure = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Abrir configurações") }
            },
            dismissButton = { TextButton(onClick = { showA11yDisclosure = false }) { Text("Agora não") } },
        )
    }
}

@Composable
private fun RunningCard(capture: CaptureUiState, cfg: CaptureConfig, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Capturando: ${cfg.bookTitle}", style = MaterialTheme.typography.titleMedium)
            Text("${cfg.sourceApp.label} · ${cfg.mode.label} · ${cfg.quality.label}")
            Text("${Plurals.pages(capture.captured)} nesta sessão · ${capture.bookPages} no print-book")
            if (capture.duplicatesSkipped > 0) Text(Plurals.count(capture.duplicatesSkipped, "repetida ignorada", "repetidas ignoradas"))
            capture.lastFileBytes?.let { Text("Última página: ${it / 1024} KB") }
            capture.autoStopMessage?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            if (capture.missingPages.isNotEmpty()) Text(Plurals.missingPages(capture.missingPages), color = MaterialTheme.colorScheme.error)
            Text(
                when (cfg.mode) {
                    CaptureMode.AUTO -> "Abra o conteúdo na primeira página desejada e toque ▶ na bolha. O app vira as páginas sozinho."
                    CaptureMode.SEMI_AUTO -> "Abra o conteúdo, toque ▶ na bolha e vá virando as páginas."
                    CaptureMode.MANUAL -> "Abra o conteúdo e toque no botão da câmera a cada página."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Concluir captura") }
}

@Composable
private fun AutomationCard(available: Boolean, onEnable: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (available) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Text("Virar páginas automaticamente", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(
                if (available) "Ativo. O modo Automático vira as páginas por você e para no fim do livro."
                else "Necessário para o modo Automático. Usa um serviço de Acessibilidade só para deslizar a página.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!available) TextButton(onClick = onEnable) { Text("Ativar") }
        }
    }
}

/** Resumo da configuração em linguagem simples: ✓ ligado, — desligado. */
@Composable
private fun SummaryList(items: List<Pair<String, Boolean>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (text, on) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (on) "✓" else "—",
                    color = if (on) PrintBook.tokens.success else PrintBook.tokens.subtle,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else PrintBook.tokens.subtle,
                )
            }
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

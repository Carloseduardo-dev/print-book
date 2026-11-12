package com.printbook.app.capture.overlay

import androidx.compose.foundation.background
import com.printbook.app.data.search.Plurals
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.printbook.app.R
import com.printbook.app.capture.service.CaptureStateHolder
import com.printbook.app.capture.service.PageThumb
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.ui.components.AuraSpinner
import com.printbook.app.ui.components.auraBorder
import com.printbook.app.ui.components.rememberPageRequest
import com.printbook.app.ui.components.rememberSmoothPageRequest
import com.printbook.app.ui.theme.AuraDark
import com.printbook.app.ui.theme.CopperDark
import com.printbook.app.ui.theme.Geist
import com.printbook.app.ui.theme.GeistMono
import com.printbook.app.ui.theme.Ink
import com.printbook.app.ui.theme.LineDark
import com.printbook.app.ui.theme.PaperText
import com.printbook.app.ui.theme.PaperText2
import com.printbook.app.ui.theme.WarnDark

private val Shape = RoundedCornerShape(22.dp)
private val Surface = Ink.copy(alpha = 0.95f)

/**
 * Bolha flutuante (design 1B). Mostra a miniatura da última página capturada; tocar nela abre o
 * painel de revisão (preview grande, faixa de miniaturas e excluir). Toda a janela da bolha é
 * escondida antes de cada captura, então o painel nunca entra no print.
 */
@Composable
fun BubbleContent(
    stateHolder: CaptureStateHolder,
    onDrag: (Float, Float) -> Unit,
    onPrimary: () -> Unit,
    onToggleReview: () -> Unit,
    onDeletePage: (Long) -> Unit,
    onFinish: () -> Unit,
    onOpenBook: () -> Unit,
    onCloseSummary: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    state.finished?.let { done ->
        FinishedCard(done.title, done.pages, done.missing, onDrag, onOpenBook, onCloseSummary)
        return
    }
    val semiAuto = state.config?.mode != null && state.config?.mode != CaptureMode.MANUAL
    val warning = state.warning != null && !state.autoActive
    val reviewOpen = state.reviewOpen
    val latest = state.recentPages.firstOrNull()

    Row(
        // Margem maior que a sombra: senão a sombra é cortada na borda da janela e vira um retângulo.
        modifier = Modifier.padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .shadow(7.dp, Shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(Shape)
                .background(Surface)
                .then(
                    when {
                        state.autoActive -> Modifier.auraBorder(AuraDark, width = 2.dp, cornerRadius = 22.dp, rotationMillis = 2_600, glow = false)
                        warning -> Modifier.border(1.5.dp, WarnDark, Shape)
                        else -> Modifier.border(1.dp, LineDark, Shape)
                    },
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    }
                }
                .padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val description = when {
                reviewOpen -> "Fechar revisão"
                !semiAuto -> "Capturar página"
                state.autoActive -> "Pausar captura automática"
                else -> "Iniciar captura automática"
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CopperDark)
                    .clickable(enabled = semiAuto || !state.busy, onClick = onPrimary)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.busy && !semiAuto -> AuraSpinner(size = 24.dp, stroke = 2.5.dp, colors = listOf(Ink, Ink.copy(alpha = 0.25f)))
                    !semiAuto -> Icon(painterResource(R.drawable.ic_pb_capture), null, tint = Ink, modifier = Modifier.size(24.dp))
                    state.autoActive -> Icon(Icons.Filled.Pause, null, tint = Ink)
                    else -> Icon(Icons.Filled.PlayArrow, null, tint = Ink)
                }
            }
            Text("${state.bookPages}", style = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = PaperText))
            // "Capturar o livro todo": progresso e tempo restante.
            state.progressLabel?.let {
                Text(it, style = TextStyle(fontFamily = GeistMono, fontSize = 9.sp, color = PaperText2))
            }
            state.eta?.let {
                Text(it, style = TextStyle(fontFamily = GeistMono, fontSize = 9.sp, color = CopperDark))
            }
            if (state.autoStopMessage != null) {
                Text("fim", style = TextStyle(fontFamily = GeistMono, fontSize = 10.sp, color = CopperDark))
            }
            // Miniatura da última captura: abre o painel de revisão.
            if (latest != null) {
                // No Turbo a miniatura troca a cada ~1 s: mostra a anterior até a nova carregar.
                val smooth = rememberSmoothPageRequest(latest.file, latest.rotation, 160, "bubble")
                AsyncImage(
                    model = smooth.request,
                    onSuccess = { smooth.onLoaded() },
                    contentDescription = "Revisar capturas",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(40.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            width = if (reviewOpen) 2.dp else 1.dp,
                            color = if (reviewOpen) CopperDark else Color(0xFF4A443A),
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable(onClick = onToggleReview),
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, Color(0xFF4A443A), RoundedCornerShape(11.dp))
                    .clickable(onClick = onFinish)
                    .semantics { contentDescription = "Concluir captura" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = PaperText, modifier = Modifier.size(18.dp))
            }
        }

        if (reviewOpen && state.recentPages.isNotEmpty()) {
            ReviewPanel(
                pages = state.recentPages,
                onDelete = onDeletePage,
                onClose = onToggleReview,
            )
        }
    }
}

/** Painel de revisão: preview grande, faixa rolável de miniaturas e excluir com confirmação. */
@Composable
private fun ReviewPanel(pages: List<PageThumb>, onDelete: (Long) -> Unit, onClose: () -> Unit) {
    var selectedId by remember { mutableStateOf(pages.first().id) }
    var confirming by remember { mutableStateOf(false) }
    // Se a selecionada foi excluída (ou chegou uma nova), volta para a mais recente.
    LaunchedEffect(pages) {
        if (pages.none { it.id == selectedId }) selectedId = pages.first().id
        confirming = false
    }
    val selected = pages.firstOrNull { it.id == selectedId } ?: pages.first()
    val label = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = PaperText)

    Column(
        modifier = Modifier
            .width(224.dp)
            .shadow(7.dp, Shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(Shape)
            .background(Surface)
            .border(1.dp, LineDark, Shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Página ${selected.position + 1}", style = label, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.Close, contentDescription = "Fechar revisão", tint = PaperText2,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(4.dp),
            )
        }
        AsyncImage(
            model = rememberPageRequest(selected.file, selected.rotation, thumbnailPx = 720),
            contentDescription = "Captura da página ${selected.position + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pages, key = { it.id }) { p ->
                AsyncImage(
                    model = rememberPageRequest(p.file, p.rotation, thumbnailPx = 160),
                    contentDescription = "Página ${p.position + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(36.dp)
                        .height(50.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .border(
                            width = if (p.id == selected.id) 2.dp else 1.dp,
                            color = if (p.id == selected.id) CopperDark else Color(0xFF4A443A),
                            shape = RoundedCornerShape(5.dp),
                        )
                        .clickable { selectedId = p.id; confirming = false },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (confirming) WarnDark else Color(0xFF2A2620))
                .clickable {
                    if (confirming) {
                        confirming = false // desarma já: um terceiro toque não exclui de novo
                        onDelete(selected.id)
                    } else {
                        confirming = true
                    }
                }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Delete, null, tint = if (confirming) Ink else WarnDark, modifier = Modifier.size(18.dp))
            Text(
                if (confirming) "  Toque de novo para excluir" else "  Excluir esta captura",
                style = label.copy(color = if (confirming) Ink else PaperText),
            )
        }
    }
}

/** Cartão mostrado por alguns segundos depois de concluir: leva direto ao livro capturado. */
@Composable
private fun FinishedCard(
    title: String,
    pages: Int,
    missing: List<Int>,
    onDrag: (Float, Float) -> Unit,
    onOpenBook: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(18.dp)
            .width(232.dp)
            .shadow(7.dp, Shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(Shape)
            .background(Surface)
            .auraBorder(AuraDark, width = 2.dp, cornerRadius = 22.dp, rotationMillis = 4_000, glow = false)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Captura concluída",
                style = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = PaperText),
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.Close, contentDescription = "Fechar", tint = PaperText2,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(4.dp),
            )
        }
        Text(
            "$pages ${if (pages == 1) "página salva" else "páginas salvas"} em \"$title\"",
            style = TextStyle(fontFamily = Geist, fontSize = 12.sp, color = PaperText2),
            maxLines = 2,
        )
        if (missing.isNotEmpty()) {
            Text(
                Plurals.missingPages(missing),
                style = TextStyle(fontFamily = Geist, fontSize = 12.sp, color = CopperDark),
                maxLines = 2,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CopperDark)
                .clickable(onClick = onOpenBook)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Abrir print-book", style = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Ink))
        }
    }
}

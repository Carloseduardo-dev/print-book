package com.printbook.app.capture.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.printbook.app.R
import com.printbook.app.capture.service.CaptureStateHolder
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.ui.components.auraBorder
import com.printbook.app.ui.theme.AuraDark
import com.printbook.app.ui.theme.CopperDark
import com.printbook.app.ui.theme.GeistMono
import com.printbook.app.ui.theme.Ink
import com.printbook.app.ui.theme.LineDark
import com.printbook.app.ui.theme.PaperText
import com.printbook.app.ui.theme.WarnDark

/**
 * Bolha flutuante (design 1B): tinta + cobre. No modo automático, a borda gira com a aura;
 * em aviso (página repetida, tela preta), borda âmbar fixa. Fica legível sobre fundo claro ou escuro.
 */
@Composable
fun BubbleContent(
    stateHolder: CaptureStateHolder,
    onDrag: (Float, Float) -> Unit,
    onPrimary: () -> Unit,
    onFinish: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val semiAuto = state.config?.mode != null && state.config?.mode != CaptureMode.MANUAL
    val shape = RoundedCornerShape(22.dp)
    val warning = state.warning != null && !state.autoActive

    Column(
        modifier = Modifier
            .padding(10.dp) // espaço para o halo da aura
            .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Ink.copy(alpha = 0.94f))
            .then(
                when {
                    state.autoActive -> Modifier.auraBorder(AuraDark, width = 2.dp, cornerRadius = 22.dp, rotationMillis = 2_600, glow = false)
                    warning -> Modifier.border(1.5.dp, WarnDark, shape)
                    else -> Modifier.border(1.dp, LineDark, shape)
                },
            )
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .padding(horizontal = 9.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val description = when {
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
                state.busy && !semiAuto -> CircularProgressIndicator(Modifier.size(22.dp), color = Ink, strokeWidth = 2.dp)
                !semiAuto -> Icon(painterResource(R.drawable.ic_pb_capture), null, tint = Ink, modifier = Modifier.size(24.dp))
                state.autoActive -> Icon(Icons.Filled.Pause, null, tint = Ink)
                else -> Icon(Icons.Filled.PlayArrow, null, tint = Ink)
            }
        }
        Text(
            "${state.bookPages}",
            style = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = PaperText),
        )
        if (state.autoStopMessage != null) {
            Text("fim", style = TextStyle(fontFamily = GeistMono, fontSize = 10.sp, color = CopperDark))
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
}

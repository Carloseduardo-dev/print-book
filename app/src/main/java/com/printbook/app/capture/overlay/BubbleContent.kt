package com.printbook.app.capture.overlay

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.printbook.app.capture.service.CaptureStateHolder
import com.printbook.app.data.prefs.CaptureMode
import com.printbook.app.ui.theme.PrintBookTheme

@Composable
fun BubbleContent(
    stateHolder: CaptureStateHolder,
    onDrag: (Float, Float) -> Unit,
    onPrimary: () -> Unit,
    onFinish: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val semiAuto = state.config?.mode != null && state.config?.mode != CaptureMode.MANUAL
    PrintBookTheme(darkTheme = true, dynamicColor = false) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val (icon, description) = when {
                    !semiAuto -> Icons.Filled.CameraAlt to "Capturar página"
                    state.autoActive -> Icons.Filled.Pause to "Pausar captura automática"
                    else -> Icons.Filled.PlayArrow to "Iniciar captura automática"
                }
                FilledIconButton(
                    onClick = onPrimary,
                    enabled = semiAuto || !state.busy,
                    modifier = Modifier.size(56.dp),
                    colors = if (state.autoActive) {
                        IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    } else {
                        IconButtonDefaults.filledIconButtonColors()
                    },
                ) {
                    if (state.busy && !semiAuto) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(icon, contentDescription = description)
                    }
                }
                Text("${state.bookPages}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                if (state.autoStopMessage != null) {
                    Text("fim", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (state.warning != null) {
                    Icon(Icons.Filled.Warning, contentDescription = state.warning, tint = MaterialTheme.colorScheme.error)
                }
                SmallFloatingActionButton(onClick = onFinish) {
                    Icon(Icons.Filled.Check, contentDescription = "Concluir captura")
                }
            }
        }
    }
}

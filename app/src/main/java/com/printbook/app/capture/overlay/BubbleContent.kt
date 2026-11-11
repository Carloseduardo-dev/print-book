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
import com.printbook.app.ui.theme.PrintBookTheme

@Composable
fun BubbleContent(
    stateHolder: CaptureStateHolder,
    onDrag: (Float, Float) -> Unit,
    onCapture: () -> Unit,
    onFinish: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    PrintBookTheme(darkTheme = true) {
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
                FilledIconButton(
                    onClick = onCapture,
                    enabled = !state.busy,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Capturar página")
                    }
                }
                Text("${state.captured}", style = MaterialTheme.typography.labelLarge)
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

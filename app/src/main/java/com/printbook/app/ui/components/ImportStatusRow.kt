package com.printbook.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.printbook.app.data.repository.ImportStatus

/** Andamento (barra Aura) ou resultado de "Imagens da galeria", com X para fechar o resultado. */
@Composable
fun ImportStatusRow(status: ImportStatus, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!status.running) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Fechar") }
            }
        }
        if (status.running) AuraProgressBar(progress = status.progress, modifier = Modifier.fillMaxWidth())
    }
}

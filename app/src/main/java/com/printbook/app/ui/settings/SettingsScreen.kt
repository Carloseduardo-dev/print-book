package com.printbook.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import com.printbook.app.BuildConfig
import com.printbook.app.ui.components.EmptyState

@Composable
fun SettingsScreen() {
    EmptyState(
        icon = Icons.Outlined.Settings,
        title = "Ajustes",
        message = "Versão ${BuildConfig.VERSION_NAME}\nModo de captura, tema e armazenamento chegam na próxima fase.",
    )
}

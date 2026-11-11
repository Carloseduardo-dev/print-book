package com.printbook.app.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.runtime.Composable
import com.printbook.app.ui.components.EmptyState

@Composable
fun LibraryScreen() {
    EmptyState(
        icon = Icons.AutoMirrored.Outlined.LibraryBooks,
        title = "Sua biblioteca está vazia",
        message = "Os print-books que você capturar aparecem aqui.",
    )
}

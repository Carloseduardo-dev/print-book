package com.printbook.app.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.printbook.app.ui.components.EmptyState

@Composable
fun HomeScreen(onStartCapture: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.AutoStories,
        title = "Bem-vindo ao print-book",
        message = "Capture as páginas dos seus apps de leitura e leia offline, em qualquer lugar.",
        action = { Button(onClick = onStartCapture) { Text("Capturar um livro") } },
    )
}

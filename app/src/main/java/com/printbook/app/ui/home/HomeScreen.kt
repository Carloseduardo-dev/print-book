package com.printbook.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.printbook.app.R
import com.printbook.app.ui.components.cropCorners
import com.printbook.app.ui.components.ReadingBar
import com.printbook.app.ui.theme.MonoLabel
import com.printbook.app.ui.theme.MonoMeta
import com.printbook.app.ui.theme.PrintBook
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.printbook.app.data.db.dao.BookWithCover
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.ui.components.BookCover
import com.printbook.app.ui.components.BookGridItem
import com.printbook.app.ui.components.EmptyState
import com.printbook.app.ui.components.progress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

data class HomeUiState(
    val loaded: Boolean = false,
    val recentlyRead: List<BookWithCover> = emptyList(),
    val recentlyAdded: List<BookWithCover> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(private val books: BookRepository) : ViewModel() {
    val state = combine(books.observeRecentlyRead(10), books.observeRecentlyAdded(10)) { read, added ->
        HomeUiState(true, read, added)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun coverFile(book: BookWithCover): File? = book.coverFile?.let { books.pageFile(book.id, it) }
}

@Composable
fun HomeScreen(
    onStartCapture: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    if (state.recentlyRead.isEmpty() && state.recentlyAdded.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.AutoStories,
            title = "Bem-vindo ao print-book",
            message = "Guarde páginas de livros, artigos, receitas, tutoriais e documentos de qualquer app. Tudo organizado e disponível offline.",
            action = { Button(onClick = onStartCapture) { Text("Fazer a primeira captura") } },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = PrintBook.tokens.subtle)
                Text("Início", style = MaterialTheme.typography.headlineMedium)
            }
        }
        state.recentlyRead.firstOrNull()?.let { current ->
            item {
                ContinueReadingCard(current, viewModel.coverFile(current)) { onContinueReading(current.id) }
            }
        }
        if (state.recentlyRead.size > 1) {
            item { Shelf("Lidos recentemente", state.recentlyRead.drop(1), viewModel::coverFile, onOpenBook) }
        }
        if (state.recentlyAdded.isNotEmpty()) {
            item { Shelf("Adicionados recentemente", state.recentlyAdded, viewModel::coverFile, onOpenBook) }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .clickable(onClick = onStartCapture)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_pb_capture), contentDescription = null)
                Column {
                    Text("Nova captura", style = MaterialTheme.typography.titleSmall)
                    Text("abra o leitor → ▶ na bolha", style = MonoMeta, color = PrintBook.tokens.subtle)
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(book: BookWithCover, cover: File?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                cover, book.title,
                Modifier.padding(5.dp).width(78.dp).cropCorners(MaterialTheme.colorScheme.primary),
                isFavorite = book.isFavorite,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CONTINUAR LENDO", style = MonoLabel, color = MaterialTheme.colorScheme.primary)
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("p. ${book.lastReadPosition + 1}/${book.pageCount} · ${(book.progress() * 100).toInt()}%", style = MonoMeta, color = PrintBook.tokens.subtle)
                ReadingBar(book.progress())
            }
        }
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Bom dia"
    in 12..17 -> "Boa tarde"
    else -> "Boa noite"
}

@Composable
private fun Shelf(title: String, books: List<BookWithCover>, cover: (BookWithCover) -> File?, onOpen: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(books, key = { it.id }) { book ->
                BookGridItem(book, cover(book), onClick = { onOpen(book.id) }, modifier = Modifier.width(120.dp))
            }
        }
    }
}

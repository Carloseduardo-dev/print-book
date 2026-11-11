package com.printbook.app.ui.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.BookStatus
import com.printbook.app.data.db.entity.CollectionEntity
import com.printbook.app.data.db.entity.PageEntity
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.ui.components.BookCover
import com.printbook.app.ui.components.formatBytes
import com.printbook.app.ui.navigation.BookDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class BookDetailUiState(
    val loaded: Boolean = false,
    val book: BookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val memberOf: Set<Long> = emptySet(),
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val books: BookRepository,
) : ViewModel() {
    val bookId = savedStateHandle.toRoute<BookDetailRoute>().bookId

    val state = combine(
        books.observeBook(bookId), books.observePages(bookId),
        books.observeCollections(), books.observeCollectionIds(bookId),
    ) { book, pages, cols, member ->
        BookDetailUiState(true, book, pages, cols, member.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState())

    fun coverFile(s: BookDetailUiState): File? {
        val book = s.book ?: return null
        val page = s.pages.firstOrNull { it.id == book.coverPageId } ?: s.pages.firstOrNull() ?: return null
        return books.pageFile(book.id, page.fileName)
    }

    fun toggleFavorite(current: Boolean) = viewModelScope.launch { books.setFavorite(bookId, !current) }
    fun rename(title: String, author: String) = viewModelScope.launch { books.rename(bookId, title, author) }
    fun markReady() = viewModelScope.launch { books.markReady(bookId) }
    fun setInCollection(collectionId: Long, inCollection: Boolean) =
        viewModelScope.launch { books.setInCollection(bookId, collectionId, inCollection) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { books.deleteBook(bookId); onDone() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onRead: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var collectionsDialog by remember { mutableStateOf(false) }
    val book = state.book

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                actions = {
                    if (book != null) {
                        IconButton(onClick = { viewModel.toggleFavorite(book.isFavorite) }) {
                            Icon(
                                if (book.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Favoritar",
                                tint = if (book.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { renameDialog = true }) { Icon(Icons.Outlined.Edit, "Renomear") }
                        IconButton(onClick = { deleteDialog = true }) { Icon(Icons.Outlined.Delete, "Excluir") }
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        if (book == null) {
            Text("Livro não encontrado.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(viewModel.coverFile(state), book.title, Modifier.width(130.dp), isFavorite = book.isFavorite)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall)
                    book.author?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text("${book.pageCount} páginas · ${formatBytes(book.totalBytes)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Capturado do ${book.sourceApp.label}", style = MaterialTheme.typography.bodySmall)
                    if (book.status == BookStatus.CAPTURING) {
                        Text("Captura em andamento", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            if (book.lastReadAt != null && book.pageCount > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Página ${book.lastReadPosition + 1} de ${book.pageCount}", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { book.lastReadPosition.toFloat() / (book.pageCount - 1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(
                onClick = { onRead(book.id) },
                enabled = book.pageCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null)
                Text(if (book.lastReadAt == null) "  Começar a ler" else "  Continuar lendo")
            }
            OutlinedButton(onClick = { onEdit(book.id) }, enabled = book.pageCount > 0, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.GridView, null)
                Text("  Editar páginas")
            }
            OutlinedButton(onClick = { collectionsDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.memberOf.isEmpty()) "Adicionar a uma coleção" else "Coleções (${state.memberOf.size})")
            }
            if (book.status == BookStatus.CAPTURING) {
                TextButton(onClick = viewModel::markReady) { Text("Marcar captura como concluída") }
            }
        }
    }

    if (renameDialog && book != null) {
        var title by remember { mutableStateOf(book.title) }
        var author by remember { mutableStateOf(book.author.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Editar informações") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
                    OutlinedTextField(author, { author = it }, label = { Text("Autor (opcional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.rename(title, author); renameDialog = false }, enabled = title.isNotBlank()) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancelar") } },
        )
    }

    if (deleteDialog && book != null) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Excluir \"${book.title}\"?") },
            text = { Text("As ${book.pageCount} páginas capturadas serão apagadas do aparelho. Isso não pode ser desfeito.") },
            confirmButton = {
                TextButton(onClick = { deleteDialog = false; viewModel.delete(onBack) }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancelar") } },
        )
    }

    if (collectionsDialog) {
        AlertDialog(
            onDismissRequest = { collectionsDialog = false },
            title = { Text("Coleções") },
            text = {
                if (state.collections.isEmpty()) {
                    Text("Crie coleções na aba Biblioteca (chip \"+ Coleção\").")
                } else {
                    Column {
                        state.collections.forEach { col ->
                            val checked = col.id in state.memberOf
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = { viewModel.setInCollection(col.id, it) })
                                Text(col.name)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { collectionsDialog = false }) { Text("Fechar") } },
        )
    }
}

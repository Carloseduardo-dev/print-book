package com.printbook.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.printbook.app.data.db.dao.BookWithCover
import com.printbook.app.data.db.entity.CollectionEntity
import com.printbook.app.data.prefs.LibraryLayout
import com.printbook.app.data.prefs.LibrarySort
import com.printbook.app.data.prefs.SettingsRepository
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.ui.components.BookGridItem
import com.printbook.app.ui.components.BookListItem
import com.printbook.app.ui.components.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class LibraryUiState(
    val loaded: Boolean = false,
    val books: List<BookWithCover> = emptyList(),
    val totalBooks: Int = 0,
    val collections: List<CollectionEntity> = emptyList(),
    val selectedCollection: Long? = null,
    val favoritesOnly: Boolean = false,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    val layout: LibraryLayout = LibraryLayout.GRID,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val books: BookRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val selectedCollection = MutableStateFlow<Long?>(null)

    private val source = selectedCollection.flatMapLatest { id ->
        if (id == null) books.observeLibrary() else books.observeInCollection(id)
    }

    val state = combine(source, books.observeCollections(), settings.settings, query, selectedCollection) { all, cols, s, q, col ->
        val filtered = all
            .filter { !s.favoritesOnly || it.isFavorite }
            .filter { q.isBlank() || BookRepository.sortKey(it.title).contains(BookRepository.sortKey(q)) }
        val sorted = when (s.librarySort) {
            LibrarySort.RECENT -> filtered.sortedByDescending { it.lastReadAt ?: it.createdAt }
            LibrarySort.TITLE -> filtered.sortedBy { BookRepository.sortKey(it.title) }
            LibrarySort.ADDED -> filtered.sortedByDescending { it.createdAt }
        }
        LibraryUiState(true, sorted, all.size, cols, col, s.favoritesOnly, q, s.librarySort, s.libraryLayout)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(q: String) { query.value = q }
    fun selectCollection(id: Long?) { selectedCollection.value = id }
    fun setFavoritesOnly(v: Boolean) = viewModelScope.launch { settings.setFavoritesOnly(v) }
    fun setSort(v: LibrarySort) = viewModelScope.launch { settings.setLibrarySort(v) }
    fun toggleLayout(current: LibraryLayout) = viewModelScope.launch {
        settings.setLibraryLayout(if (current == LibraryLayout.GRID) LibraryLayout.LIST else LibraryLayout.GRID)
    }
    fun createCollection(name: String) = viewModelScope.launch { if (name.isNotBlank()) books.createCollection(name) }
    fun coverFile(book: BookWithCover): File? = book.coverFile?.let { books.pageFile(book.id, it) }
}

@Composable
fun LibraryScreen(onOpenBook: (Long) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    var newCollectionDialog by remember { mutableStateOf(false) }
    if (!state.loaded) return

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Biblioteca", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Row {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Ordenar") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    LibrarySort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text((if (s == state.sort) "✓ " else "") + s.label) },
                            onClick = { viewModel.setSort(s); sortMenu = false },
                        )
                    }
                }
            }
            IconButton(onClick = { viewModel.toggleLayout(state.layout) }) {
                Icon(
                    if (state.layout == LibraryLayout.GRID) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                    contentDescription = "Alternar grade/lista",
                )
            }
        }

        if (state.totalBooks == 0 && state.selectedCollection == null) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                title = "Sua biblioteca está vazia",
                message = "Os print-books que você capturar aparecem aqui.",
            )
            return
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("Buscar por título") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.selectedCollection == null && !state.favoritesOnly,
                    onClick = { viewModel.selectCollection(null); viewModel.setFavoritesOnly(false) },
                    label = { Text("Todos") },
                )
            }
            item {
                FilterChip(
                    selected = state.favoritesOnly,
                    onClick = { viewModel.setFavoritesOnly(!state.favoritesOnly) },
                    label = { Text("Favoritos") },
                )
            }
            items(state.collections, key = { it.id }) { col ->
                FilterChip(
                    selected = state.selectedCollection == col.id,
                    onClick = { viewModel.selectCollection(if (state.selectedCollection == col.id) null else col.id) },
                    label = { Text(col.name) },
                )
            }
            item {
                FilterChip(
                    selected = false,
                    onClick = { newCollectionDialog = true },
                    label = { Text("Coleção") },
                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                )
            }
        }

        if (state.books.isEmpty()) {
            Text(
                "Nenhum livro encontrado.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.layout == LibraryLayout.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.books, key = { it.id }) { book ->
                    BookGridItem(book, viewModel.coverFile(book), onClick = { onOpenBook(book.id) })
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.books, key = { it.id }) { book ->
                    BookListItem(book, viewModel.coverFile(book), onClick = { onOpenBook(book.id) })
                }
            }
        }
    }

    if (newCollectionDialog) {
        NameDialog(
            title = "Nova coleção",
            initial = "",
            onDismiss = { newCollectionDialog = false },
            onConfirm = { viewModel.createCollection(it); newCollectionDialog = false },
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, label: String = "Nome") {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

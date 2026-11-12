package com.printbook.app.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.PageEntity
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.ui.components.rememberPageRequest
import com.printbook.app.ui.navigation.EditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class EditorUiState(
    val loaded: Boolean = false,
    val book: BookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val selected: Set<Long> = emptySet(),
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val books: BookRepository,
) : ViewModel() {
    val bookId = savedStateHandle.toRoute<EditorRoute>().bookId
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    val state = combine(books.observeBook(bookId), books.observePages(bookId), selected) { book, pages, sel ->
        val ids = pages.map { it.id }.toSet()
        EditorUiState(true, book, pages, sel intersect ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    fun file(page: PageEntity): File = books.pageFile(bookId, page.fileName)
    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }
    fun selectAll() = selected.update { state.value.pages.map { it.id }.toSet() }
    fun clearSelection() = selected.update { emptySet() }

    fun deleteSelected() = viewModelScope.launch {
        books.deletePages(bookId, state.value.selected)
        clearSelection()
    }

    fun setCover() = viewModelScope.launch {
        state.value.selected.singleOrNull()?.let { books.setCover(bookId, it) }
        clearSelection()
    }

    fun rotateSelected() = viewModelScope.launch {
        val s = state.value
        s.pages.filter { it.id in s.selected }.forEach { books.rotatePage(it.id, it.rotation) }
    }

    /** Move a página selecionada uma posição para trás (-1) ou para frente (+1). */
    fun move(delta: Int) = viewModelScope.launch {
        val s = state.value
        val id = s.selected.singleOrNull() ?: return@launch
        val ids = s.pages.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from == to) return@launch
        ids.add(to, ids.removeAt(from))
        books.reorderPages(bookId, ids)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenPage: (bookId: Long, pageIndex: Int) -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = state.selected.isNotEmpty()
    val single = state.selected.size == 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selecting) (if (state.selected.size == 1) "1 selecionada" else "${state.selected.size} selecionadas") else "Editar · ${state.book?.title.orEmpty()}")
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Outlined.Close, "Limpar seleção") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::selectAll) { Icon(Icons.Outlined.SelectAll, "Selecionar todas") }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                BottomAppBar {
                    IconButton(onClick = { viewModel.move(-1) }, enabled = single) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Mover para trás")
                    }
                    IconButton(onClick = { viewModel.move(1) }, enabled = single) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Mover para frente")
                    }
                    IconButton(onClick = viewModel::rotateSelected) { Icon(Icons.Outlined.RotateRight, "Girar") }
                    IconButton(onClick = viewModel::setCover, enabled = single) { Icon(Icons.Outlined.Image, "Definir como capa") }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        if (!selecting) {
            Text(
                "Segure para selecionar · toque para abrir",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 30.dp, bottom = padding.calculateBottomPadding() + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.pages, key = { _, p -> p.id }) { index, page ->
                val isSelected = page.id in state.selected
                // Com capa própria (galeria), nenhuma página é a capa.
                val isCover = state.book?.customCoverFile == null &&
                    (state.book?.coverPageId == page.id || (state.book?.coverPageId == null && index == 0))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.62f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .combinedClickable(
                            onClick = { if (selecting) viewModel.toggle(page.id) else onOpenPage(viewModel.bookId, index) },
                            onLongClick = { viewModel.toggle(page.id) },
                        ),
                ) {
                    AsyncImage(
                        model = rememberPageRequest(viewModel.file(page), page.rotation),
                        contentDescription = "Página ${index + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        "${index + 1}" + if (isCover) " · capa" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topEnd = 6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (state.selected.size == 1) "Excluir 1 página?" else "Excluir ${state.selected.size} páginas?") },
            text = { Text("As imagens serão apagadas do aparelho.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteSelected() }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

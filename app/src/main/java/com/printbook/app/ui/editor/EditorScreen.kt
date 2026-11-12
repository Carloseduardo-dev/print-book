package com.printbook.app.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.printbook.app.capture.service.CaptureStateHolder
import com.printbook.app.capture.service.ContinueRequest
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.PageEntity
import com.printbook.app.data.prefs.SettingsRepository
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.data.repository.PageOrder
import com.printbook.app.data.repository.importMessage
import com.printbook.app.ui.components.rememberPageRequest
import com.printbook.app.ui.navigation.EditorRoute
import com.printbook.app.ui.theme.PrintBook
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.io.File
import javax.inject.Inject

data class EditorUiState(
    val loaded: Boolean = false,
    val book: BookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val selected: Set<Long> = emptySet(),
    /** Páginas que acabaram de entrar (destacadas por alguns segundos). */
    val fresh: Set<Long> = emptySet(),
)

/** Destino do "Mover para…". */
sealed interface MoveTarget {
    data object Start : MoveTarget
    data object End : MoveTarget
    /** Antes da página [number] (numeração atual, 1-based). */
    data class Before(val number: Int) : MoveTarget
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val books: BookRepository,
    private val capture: CaptureStateHolder,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val bookId = savedStateHandle.toRoute<EditorRoute>().bookId
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val fresh = MutableStateFlow<Set<Long>>(emptySet())

    /** Ids já vistos: o que aparecer depois disso é página nova (galeria ou captura). */
    private var knownIds: Set<Long>? = null
    private var freshJob: Job? = null

    val state = combine(books.observeBook(bookId), books.observePages(bookId), selected, fresh) { book, pages, sel, fr ->
        val ids = pages.map { it.id }.toSet()
        EditorUiState(true, book, pages, sel intersect ids, fr intersect ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    init {
        viewModelScope.launch {
            books.observePages(bookId).collect { pages -> markFresh(pages.map { it.id }.toSet()) }
        }
    }

    private fun markFresh(ids: Set<Long>) {
        val added = PageOrder.added(knownIds, ids)
        knownIds = ids
        if (added.isEmpty()) return
        fresh.update { it + added }
        freshJob?.cancel()
        freshJob = viewModelScope.launch {
            delay(FRESH_HIGHLIGHT_MS)
            fresh.value = emptySet()
        }
    }

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
        val ids = s.pages.map { it.id }
        val from = ids.indexOf(id)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from == to) return@launch
        books.reorderPages(bookId, PageOrder.move(ids, from, to))
    }

    /** Arrastar e soltar (modo Organizar): grava a ordem montada na tela. */
    fun commitOrder(orderedIds: List<Long>) = viewModelScope.launch {
        if (orderedIds != state.value.pages.map { it.id }) books.reorderPages(bookId, orderedIds)
    }

    /** "Mover para…": leva as selecionadas (na ordem atual entre elas) para o destino. */
    fun moveSelectedTo(target: MoveTarget) = viewModelScope.launch {
        val s = state.value
        if (s.selected.isEmpty()) return@launch
        val ids = s.pages.map { it.id }
        val index = when (target) {
            MoveTarget.Start -> 0
            MoveTarget.End -> Int.MAX_VALUE
            // Posição na lista sem as selecionadas: quantas não selecionadas ficam antes da página N.
            is MoveTarget.Before -> PageOrder.targetBefore(ids, s.selected, target.number)
        }
        books.movePages(bookId, s.selected, index)
    }

    /** Posição (0-based) da única página selecionada, para "inserir depois desta". */
    private fun selectedPosition(): Int? {
        val s = state.value
        val id = s.selected.singleOrNull() ?: return null
        return s.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    /** Prepara a aba Capturar para adicionar páginas: no fim, ou depois da selecionada. */
    fun captureMore(afterSelected: Boolean) {
        val book = state.value.book ?: return
        val after = if (afterSelected) selectedPosition() ?: return else null
        capture.requestContinue(ContinueRequest(book.id, book.title, insertAfterPosition = after))
        clearSelection()
    }

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing
    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress

    /** Imagens da galeria: no fim ([after] null) ou depois da posição [after]. */
    fun importImages(uris: List<Uri>, after: Int?) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _importing.value = true
        _importProgress.value = 0f
        _importStatus.value = "Adicionando ${uris.size} ${if (uris.size == 1) "imagem" else "imagens"}…"
        val quality = settingsRepo.settings.first().pageQuality
        val r = books.importImages(bookId, uris, after, quality) { done, total ->
            _importProgress.value = if (total > 0) done.toFloat() / total else 1f
        }
        _importing.value = false
        _importStatus.value = importMessage(r)
        clearSelection()
    }

    /** Posição para a galeria: depois da selecionada (se pedido) ou no fim. */
    fun insertPositionForGallery(afterSelected: Boolean): Int? = if (afterSelected) selectedPosition() else null

    fun clearImportStatus() { _importStatus.value = null }

    private companion object {
        const val FRESH_HIGHLIGHT_MS = 4_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenPage: (bookId: Long, pageIndex: Int) -> Unit,
    onOpenCapture: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    // Onde entram as imagens escolhidas na galeria (definido antes de abrir o seletor).
    var galleryAfter by remember { mutableStateOf<Int?>(null) }
    val selecting = state.selected.isNotEmpty() && !organizing
    val single = state.selected.size == 1

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.importImages(uris, galleryAfter)
    }
    fun openGallery(afterSelected: Boolean) {
        galleryAfter = viewModel.insertPositionForGallery(afterSelected)
        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun captureMore(afterSelected: Boolean) {
        viewModel.captureMore(afterSelected)
        onOpenCapture()
    }

    // Ordem mostrada na tela: acompanha o banco, menos durante um arrasto.
    var order by remember { mutableStateOf(state.pages) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.pages) { if (!dragging) order = state.pages }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            organizing -> "Organizar"
                            selecting -> if (single) "1 selecionada" else "${state.selected.size} selecionadas"
                            else -> "Editar · ${state.book?.title.orEmpty()}"
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    when {
                        organizing -> IconButton(onClick = { organizing = false }) { Icon(Icons.Outlined.Close, "Concluir") }
                        selecting -> IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Outlined.Close, "Limpar seleção") }
                        else -> IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                    }
                },
                actions = {
                    if (organizing) {
                        TextButton(onClick = { organizing = false }) { Text("Concluir") }
                    } else {
                        IconButton(onClick = { viewModel.clearSelection(); organizing = true }, enabled = state.pages.size > 1) {
                            Icon(Icons.Outlined.SwapVert, "Organizar")
                        }
                        IconButton(onClick = viewModel::selectAll) { Icon(Icons.Outlined.SelectAll, "Selecionar todas") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!selecting && !organizing) {
                Box {
                    FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Outlined.Add, "Adicionar páginas") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Capturar mais páginas") }, onClick = { addMenu = false; captureMore(false) })
                        DropdownMenuItem(text = { Text("Imagens da galeria") }, onClick = { addMenu = false; openGallery(false) })
                    }
                }
            }
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
                    TextButton(onClick = { moveDialog = true }) { Text("Mover para…") }
                    Box(Modifier.weight(1f))
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "Mais") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("Girar") }, onClick = { moreMenu = false; viewModel.rotateSelected() })
                            DropdownMenuItem(
                                text = { Text("Definir como capa") },
                                enabled = single,
                                onClick = { moreMenu = false; viewModel.setCover() },
                            )
                            DropdownMenuItem(
                                text = { Text("Capturar páginas depois desta") },
                                enabled = single,
                                onClick = { moreMenu = false; captureMore(true) },
                            )
                            DropdownMenuItem(
                                text = { Text("Inserir imagens depois desta") },
                                enabled = single,
                                onClick = { moreMenu = false; openGallery(true) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        Column(
            Modifier.padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val hint = when {
                organizing -> "Segure e arraste uma página para mudar a ordem"
                selecting -> null
                else -> "Segure para selecionar · toque para abrir"
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            importStatus?.let { msg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (!importing) {
                        IconButton(onClick = viewModel::clearImportStatus) { Icon(Icons.Outlined.Close, "Fechar") }
                    }
                }
                if (importing) {
                    com.printbook.app.ui.components.AuraProgressBar(progress = importProgress, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val headerSpace = (if (selecting) 0.dp else 26.dp) + if (importStatus != null) 52.dp else 0.dp
        val aura = PrintBook.tokens.aura
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = padding.calculateTopPadding() + headerSpace + 4.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(order, key = { _, p -> p.id }) { index, page ->
                ReorderableItem(reorderState, key = page.id, enabled = organizing) { isDragging ->
                    val isSelected = page.id in state.selected
                    val isFresh = page.id in state.fresh
                    // Com capa própria (galeria), nenhuma página é a capa.
                    val isCover = state.book?.customCoverFile == null &&
                        (state.book?.coverPageId == page.id || (state.book?.coverPageId == null && index == 0))
                    val shape = RoundedCornerShape(6.dp)
                    val base = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.62f)
                        .shadow(if (isDragging) 8.dp else 0.dp, shape)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            when {
                                isSelected -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                                isFresh -> Modifier.border(3.dp, Brush.linearGradient(aura), shape)
                                else -> Modifier
                            },
                        )
                    val interaction = if (organizing) {
                        Modifier.longPressDraggableHandle(
                            onDragStarted = { dragging = true },
                            onDragStopped = {
                                dragging = false
                                viewModel.commitOrder(order.map { it.id })
                            },
                        )
                    } else {
                        Modifier.combinedClickable(
                            onClick = { if (selecting) viewModel.toggle(page.id) else onOpenPage(viewModel.bookId, index) },
                            onLongClick = { viewModel.toggle(page.id) },
                        )
                    }
                    Box(base.then(interaction)) {
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
                        if (organizing) {
                            Icon(
                                Icons.Outlined.DragIndicator,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
                            )
                        } else if (isSelected) {
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
    }

    if (moveDialog) {
        MoveToDialog(
            count = state.selected.size,
            pageCount = state.pages.size,
            onDismiss = { moveDialog = false },
            onConfirm = { target ->
                moveDialog = false
                viewModel.moveSelectedTo(target)
            },
        )
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

/** "Mover para…": início, fim ou antes de uma página escolhida pelo número. */
@Composable
private fun MoveToDialog(
    count: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (MoveTarget) -> Unit,
) {
    var choice by remember { mutableStateOf(0) } // 0 início, 1 fim, 2 antes da página N
    var number by remember { mutableStateOf("") }
    val n = number.toIntOrNull()
    val validNumber = n != null && n in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Mover 1 página" else "Mover $count páginas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Para o início", "Para o fim", "Antes da página…").forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = choice == i, onClick = { choice = i }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == i, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (choice == 2) {
                    OutlinedTextField(
                        value = number,
                        onValueChange = { v -> number = v.filter(Char::isDigit).take(5) },
                        label = { Text("Número da página (1 a $pageCount)") },
                        singleLine = true,
                        isError = number.isNotEmpty() && !validNumber,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = choice != 2 || validNumber,
                onClick = {
                    onConfirm(
                        when (choice) {
                            0 -> MoveTarget.Start
                            1 -> MoveTarget.End
                            else -> MoveTarget.Before(n ?: 1)
                        },
                    )
                },
            ) { Text("Mover") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

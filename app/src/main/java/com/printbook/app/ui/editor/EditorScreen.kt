package com.printbook.app.ui.editor

import android.net.Uri
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import com.printbook.app.ui.components.ImportStatusRow
import com.printbook.app.data.repository.InsertPoint
import com.printbook.app.data.repository.ImportStatus
import com.printbook.app.data.repository.GalleryImporter
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
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.data.repository.PageOrder
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
    private val importer: GalleryImporter,
) : ViewModel() {
    val bookId = savedStateHandle.toRoute<EditorRoute>().bookId
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val fresh = MutableStateFlow<Set<Long>>(emptySet())

    /** Ids já vistos: o que aparecer depois disso é página nova (galeria ou captura). */
    private var knownIds: Set<Long>? = null
    private var freshJob: Job? = null

    /** Uma consulta só das páginas (a grade e o destaque das novas usam a mesma). */
    private val pages = books.observePages(bookId)
        .onEach { list ->
            val ids = list.map { it.id }.toSet()
            val added = PageOrder.added(knownIds, ids)
            knownIds = ids
            flash(added)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state = combine(books.observeBook(bookId), pages.filterNotNull(), selected, fresh) { book, pages, sel, fr ->
        val ids = pages.map { it.id }.toSet()
        EditorUiState(true, book, pages, sel intersect ids, fr intersect ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    private val _scrollTo = MutableStateFlow<Long?>(null)
    /** Página para onde a grade deve rolar (depois de mover). Consumida com [scrolled]. */
    val scrollTo: StateFlow<Long?> = _scrollTo
    fun scrolled() { _scrollTo.value = null }

    /** Destaca páginas por alguns segundos (recém-inseridas ou recém-movidas). */
    private fun flash(ids: Set<Long>) {
        if (ids.isEmpty()) return
        fresh.update { it + ids }
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
        _scrollTo.value = id
    }

    /** Arrastar e soltar (modo Organizar): grava a ordem montada na tela. */
    fun commitOrder(orderedIds: List<Long>) = viewModelScope.launch {
        if (orderedIds != state.value.pages.map { it.id }) books.reorderPages(bookId, orderedIds)
    }

    /**
     * "Mover para…": leva as selecionadas (na ordem atual entre elas) para o destino. Elas
     * continuam selecionadas (dá para encadear outra ação); a grade rola até elas e as destaca.
     */
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
        _scrollTo.value = ids.first { it in s.selected }
        flash(s.selected)
    }

    /** Âncora "depois desta": a única página selecionada (por id, não por número). */
    fun selectedAnchor(): Long? = state.value.selected.singleOrNull()

    /** Prepara a aba Capturar para adicionar páginas: no fim, ou depois da selecionada. */
    fun captureMore(afterSelected: Boolean) {
        val s = state.value
        val book = s.book ?: return
        val request = if (afterSelected) {
            val id = selectedAnchor() ?: return
            val number = s.pages.indexOfFirst { it.id == id } + 1
            ContinueRequest(book.id, book.title, InsertPoint.After(id), "Depois da página $number")
        } else {
            ContinueRequest(book.id, book.title, InsertPoint.End, "No fim")
        }
        capture.requestContinue(request)
        clearSelection()
    }

    /** Andamento/resultado de "Imagens da galeria" deste print-book. */
    val importStatus: StateFlow<ImportStatus?> = importer.status.map { it[bookId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Imagens da galeria: no fim ([afterPageId] null) ou depois dessa página. Continua fora da tela. */
    fun importImages(uris: List<Uri>, afterPageId: Long?) {
        if (uris.isEmpty()) return
        importer.start(bookId, uris, afterPageId?.let { InsertPoint.After(it) } ?: InsertPoint.End)
        clearSelection()
    }

    fun clearImportStatus() = importer.dismiss(bookId)

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
    val scrollTo by viewModel.scrollTo.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    // Página-âncora das imagens da galeria (null = no fim). Saveable: o seletor pode recriar a
    // Activity (girar a tela) e o resultado chegaria sem saber onde inserir.
    var galleryAfter by rememberSaveable { mutableStateOf<Long?>(null) }
    val selecting = state.selected.isNotEmpty() && !organizing
    val single = state.selected.size == 1

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.importImages(uris, galleryAfter)
    }
    fun openGallery(afterSelected: Boolean) {
        galleryAfter = if (afterSelected) viewModel.selectedAnchor() else null
        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun captureMore(afterSelected: Boolean) {
        viewModel.captureMore(afterSelected)
        onOpenCapture()
    }

    // Ordem mostrada na tela: acompanha o banco, menos durante um arrasto.
    var order by remember { mutableStateOf(state.pages) }
    var dragging by remember { mutableStateOf(false) }
    // A chave inclui o fim do arrasto: soltar sem mudar nada também ressincroniza (página que
    // chegou durante o arrasto aparece).
    LaunchedEffect(state.pages, dragging) { if (!dragging) order = state.pages }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    // Depois de mover: rola até a página (que continua selecionada e pisca com a borda Aura).
    LaunchedEffect(scrollTo, state.pages) {
        val id = scrollTo ?: return@LaunchedEffect
        val index = state.pages.indexOfFirst { it.id == id }
        if (index >= 0) {
            gridState.animateScrollToItem(index)
            viewModel.scrolled()
        }
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
                    } else if (selecting) {
                        IconButton(onClick = viewModel::selectAll) { Icon(Icons.Outlined.SelectAll, "Selecionar todas") }
                        // "Concluir" deixa claro como terminar (o X à esquerda faz o mesmo).
                        TextButton(onClick = viewModel::clearSelection) { Text("Concluir") }
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
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(
                Modifier.padding(horizontal = 16.dp),
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
                importStatus?.let { ImportStatusRow(it, onDismiss = viewModel::clearImportStatus) }
            }
        val aura = PrintBook.tokens.aura
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                // Recém-inserida ou recém-movida: borda Aura por alguns segundos
                                // (vence a da seleção; o ícone de check continua indicando a seleção).
                                isFresh -> Modifier.border(3.dp, Brush.linearGradient(aura), shape)
                                isSelected -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
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

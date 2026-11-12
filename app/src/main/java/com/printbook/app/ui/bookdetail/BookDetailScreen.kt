package com.printbook.app.ui.bookdetail

import androidx.activity.result.PickVisualMediaRequest
import kotlinx.coroutines.flow.map
import com.printbook.app.ui.components.ImportStatusRow
import com.printbook.app.data.repository.InsertPoint
import com.printbook.app.data.repository.ImportStatus
import com.printbook.app.data.repository.GalleryImporter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.printbook.app.ui.components.AuraButton
import com.printbook.app.ui.components.AuraProgressBar
import androidx.compose.ui.unit.sp
import com.printbook.app.ui.theme.MonoMeta
import com.printbook.app.ui.theme.PrintBook
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.printbook.app.data.db.dao.OcrProgress
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.BookStatus
import com.printbook.app.data.db.entity.CollectionEntity
import com.printbook.app.data.db.entity.PageEntity
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.ui.components.BookCover
import com.printbook.app.ui.components.formatBytes
import com.printbook.app.ui.navigation.BookDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import com.printbook.app.capture.service.ContinueRequest
import com.printbook.app.capture.service.CaptureStateHolder
import com.printbook.app.data.prefs.PageQuality
import com.printbook.app.work.CompactStatus
import com.printbook.app.export.BookExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val ocr: OcrProgress? = null,
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val books: BookRepository,
    private val exporter: BookExporter,
    private val capture: CaptureStateHolder,
    private val importer: GalleryImporter,
) : ViewModel() {
    val canExport = exporter.available
    private val _export = MutableStateFlow<String?>(null)
    /** Mensagem de progresso/resultado da exportação (null = nada em andamento). */
    val exportStatus: StateFlow<String?> = _export

    fun exportPdf(target: Uri) = viewModelScope.launch {
        _export.value = "Gerando PDF..."
        val result = exporter.exportPdf(bookId, target) { done, total -> _export.value = "Gerando PDF: $done/$total páginas" }
        _export.value = result.fold({ "PDF salvo ($it páginas)" }, { "Falha ao exportar: ${it.message}" })
    }

    fun clearExportStatus() { _export.value = null }
    val bookId = savedStateHandle.toRoute<BookDetailRoute>().bookId

    val state = combine(
        books.observeBook(bookId), books.observePages(bookId),
        books.observeCollections(), books.observeCollectionIds(bookId),
    ) { book, pages, cols, member ->
        BookDetailUiState(true, book, pages, cols, member.toSet())
    }.combine(books.observeOcrProgress(bookId)) { s, ocr -> s.copy(ocr = ocr) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState())

    fun coverFile(s: BookDetailUiState): File? {
        val book = s.book ?: return null
        val name = BookRepository.resolveCover(
            book.customCoverFile,
            s.pages.firstOrNull { it.id == book.coverPageId }?.fileName,
            s.pages.firstOrNull()?.fileName,
        ) ?: return null
        return books.pageFile(book.id, name)
    }

    private val _coverError = MutableStateFlow<String?>(null)
    /** Erro ao trocar a capa (imagem que não abre, falta de espaço). */
    val coverError: StateFlow<String?> = _coverError
    fun clearCoverError() { _coverError.value = null }

    fun setCustomCover(uri: Uri) = viewModelScope.launch {
        books.setCustomCover(bookId, uri).onFailure {
            _coverError.value = "Não foi possível usar essa imagem como capa."
        }
    }

    fun removeCover() = viewModelScope.launch { books.removeCover(bookId) }

    // ---- Adicionar páginas ----
    /** Andamento/resultado de "Imagens da galeria" deste print-book (null = nada a mostrar). */
    val importStatus: StateFlow<ImportStatus?> = importer.status.map { it[bookId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Prepara a aba Capturar para continuar este print-book (no fim). */
    fun captureMore() {
        val book = state.value.book ?: return
        capture.requestContinue(ContinueRequest(book.id, book.title, InsertPoint.End, "No fim"))
    }

    /** Imagens da galeria entram no fim do print-book (continua mesmo saindo da tela). */
    fun importImages(uris: List<Uri>) = importer.start(bookId, uris, InsertPoint.End)

    fun clearImportStatus() = importer.dismiss(bookId)

    /** Progresso/resultado da compactação ("Liberar espaço"). */
    val compact: StateFlow<CompactStatus?> =
        books.observeCompact(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun compact(quality: PageQuality) = books.compact(bookId, quality)

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
    onOpenCapture: () -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var addMenu by remember { mutableStateOf(false) }
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.importImages(uris)
    }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var collectionsDialog by remember { mutableStateOf(false) }
    var exportWarning by remember { mutableStateOf(false) }
    var coverMenu by remember { mutableStateOf(false) }
    var compactDialog by remember { mutableStateOf(false) }
    val compact by viewModel.compact.collectAsStateWithLifecycle()
    val coverError by viewModel.coverError.collectAsStateWithLifecycle()
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setCustomCover(uri)
    }
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.exportPdf(uri)
    }
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
            Text("print-book não encontrado.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Tocar na capa abre o livro; o botão no canto troca a capa.
                Box(Modifier.width(130.dp)) {
                    BookCover(
                        viewModel.coverFile(state), book.title,
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = book.pageCount > 0) { onRead(book.id) },
                        isFavorite = book.isFavorite,
                    )
                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                        SmallFloatingActionButton(
                            onClick = { coverMenu = true },
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(34.dp),
                        ) { Icon(Icons.Outlined.Image, contentDescription = "Trocar capa", modifier = Modifier.size(18.dp)) }
                        DropdownMenu(expanded = coverMenu, onDismissRequest = { coverMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Imagem da galeria") },
                                onClick = {
                                    coverMenu = false
                                    coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Escolher uma página") },
                                enabled = book.pageCount > 0,
                                onClick = { coverMenu = false; onEdit(book.id) },
                            )
                            if (book.customCoverFile != null || book.coverPageId != null) {
                                DropdownMenuItem(
                                    text = { Text("Usar a primeira página") },
                                    onClick = { coverMenu = false; viewModel.removeCover() },
                                )
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall)
                    book.author?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(
                        "origem   ${book.sourceApp.label.lowercase()}\npáginas  ${book.pageCount}\ntamanho  ${formatBytes(book.totalBytes)}",
                        style = MonoMeta.copy(lineHeight = 19.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (book.status == BookStatus.CAPTURING) {
                        Text("Captura em andamento", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            if (book.lastReadAt != null && book.pageCount > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Página ${book.lastReadPosition + 1} de ${book.pageCount}", style = MaterialTheme.typography.bodyMedium)
                    AuraProgressBar(progress = book.lastReadPosition.toFloat() / (book.pageCount - 1))
                }
            }

            state.ocr?.let { ocr ->
                if (ocr.total > 0) {
                    val indexed = ocr.done + ocr.failed
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (indexed >= ocr.total) "Busca no texto disponível"
                            else "Preparando a busca no texto: $indexed de ${ocr.total} páginas",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (indexed >= ocr.total) PrintBook.tokens.success else PrintBook.tokens.subtle,
                        )
                        if (indexed < ocr.total) {
                            AuraProgressBar(progress = indexed.toFloat() / ocr.total, height = 3.dp)
                        }
                    }
                }
            }

            AuraButton(
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
            Box {
                OutlinedButton(onClick = { addMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null)
                    Text("  Adicionar páginas")
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Capturar mais páginas") },
                        onClick = { addMenu = false; viewModel.captureMore(); onOpenCapture() },
                    )
                    DropdownMenuItem(
                        text = { Text("Imagens da galeria") },
                        onClick = {
                            addMenu = false
                            galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                }
            }
            importStatus?.let { ImportStatusRow(it, onDismiss = viewModel::clearImportStatus) }
            if (book.pageCount > 0 && book.status != BookStatus.CAPTURING) {
                when (val c = compact) {
                    is CompactStatus.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (c.total > 0) "Compactando: ${c.done} de ${c.total} páginas" else "Preparando a compactação...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        AuraProgressBar(progress = if (c.total > 0) c.done.toFloat() / c.total else 0f, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        OutlinedButton(onClick = { compactDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Liberar espaço")
                        }
                        if (c is CompactStatus.Done) {
                            Text(
                                if (c.freedBytes > 0) "Liberou ${formatBytes(c.freedBytes)}." else "Este print-book já estava compactado.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { collectionsDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.memberOf.isEmpty()) "Adicionar a uma coleção" else "Coleções (${state.memberOf.size})")
            }
            if (viewModel.canExport && book.pageCount > 0) {
                OutlinedButton(onClick = { exportWarning = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Exportar PDF")
                }
                exportStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (book.status == BookStatus.CAPTURING) {
                TextButton(onClick = viewModel::markReady) { Text("Marcar captura como concluída") }
            }
        }
    }

    coverError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearCoverError,
            title = { Text("Capa não alterada") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearCoverError) { Text("OK") } },
        )
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
            text = {
                Text(
                    when (book.pageCount) {
                        0 -> "Este print-book será apagado. Isso não pode ser desfeito."
                        1 -> "A página capturada será apagada do aparelho. Isso não pode ser desfeito."
                        else -> "As ${book.pageCount} páginas capturadas serão apagadas do aparelho. Isso não pode ser desfeito."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteDialog = false; viewModel.delete(onBack) }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancelar") } },
        )
    }

    if (exportWarning && book != null) {
        AlertDialog(
            onDismissRequest = { exportWarning = false },
            title = { Text("Exportar PDF") },
            text = { Text("O PDF é para seu uso pessoal. Não compartilhe conteúdo protegido por direitos autorais.") },
            confirmButton = {
                TextButton(onClick = {
                    exportWarning = false
                    viewModel.clearExportStatus()
                    pdfLauncher.launch("${book.title}.pdf")
                }) { Text("Escolher onde salvar") }
            },
            dismissButton = { TextButton(onClick = { exportWarning = false }) { Text("Cancelar") } },
        )
    }

    if (compactDialog && book != null) {
        AlertDialog(
            onDismissRequest = { compactDialog = false },
            title = { Text("Liberar espaço") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Hoje este print-book ocupa ${formatBytes(book.totalBytes)}. As páginas são recodificadas no aparelho, em segundo plano.")
                    Text("Econômica: cerca de metade do espaço, mantém as cores.", style = MaterialTheme.typography.bodySmall)
                    Text("Texto: o menor tamanho (cerca de 1/3), em tons de cinza e sem perder nitidez. Perde as cores das figuras.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { compactDialog = false; viewModel.compact(PageQuality.ECONOMICA) }) { Text("Econômica") }
            },
            dismissButton = {
                TextButton(onClick = { compactDialog = false; viewModel.compact(PageQuality.TEXTO) }) { Text("Texto") }
            },
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

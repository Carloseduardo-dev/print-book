package com.printbook.app.ui.reader

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.printbook.app.ui.components.auraOnFocus
import androidx.compose.ui.unit.sp
import com.printbook.app.ui.theme.MonoMeta
import android.app.Activity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.snapshotFlow
import com.printbook.app.data.db.dao.TextSearchHit
import com.printbook.app.ui.components.SearchHitItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.PageEntity
import com.printbook.app.data.prefs.ReaderFilter
import com.printbook.app.data.prefs.Settings
import com.printbook.app.data.prefs.SettingsRepository
import com.printbook.app.data.repository.BookRepository
import com.printbook.app.ui.components.rememberPageRequest
import com.printbook.app.ui.navigation.ReaderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File
import javax.inject.Inject

data class ReaderUiState(
    val loaded: Boolean = false,
    val book: BookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val bookmarkedPageIds: Set<Long> = emptySet(),
    val settings: Settings = Settings(),
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val books: BookRepository,
    private val settingsRepo: SettingsRepository,
    val keyEvents: ReaderKeyEvents,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ReaderRoute>()
    val bookId = route.bookId
    val requestedPage = route.pageIndex

    val state = combine(
        books.observeBook(bookId), books.observePages(bookId), books.observeBookmarks(bookId), settingsRepo.settings,
    ) { book, pages, marks, settings ->
        ReaderUiState(true, book, pages, marks.map { it.pageId }.toSet(), settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    fun file(page: PageEntity): File = books.pageFile(bookId, page.fileName)
    suspend fun saveProgress(position: Int) = books.setProgress(bookId, position)
    fun toggleBookmark(page: PageEntity, bookmarked: Boolean) =
        viewModelScope.launch { books.toggleBookmark(bookId, page.id, bookmarked) }
    fun setFilter(filter: ReaderFilter) = viewModelScope.launch { settingsRepo.setReaderFilter(filter) }
    suspend fun searchInBook(query: String): List<TextSearchHit> = books.searchInBook(bookId, query)
}

private fun ReaderFilter.colorFilter(): ColorFilter? = when (this) {
    ReaderFilter.NONE -> null
    ReaderFilter.INVERT -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    ReaderFilter.SEPIA -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    ReaderFilter.DIM -> ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.6f, 0.6f, 0.6f, 1f) })
}

@Composable
fun ReaderScreen(onBack: () -> Unit, viewModel: ReaderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val book = state.book
    if (!state.loaded || book == null || state.pages.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    ReaderContent(state, book, viewModel, onBack)
}

@Composable
private fun ReaderContent(state: ReaderUiState, book: BookEntity, viewModel: ReaderViewModel, onBack: () -> Unit) {
    val pages = state.pages
    val initial = (if (viewModel.requestedPage >= 0) viewModel.requestedPage else book.lastReadPosition)
        .coerceIn(0, pages.lastIndex)
    val pagerState = rememberPagerState(initialPage = initial) { pages.size }
    val scope = rememberCoroutineScope()
    var chromeVisible by remember { mutableStateOf(true) }
    var filterMenu by remember { mutableStateOf(false) }
    val zoomStates = remember { mutableStateMapOf<Int, ZoomableState>() }
    // Busca dentro do livro.
    var searchOpen by remember { mutableStateOf(false) }
    val colorFilter = state.settings.readerFilter.colorFilter()

    // Tela cheia (barras do sistema escondidas quando o menu do leitor some) e tela sempre ligada.
    val view = LocalView.current
    DisposableEffect(chromeVisible) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chromeVisible) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = state.settings.keepScreenOn
        onDispose {
            view.keepScreenOn = false
            (view.context as? Activity)?.window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Teclas de volume viram a página (se ligado nos Ajustes).
    DisposableEffect(state.settings.volumeKeysTurnPages) {
        viewModel.keyEvents.active = state.settings.volumeKeysTurnPages
        onDispose { viewModel.keyEvents.active = false }
    }
    LaunchedEffect(Unit) {
        viewModel.keyEvents.turns.collect { turn ->
            val target = when (turn) {
                ReaderKeyEvents.Turn.NEXT -> pagerState.currentPage + 1
                ReaderKeyEvents.Turn.PREVIOUS -> pagerState.currentPage - 1
            }.coerceIn(0, pages.lastIndex)
            pagerState.animateScrollToPage(target)
        }
    }

    // Progresso salvo com debounce e ao sair.
    LaunchedEffect(pagerState.currentPage) {
        delay(600)
        viewModel.saveProgress(pagerState.currentPage)
    }
    DisposableEffect(Unit) {
        onDispose {
            val last = pagerState.currentPage
            scope.launch { withContext(NonCancellable) { viewModel.saveProgress(last) } }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { pages[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val page = pages[index]
            val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maximum = ZoomLimit(factor = 5f)))
            DisposableEffect(index, zoomState) {
                zoomStates[index] = zoomState
                onDispose { zoomStates.remove(index) }
            }
            ZoomableAsyncImage(
                model = rememberPageRequest(viewModel.file(page), page.rotation),
                contentDescription = "Página ${index + 1}",
                state = rememberZoomableImageState(zoomState),
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxSize(),
                onClick = { offset ->
                    val width = view.width.toFloat()
                    when {
                        offset.x < width * 0.25f -> scope.launch { pagerState.animateScrollToPage((index - 1).coerceAtLeast(0)) }
                        offset.x > width * 0.75f -> scope.launch { pagerState.animateScrollToPage((index + 1).coerceAtMost(pages.lastIndex)) }
                        else -> chromeVisible = !chromeVisible
                    }
                },
                onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = 3f),
            )
        }

        val current = pages[pagerState.currentPage]
        val bookmarked = current.id in state.bookmarkedPageIds

        AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = Color.Black.copy(alpha = 0.9f), contentColor = Color.White) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                    Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { searchOpen = true }) { Icon(Icons.Outlined.Search, "Buscar no livro") }
                    IconButton(onClick = { viewModel.toggleBookmark(current, bookmarked) }) {
                        Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Marcador")
                    }
                    Box {
                        IconButton(onClick = { filterMenu = true }) { Icon(Icons.Outlined.Contrast, "Cores") }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            ReaderFilter.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text((if (f == state.settings.readerFilter) "✓ " else "") + f.label) },
                                    onClick = { viewModel.setFilter(f); filterMenu = false },
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = Color.Black.copy(alpha = 0.9f), contentColor = Color.White) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (pages.size > 1) {
                        var sliderValue by remember(pagerState.currentPage) { mutableStateOf(pagerState.currentPage.toFloat()) }
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = { scope.launch { pagerState.scrollToPage(sliderValue.toInt()) } },
                            valueRange = 0f..pages.lastIndex.toFloat(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "p. ${pagerState.currentPage + 1} / ${pages.size}",
                            style = MonoMeta.copy(fontSize = 12.sp),
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { scope.launch { zoomStates[pagerState.currentPage]?.zoomBy(zoomFactor = 1f / 1.5f) } }) {
                            Icon(Icons.Outlined.ZoomOut, "Diminuir zoom")
                        }
                        IconButton(onClick = { scope.launch { zoomStates[pagerState.currentPage]?.resetZoom() } }) {
                            Icon(Icons.Outlined.CenterFocusStrong, "Ajustar à tela")
                        }
                        IconButton(onClick = { scope.launch { zoomStates[pagerState.currentPage]?.zoomBy(zoomFactor = 1.5f) } }) {
                            Icon(Icons.Outlined.ZoomIn, "Aumentar zoom")
                        }
                    }
                }
            }
        }
    }

    if (searchOpen) {
        BookSearchSheet(
            search = viewModel::searchInBook,
            onDismiss = { searchOpen = false },
            onOpen = { index ->
                searchOpen = false
                scope.launch { pagerState.scrollToPage(index.coerceIn(0, pages.lastIndex)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun BookSearchSheet(
    search: suspend (String) -> List<TextSearchHit>,
    onDismiss: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TextSearchHit>>(emptyList()) }
    LaunchedEffect(Unit) {
        snapshotFlow { query }.debounce(300).collect { q ->
            hits = if (q.trim().length >= 2) search(q) else emptyList()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar neste livro") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).auraOnFocus(cornerRadius = 14.dp),
            )
            if (query.trim().length >= 2) {
                Text(
                    "${hits.size} página(s)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(hits, key = { it.pageId }) { hit ->
                    SearchHitItem(hit, showBook = false, onClick = { onOpen(hit.position) })
                }
            }
        }
    }
}

package com.printbook.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.printbook.app.data.db.entity.SourceApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class CaptureMode(val label: String, val description: String) {
    MANUAL("Manual", "Toque na bolha para capturar cada página."),
    SEMI_AUTO("Semiautomático", "Você vira a página; o app captura sozinho quando a tela para de mudar."),
    AUTO("Automático", "O app vira as páginas sozinho e para no fim do livro."),
}

enum class PageQuality(val label: String, val description: String) {
    MAXIMA("Máxima", "Sem perda (WebP lossless). Maior nitidez, ocupa mais espaço."),
    ECONOMICA("Econômica", "WebP com qualidade 90. Cerca de metade do espaço, ainda nítido."),
    TEXTO("Texto", "Tons de cinza, sem perda de nitidez. O menor tamanho para livros de texto; perde as cores das figuras."),
}

/** Intervalos da virada automática (ms) e limites de páginas por sessão (0 = sem limite). */
val AUTO_INTERVALS_MS = listOf(1_500L, 2_500L, 4_000L)
val MAX_PAGES_OPTIONS = listOf(100, 300, 500, 0)

enum class LibrarySort(val label: String) { RECENT("Recentes"), TITLE("Título"), ADDED("Adicionados") }
enum class LibraryLayout { GRID, LIST }
enum class ThemeMode(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Escuro") }
enum class ReaderFilter(val label: String) { NONE("Original"), INVERT("Invertido"), SEPIA("Sépia"), DIM("Escurecido") }

data class Settings(
    val captureMode: CaptureMode = CaptureMode.SEMI_AUTO,
    val autoCrop: Boolean = true,
    val skipDuplicates: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryLayout: LibraryLayout = LibraryLayout.GRID,
    val favoritesOnly: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val readerFilter: ReaderFilter = ReaderFilter.NONE,
    val keepScreenOn: Boolean = true,
    val lastSourceApp: SourceApp = SourceApp.KINDLE,
    val pageQuality: PageQuality = PageQuality.MAXIMA,
    val autoIntervalMs: Long = 2_500L,
    val maxPagesPerSession: Int = 500,
    val volumeKeysTurnPages: Boolean = true,
    /** Modo Turbo: viradas e detecção mais rápidas (Semi e Automático). */
    val turbo: Boolean = false,
    /** Automático: ler o total de páginas do leitor e capturar até o fim do livro. */
    val wholeBook: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private object Keys {
        val captureMode = stringPreferencesKey("capture_mode")
        val autoCrop = booleanPreferencesKey("auto_crop")
        val skipDuplicates = booleanPreferencesKey("skip_duplicates")
        val librarySort = stringPreferencesKey("library_sort")
        val libraryLayout = stringPreferencesKey("library_layout")
        val favoritesOnly = booleanPreferencesKey("favorites_only")
        val themeMode = stringPreferencesKey("theme_mode")
        val readerFilter = stringPreferencesKey("reader_filter")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val lastSourceApp = stringPreferencesKey("last_source_app")
        val pageQuality = stringPreferencesKey("page_quality")
        val autoIntervalMs = longPreferencesKey("auto_interval_ms")
        val maxPagesPerSession = intPreferencesKey("max_pages_per_session")
        val volumeKeys = booleanPreferencesKey("volume_keys")
        val turbo = booleanPreferencesKey("turbo")
        val wholeBook = booleanPreferencesKey("whole_book")
    }

    val settings: Flow<Settings> = store.data.map { p ->
        val d = Settings()
        Settings(
            captureMode = p[Keys.captureMode].toEnum(d.captureMode),
            autoCrop = p[Keys.autoCrop] ?: d.autoCrop,
            skipDuplicates = p[Keys.skipDuplicates] ?: d.skipDuplicates,
            librarySort = p[Keys.librarySort].toEnum(d.librarySort),
            libraryLayout = p[Keys.libraryLayout].toEnum(d.libraryLayout),
            favoritesOnly = p[Keys.favoritesOnly] ?: d.favoritesOnly,
            themeMode = p[Keys.themeMode].toEnum(d.themeMode),
            readerFilter = p[Keys.readerFilter].toEnum(d.readerFilter),
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            lastSourceApp = p[Keys.lastSourceApp].toEnum(d.lastSourceApp),
            pageQuality = p[Keys.pageQuality].toEnum(d.pageQuality),
            autoIntervalMs = p[Keys.autoIntervalMs] ?: d.autoIntervalMs,
            maxPagesPerSession = p[Keys.maxPagesPerSession] ?: d.maxPagesPerSession,
            volumeKeysTurnPages = p[Keys.volumeKeys] ?: d.volumeKeysTurnPages,
            turbo = p[Keys.turbo] ?: d.turbo,
            wholeBook = p[Keys.wholeBook] ?: d.wholeBook,
        )
    }

    suspend fun setCaptureMode(v: CaptureMode) = store.edit { it[Keys.captureMode] = v.name }
    suspend fun setAutoCrop(v: Boolean) = store.edit { it[Keys.autoCrop] = v }
    suspend fun setSkipDuplicates(v: Boolean) = store.edit { it[Keys.skipDuplicates] = v }
    suspend fun setLibrarySort(v: LibrarySort) = store.edit { it[Keys.librarySort] = v.name }
    suspend fun setLibraryLayout(v: LibraryLayout) = store.edit { it[Keys.libraryLayout] = v.name }
    suspend fun setFavoritesOnly(v: Boolean) = store.edit { it[Keys.favoritesOnly] = v }
    suspend fun setThemeMode(v: ThemeMode) = store.edit { it[Keys.themeMode] = v.name }
    suspend fun setReaderFilter(v: ReaderFilter) = store.edit { it[Keys.readerFilter] = v.name }
    suspend fun setKeepScreenOn(v: Boolean) = store.edit { it[Keys.keepScreenOn] = v }
    suspend fun setLastSourceApp(v: SourceApp) = store.edit { it[Keys.lastSourceApp] = v.name }
    suspend fun setPageQuality(v: PageQuality) = store.edit { it[Keys.pageQuality] = v.name }
    suspend fun setAutoIntervalMs(v: Long) = store.edit { it[Keys.autoIntervalMs] = v }
    suspend fun setMaxPagesPerSession(v: Int) = store.edit { it[Keys.maxPagesPerSession] = v }
    suspend fun setVolumeKeysTurnPages(v: Boolean) = store.edit { it[Keys.volumeKeys] = v }
    suspend fun setTurbo(v: Boolean) = store.edit { it[Keys.turbo] = v }
    suspend fun setWholeBook(v: Boolean) = store.edit { it[Keys.wholeBook] = v }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}

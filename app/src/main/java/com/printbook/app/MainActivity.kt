package com.printbook.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.printbook.app.data.prefs.Settings
import com.printbook.app.data.prefs.SettingsRepository
import com.printbook.app.data.prefs.ThemeMode
import com.printbook.app.ui.navigation.PrintBookNavHost
import com.printbook.app.ui.reader.ReaderKeyEvents
import javax.inject.Inject
import com.printbook.app.ui.theme.PrintBookTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var readerKeys: ReaderKeyEvents
    @Inject lateinit var captureState: com.printbook.app.capture.service.CaptureStateHolder

    override fun onResume() {
        super.onResume()
        captureState.appVisible = true
    }

    override fun onPause() {
        captureState.appVisible = false
        super.onPause()
    }

    /** Livro a abrir vindo de fora (cartão "Abrir livro" da bolha, notificação). */
    private val openBook = MutableStateFlow<Long?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readOpenBook(intent)
    }

    private fun readOpenBook(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_OPEN_BOOK_ID, -1L) ?: -1L
        if (id > 0) {
            openBook.value = id
            intent?.removeExtra(EXTRA_OPEN_BOOK_ID)
        }
    }

    /** Volume baixo = próxima página, volume alto = anterior, só com o leitor aberto. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (readerKeys.active) {
            val turn = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> ReaderKeyEvents.Turn.NEXT
                KeyEvent.KEYCODE_VOLUME_UP -> ReaderKeyEvents.Turn.PREVIOUS
                else -> null
            }
            if (turn != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) readerKeys.emit(turn)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readOpenBook(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = Settings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            PrintBookTheme(darkTheme = dark) {
                val pending by openBook.collectAsStateWithLifecycle()
                PrintBookNavHost(openBookId = pending, onBookOpened = { openBook.value = null })
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK_ID = "open_book_id"

        /** Intent que abre o print-book direto no Detalhe do livro. */
        fun openBookIntent(context: android.content.Context, bookId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_BOOK_ID, bookId)
    }
}

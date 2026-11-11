package com.printbook.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
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
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = Settings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            PrintBookTheme(darkTheme = dark) {
                PrintBookNavHost()
            }
        }
    }
}

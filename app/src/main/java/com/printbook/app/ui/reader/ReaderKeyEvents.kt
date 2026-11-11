package com.printbook.app.ui.reader

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Teclas de volume -> virar página. A MainActivity intercepta as teclas só enquanto o leitor está
 * aberto e o ajuste está ligado ([active]); fora disso o volume funciona normalmente.
 */
@Singleton
class ReaderKeyEvents @Inject constructor() {
    enum class Turn { NEXT, PREVIOUS }

    @Volatile var active: Boolean = false

    private val _turns = MutableSharedFlow<Turn>(extraBufferCapacity = 8)
    val turns: SharedFlow<Turn> = _turns.asSharedFlow()

    fun emit(turn: Turn): Boolean = _turns.tryEmit(turn)
}

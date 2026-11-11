package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import com.printbook.app.capture.analysis.AutoTurnController.Decision
import com.printbook.app.capture.analysis.AutoTurnController.Reason
import com.printbook.app.capture.analysis.AutoTurnController.Result
import org.junit.Test

class AutoTurnControllerTest {

    private fun AutoTurnController.feed(vararg r: Result): List<Decision> = r.map { onResult(it) }

    @Test
    fun `paginas novas continuam`() {
        val c = AutoTurnController(maxPages = null)
        assertThat(c.feed(*Array(50) { Result.SAVED })).containsExactlyElementsIn(List(50) { Decision.Continue })
        assertThat(c.savedPages).isEqualTo(50)
    }

    @Test
    fun `tres duplicatas seguidas indicam fim do livro`() {
        val c = AutoTurnController()
        val d = c.feed(Result.SAVED, Result.DUPLICATE, Result.DUPLICATE, Result.DUPLICATE)
        assertThat(d.last()).isEqualTo(Decision.Stop(Reason.END_OF_BOOK))
    }

    @Test
    fun `duplicata isolada nao para`() {
        val c = AutoTurnController()
        val d = c.feed(Result.DUPLICATE, Result.DUPLICATE, Result.SAVED, Result.DUPLICATE, Result.DUPLICATE)
        assertThat(d).doesNotContain(Decision.Stop(Reason.END_OF_BOOK))
    }

    @Test
    fun `duas viradas sem mudanca indicam fim do livro`() {
        val c = AutoTurnController()
        assertThat(c.feed(Result.SAVED, Result.NO_CHANGE, Result.NO_CHANGE).last())
            .isEqualTo(Decision.Stop(Reason.END_OF_BOOK))
    }

    @Test
    fun `limite de paginas`() {
        val c = AutoTurnController(maxPages = 3)
        assertThat(c.feed(Result.SAVED, Result.SAVED, Result.SAVED).last()).isEqualTo(Decision.Stop(Reason.PAGE_LIMIT))
    }

    @Test
    fun `quadro preto pausa imediatamente`() {
        assertThat(AutoTurnController().onResult(Result.BLACK)).isEqualTo(Decision.Pause(Reason.BLOCKED))
    }

    @Test
    fun `falhas seguidas pausam e sucesso zera a contagem`() {
        val c = AutoTurnController()
        assertThat(c.feed(Result.FAILED, Result.FAILED, Result.SAVED, Result.FAILED, Result.FAILED))
            .doesNotContain(Decision.Pause(Reason.FAILURES))
        assertThat(c.onResult(Result.FAILED)).isEqualTo(Decision.Pause(Reason.FAILURES))
    }
}

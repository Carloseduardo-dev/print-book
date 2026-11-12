package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import com.printbook.app.capture.analysis.GestureRetry.Result
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GestureRetryTest {

    /** Leitor de mentira: [starts] diz, por tentativa, o que a tela fez depois do gesto. */
    private class FakeReader(private val starts: List<Boolean?>, private val settles: Boolean = true, private val gestureOk: Boolean = true) {
        var gestures = 0
        var prepared = 0
        val retries = mutableListOf<Int>()
        suspend fun run(attempts: Int = 3) = GestureRetry.turn(
            attempts = attempts,
            retryDelayMs = 500,
            beforeAttempt = { prepared++ },
            gesture = { gestures++; gestureOk },
            awaitStart = { starts.getOrNull(gestures - 1) },
            awaitSettle = { settles },
            onRetry = { retries += it },
        )
    }

    @Test
    fun `gesto aceito de primeira`() = runTest {
        val r = FakeReader(listOf(false))
        assertThat(r.run()).isEqualTo(Result.SETTLED)
        assertThat(r.gestures).isEqualTo(1)
        assertThat(r.retries).isEmpty()
    }

    @Test
    fun `gesto ignorado e aceito na segunda tentativa`() = runTest {
        // O caso da página 433: o leitor ignorou o primeiro deslize para trás.
        val r = FakeReader(listOf(null, false))
        assertThat(r.run()).isEqualTo(Result.SETTLED)
        assertThat(r.gestures).isEqualTo(2)
        assertThat(r.prepared).isEqualTo(2) // gatilho armado de novo antes de cada tentativa
        assertThat(r.retries).containsExactly(2)
    }

    @Test
    fun `todas as tentativas ignoradas`() = runTest {
        val r = FakeReader(listOf(null, null, null))
        assertThat(r.run(attempts = 3)).isEqualTo(Result.IGNORED)
        assertThat(r.gestures).isEqualTo(3)
    }

    @Test
    fun `tela que comecou a mudar nao recebe outro gesto`() = runTest {
        // Repetir o gesto depois que a tela mudou viraria uma página a mais.
        val r = FakeReader(listOf(false), settles = false)
        assertThat(r.run()).isEqualTo(Result.UNSETTLED)
        assertThat(r.gestures).isEqualTo(1)
    }

    @Test
    fun `tela que ja parou durante a espera do inicio`() = runTest {
        val r = FakeReader(listOf(true))
        assertThat(r.run()).isEqualTo(Result.SETTLED)
        assertThat(r.gestures).isEqualTo(1)
    }

    @Test
    fun `gesto que nao pode ser feito conta como ignorado`() = runTest {
        val r = FakeReader(listOf(false), gestureOk = false)
        assertThat(r.run(attempts = 2)).isEqualTo(Result.IGNORED)
        assertThat(r.gestures).isEqualTo(2)
    }
}

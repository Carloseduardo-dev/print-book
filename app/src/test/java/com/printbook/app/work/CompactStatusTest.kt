package com.printbook.app.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompactStatusTest {

    private val now = 1_000_000_000L
    private val minute = 60_000L

    @Test
    fun `resultado recente do proprio livro aparece`() {
        val done = CompactStatus.Done(freedBytes = 500, finishedAt = now - 2 * minute)
        assertThat(done.isCurrent(bookCreatedAt = now - 60 * minute, now = now)).isTrue()
    }

    @Test
    fun `livro novo com id reaproveitado nao herda a mensagem do antigo`() {
        // A compactação terminou antes de o livro atual existir: era do livro apagado.
        val done = CompactStatus.Done(freedBytes = 0, finishedAt = now - 10 * minute)
        assertThat(done.isCurrent(bookCreatedAt = now - 5 * minute, now = now)).isFalse()
    }

    @Test
    fun `resultado antigo deixa de aparecer`() {
        val done = CompactStatus.Done(freedBytes = 500, finishedAt = now - 31 * minute)
        assertThat(done.isCurrent(bookCreatedAt = now - 120 * minute, now = now)).isFalse()
    }

    @Test
    fun `resultado de versao anterior sem horario nao aparece`() {
        val done = CompactStatus.Done(freedBytes = 0, finishedAt = 0)
        assertThat(done.isCurrent(bookCreatedAt = now - 120 * minute, now = now)).isFalse()
    }
}

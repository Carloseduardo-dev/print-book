package com.printbook.app.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompactStatusTest {

    private val now = 1_000_000_000L
    private val minute = 60_000L

    @Test
    fun `resultado recente aparece`() {
        assertThat(CompactStatus.Done(freedBytes = 500, finishedAt = now - 2 * minute).isRecent(now)).isTrue()
        assertThat(CompactStatus.Done(freedBytes = 0, finishedAt = now - 30 * minute).isRecent(now)).isTrue()
    }

    @Test
    fun `resultado antigo deixa de aparecer ao reabrir o livro`() {
        assertThat(CompactStatus.Done(freedBytes = 0, finishedAt = now - 31 * minute).isRecent(now)).isFalse()
        assertThat(CompactStatus.Done(freedBytes = 500, finishedAt = now - 3 * 24 * 60 * minute).isRecent(now)).isFalse()
    }

    @Test
    fun `resultado de versao anterior sem horario nao aparece`() {
        assertThat(CompactStatus.Done(freedBytes = 0, finishedAt = 0).isRecent(now)).isFalse()
    }

    @Test
    fun `horario no futuro (relogio ajustado) nao aparece`() {
        assertThat(CompactStatus.Done(freedBytes = 0, finishedAt = now + 5 * minute).isRecent(now)).isFalse()
    }
}

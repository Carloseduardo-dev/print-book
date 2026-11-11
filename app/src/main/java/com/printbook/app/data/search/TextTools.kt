package com.printbook.app.data.search

import java.text.Normalizer
import java.util.Locale

/** Utilitários de texto puros (sem Android), testáveis em JVM. */
object TextTools {

    /**
     * Converte o que o usuário digitou numa expressão FTS4 segura: cada palavra vira um prefixo
     * ("euler" -> euler*), sem operadores nem aspas. Acentos são removidos (o índice também
     * ignora acentos). Retorna null se não sobrar nenhuma palavra útil.
     */
    fun ftsQuery(input: String): String? {
        val normalized = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val words = normalized.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return null
        return words.take(8).joinToString(" ") { "$it*" }
    }

    private val ptWords = setOf(
        "de", "que", "não", "nao", "uma", "para", "com", "os", "as", "do", "da", "dos", "das",
        "em", "no", "na", "por", "mais", "como", "mas", "ao", "se", "é", "são", "sao", "também",
        "isso", "este", "esta", "pelo", "pela", "seu", "sua",
    )
    private val enWords = setOf(
        "the", "and", "of", "to", "is", "in", "that", "it", "for", "with", "as", "on", "be",
        "this", "are", "by", "an", "or", "we", "you", "can", "will", "from", "which", "not",
    )

    enum class Language(val locale: Locale) { PT(Locale("pt", "BR")), EN(Locale.US) }

    /** Heurística por palavras comuns para escolher a voz do TTS. Empate ou vazio = português. */
    fun guessLanguage(text: String): Language {
        var pt = 0
        var en = 0
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+")).forEach { w ->
            if (w in ptWords) pt++
            if (w in enWords) en++
        }
        return if (en > pt * 1.3 && en >= 3) Language.EN else Language.PT
    }

    /** Limpa o texto do OCR para leitura em voz alta (junta hifenização de fim de linha). */
    fun forSpeech(text: String): String =
        text.replace(Regex("-\\n(\\p{L})"), "$1")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
}

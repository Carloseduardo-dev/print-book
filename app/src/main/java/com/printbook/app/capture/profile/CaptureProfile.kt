package com.printbook.app.capture.profile

import com.printbook.app.data.db.entity.SourceApp

/** Retângulo normalizado (0..1) em relação ao quadro capturado. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Perfil de recorte por app de leitura, medido no Galaxy A56 (1080x2340, tela inteira).
 *
 * @param crop recorte fixo aplicado sempre (ex.: rodapé "Localização X de Y" do Kindle)
 * @param chromeCrop recorte extra aplicado só quando as barras do leitor estão visíveis
 * @param chromeProbeY linha (normalizada) usada para detectar se a barra superior está visível
 * @param trimMargins remover margens uniformes. Só para páginas de layout fixo com moldura
 *   (Minha Biblioteca): em texto refluível (Kindle/Play) cortaria cada página num tamanho
 *   diferente e o leitor ampliaria demais as páginas curtas (fim de capítulo).
 */
data class CaptureProfile(
    val sourceApp: SourceApp,
    val crop: NormRect = NormRect.FULL,
    val chromeCrop: NormRect? = null,
    val chromeProbeY: Float = 0.05f,
    val trimMargins: Boolean = false,
) {
    companion object {
        fun forApp(app: SourceApp): CaptureProfile = when (app) {
            // Tela cheia; rodapé "Localização X de Y · NN%" em y≈0.968.
            SourceApp.KINDLE -> CaptureProfile(app, crop = NormRect(0f, 0f, 1f, 0.950f))
            // Rodapé "X páginas restantes · NN%" em y≈0.979.
            SourceApp.PLAY_BOOKS -> CaptureProfile(app, crop = NormRect(0f, 0f, 1f, 0.960f))
            // Barra superior até y≈0.101 e inferior a partir de y≈0.878 quando visíveis;
            // no modo imersivo (um toque esconde as barras) a página ocupa a tela.
            SourceApp.MINHA_BIBLIOTECA -> CaptureProfile(
                app,
                chromeCrop = NormRect(0f, 0.1009f, 1f, 0.8782f),
                trimMargins = true,
            )
            SourceApp.OTHER -> CaptureProfile(app)
        }
    }
}

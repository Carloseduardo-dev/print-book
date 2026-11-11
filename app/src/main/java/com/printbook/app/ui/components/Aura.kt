package com.printbook.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import com.printbook.app.ui.theme.LocalPrintBookTokens
import android.graphics.Matrix
import android.graphics.SweepGradient
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** "Reduzir animações" do sistema (escala de animação 0): a aura fica parada. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Gradiente cônico da marca começando em [angle] graus (equivale a conic-gradient(from angle)). */
private class AuraBrush(private val colors: IntArray, private val angle: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val cx = size.width / 2f
        val cy = size.height / 2f
        return SweepGradient(cx, cy, colors, null).apply {
            setLocalMatrix(Matrix().apply { setRotate(angle, cx, cy) })
        }
    }
}

/**
 * Desenha a borda da aura sobre a área do DrawScope: traço com o gradiente da marca girado em
 * [angle] graus e halo suave (traços largos e translúcidos). [alpha] acende/apaga.
 */
fun DrawScope.drawAuraRing(
    colors: List<Color>,
    angle: Float,
    strokeWidth: Float,
    cornerRadius: Float,
    alpha: Float = 1f,
    glow: Boolean = true,
) {
    if (alpha <= 0f) return
    val argb = (colors + colors.first()).map { it.toArgb() }.toIntArray()
    val brush = AuraBrush(argb, angle)
    val inset = strokeWidth / 2f
    val topLeft = Offset(inset, inset)
    val rectSize = Size(size.width - inset * 2, size.height - inset * 2)
    val radius = CornerRadius(cornerRadius)
    if (glow) {
        for (i in 3 downTo 1) {
            drawRoundRect(
                brush = brush, topLeft = topLeft, size = rectSize, cornerRadius = radius,
                style = Stroke(width = strokeWidth * (1f + i * 1.8f)), alpha = alpha * 0.09f,
            )
        }
    }
    drawRoundRect(brush = brush, topLeft = topLeft, size = rectSize, cornerRadius = radius, style = Stroke(strokeWidth), alpha = alpha)
}

/**
 * Aura em volta do componente. [rotationMillis] = duração de uma volta. Com "reduzir animações"
 * ligado, fica parada.
 */
fun Modifier.auraBorder(
    colors: List<Color>,
    width: Dp = 1.5.dp,
    cornerRadius: Dp = 14.dp,
    rotationMillis: Int = 6_000,
    enabled: Boolean = true,
    glow: Boolean = true,
): Modifier = if (!enabled) this else composed {
    val reduced = rememberReducedMotion()
    val angle = if (reduced || rotationMillis <= 0) {
        40f
    } else {
        rememberInfiniteTransition(label = "aura").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(rotationMillis, easing = LinearEasing), RepeatMode.Restart),
            label = "auraAngle",
        ).value
    }
    drawWithContent {
        drawContent()
        drawAuraRing(colors, angle, width.toPx(), cornerRadius.toPx(), 1f, glow)
    }
}

/** Velocidade padrão do giro da aura em campos em foco (uma volta). */
const val AURA_FOCUS_ROTATION_MS = 3_000

/**
 * Aura girando em volta do componente enquanto ele está em foco (ex.: campo de texto selecionado
 * para digitar). Fora de foco, nada é desenhado.
 */
fun Modifier.auraOnFocus(cornerRadius: Dp = 14.dp, width: Dp = 2.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .auraBorder(
            LocalPrintBookTokens.current.aura,
            width = width,
            cornerRadius = cornerRadius,
            rotationMillis = AURA_FOCUS_ROTATION_MS,
            enabled = focused,
            glow = true,
        )
}

/** Cantos de recorte (motivo visual da captura) nos 4 cantos do componente. */
fun Modifier.cropCorners(color: Color, length: Dp = 10.dp, stroke: Dp = 1.5.dp, outset: Dp = 5.dp): Modifier =
    drawWithContent {
        drawContent()
        val l = length.toPx()
        val s = stroke.toPx()
        val o = outset.toPx()
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * l, y), s)
            drawLine(color, Offset(x, y), Offset(x, y + dy * l), s)
        }
        corner(-o, -o, 1f, 1f)
        corner(w + o, -o, -1f, 1f)
        corner(-o, h + o, 1f, -1f)
        corner(w + o, h + o, -1f, -1f)
    }

package com.printbook.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.printbook.app.ui.theme.PrintBook

/*
 * Componentes de identidade "Aura". Regra de uso: só onde há ação em andamento ou a chamada
 * principal da tela (carregamento, progresso, CTA). Nunca em abas, chips, cards ou textos.
 */

/** Indicador de carregamento: arco com o gradiente da marca girando. */
@Composable
fun AuraSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    stroke: Dp = 2.5.dp,
    colors: List<Color> = PrintBook.tokens.aura,
) {
    val reduced = rememberReducedMotion()
    val angle = if (reduced) 0f else rememberInfiniteTransition(label = "auraSpinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "auraSpinnerAngle",
    ).value
    val loop = colors + colors.first()
    Canvas(modifier.size(size)) {
        val s = stroke.toPx()
        rotate(angle) {
            drawArc(
                brush = Brush.sweepGradient(loop, center = center),
                startAngle = 0f,
                sweepAngle = if (reduced) 360f else 290f,
                useCenter = false,
                topLeft = Offset(s / 2, s / 2),
                size = Size(this.size.width - s, this.size.height - s),
                style = Stroke(width = s, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Barra de progresso fina com preenchimento no gradiente da marca. O gradiente cobre a largura
 * toda, então a cor da ponta "conta" quanto já foi lido.
 */
@Composable
fun AuraProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    colors: List<Color> = PrintBook.tokens.aura,
    trackColor: Color = MaterialTheme.colorScheme.outline,
) {
    val p = progress.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(this.size.height / 2)
        drawRoundRect(color = trackColor, cornerRadius = r)
        if (p > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(colors, startX = 0f, endX = this.size.width),
                size = Size((this.size.width * p).coerceAtLeast(this.size.height), this.size.height),
                cornerRadius = r,
            )
        }
    }
}

/** Botão de chamada principal (CTA) com a borda Aura girando devagar. */
@Composable
fun AuraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        contentPadding = contentPadding,
        modifier = modifier
            .height(52.dp)
            .auraBorder(PrintBook.tokens.aura, width = 2.dp, cornerRadius = 16.dp, rotationMillis = 6_000, enabled = enabled),
        content = content,
    )
}

/** Slider de progresso de leitura: trilho Aura e polegar cobre. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        thumb = {
            Box(
                Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            )
        },
        track = { state ->
            AuraProgressBar(
                progress = (state.value - valueRange.start) / span,
                height = 5.dp,
                trackColor = Color.White.copy(alpha = 0.18f),
            )
        },
    )
}

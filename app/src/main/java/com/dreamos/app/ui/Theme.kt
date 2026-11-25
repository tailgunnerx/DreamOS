package com.dreamos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dark-scale gray + high-contrast neon palette inspired by ListGen & late-90s cyber-locker aesthetics.
 */
object DreamPalette {
    val VoidBlack = Color(0xFF08090D)
    val AbyssGray = Color(0xFF0E1017)
    val SlateDark = Color(0xFF151822)
    val SlateElevated = Color(0xFF1E2230)
    val GraphiteLine = Color(0xFF2B3042)
    val SteelMuted = Color(0xFF6E758C)
    val SilverText = Color(0xFFAEB6CC)
    val PureWhite = Color(0xFFF4F6FC)
    val DangerNeon = Color(0xFFFF3366)

    data class NeonSwatch(val name: String, val primary: Color, val secondary: Color)

    val Neons = listOf(
        NeonSwatch("Cyber Cyan", Color(0xFF00F0FF), Color(0xFF0088FF)),
        NeonSwatch("Hyper Pink", Color(0xFFFF2BD6), Color(0xFFFF006E)),
        NeonSwatch("Acid Lime", Color(0xFFB6FF3B), Color(0xFF00E676)),
        NeonSwatch("Laser Amber", Color(0xFFFFE14D), Color(0xFFFF8800)),
        NeonSwatch("Ultraviolet", Color(0xFFB366FF), Color(0xFF6E3AFF)),
        NeonSwatch("Plasma Coral", Color(0xFFFF5E3A), Color(0xFFFF2A6D)),
    )

    fun swatch(index: Int): NeonSwatch = Neons[index.mod(Neons.size)]
    fun neonColor(index: Int): Color = swatch(index).primary
}

private val DreamColorScheme = darkColorScheme(
    primary = DreamPalette.Neons[0].primary,
    secondary = DreamPalette.Neons[1].primary,
    tertiary = DreamPalette.Neons[2].primary,
    background = DreamPalette.VoidBlack,
    surface = DreamPalette.SlateDark,
    surfaceVariant = DreamPalette.SlateElevated,
    onPrimary = DreamPalette.VoidBlack,
    onSecondary = DreamPalette.VoidBlack,
    onBackground = DreamPalette.PureWhite,
    onSurface = DreamPalette.PureWhite,
    onSurfaceVariant = DreamPalette.SilverText,
    error = DreamPalette.DangerNeon,
)

@Composable
fun DreamOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DreamColorScheme,
        content = content,
    )
}

/** Draws an outer soft neon halo + crisp inner border on a dark-scale gray box. */
fun Modifier.neonGlowBox(
    neon: Color,
    cornerRadius: Dp = 14.dp,
    glowAlpha: Float = 0.22f,
    borderAlpha: Float = 0.78f,
    surfaceColor: Color = DreamPalette.SlateDark,
): Modifier = this
    .drawBehind {
        val r = cornerRadius.toPx()
        // Outer diffused halo rings
        drawRoundRect(
            color = neon.copy(alpha = glowAlpha * 0.35f),
            topLeft = Offset(-5.dp.toPx(), -5.dp.toPx()),
            size = Size(size.width + 10.dp.toPx(), size.height + 10.dp.toPx()),
            cornerRadius = CornerRadius(r + 5.dp.toPx()),
        )
        drawRoundRect(
            color = neon.copy(alpha = glowAlpha * 0.65f),
            topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()),
            size = Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()),
            cornerRadius = CornerRadius(r + 2.dp.toPx()),
        )
    }
    .clip(RoundedCornerShape(cornerRadius))
    .background(
        Brush.verticalGradient(
            listOf(
                surfaceColor,
                DreamPalette.AbyssGray,
            )
        )
    )
    .border(BorderStroke(1.2.dp, neon.copy(alpha = borderAlpha)), RoundedCornerShape(cornerRadius))

@Composable
fun NeonCardBox(
    neon: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    glowAlpha: Float = 0.20f,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = modifier.neonGlowBox(neon = neon, cornerRadius = cornerRadius, glowAlpha = glowAlpha)
    Box(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        content = content,
    )
}

@Composable
fun NeonButton(
    text: String,
    neon: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val activeNeon = if (enabled) neon else neon.copy(alpha = 0.55f)
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                if (filled && enabled) activeNeon.copy(alpha = 0.28f)
                else if (filled) activeNeon.copy(alpha = 0.12f)
                else DreamPalette.SlateElevated.copy(alpha = 0.90f)
            )
            .border(
                width = if (filled) 1.6.dp else 1.2.dp,
                color = activeNeon.copy(alpha = if (enabled) (if (filled) 1f else 0.65f) else 0.42f),
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        leading?.invoke()
        Text(
            text = text,
            color = if (enabled) DreamPalette.PureWhite else DreamPalette.PureWhite.copy(alpha = 0.68f),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
fun NeonChip(
    label: String,
    selected: Boolean,
    neon: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) neon.copy(alpha = 0.22f) else DreamPalette.AbyssGray)
            .border(1.dp, if (selected) neon else DreamPalette.GraphiteLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) neon else DreamPalette.SilverText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun NeonHudIconButton(
    neon: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    padding: PaddingValues = PaddingValues(9.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (active) neon.copy(alpha = 0.22f) else DreamPalette.VoidBlack.copy(alpha = 0.78f))
            .border(1.dp, if (active) neon else DreamPalette.GraphiteLine.copy(alpha = 0.9f), shape)
            .clickable(onClick = onClick)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

// ------------------------------------------------------------------ Custom crisp vector glyphs

@Composable
fun PauseBarsIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val w = size.width * 0.22f
        val h = size.height * 0.68f
        val top = (size.height - h) / 2f
        drawRoundRect(color, Offset(size.width * 0.22f, top), Size(w, h), CornerRadius(3f, 3f))
        drawRoundRect(color, Offset(size.width * 0.56f, top), Size(w, h), CornerRadius(3f, 3f))
    }
}

@Composable
fun GridGalleryIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val g = size.width * 0.36f
        val gap = size.width * 0.12f
        val start = (size.width - g * 2 - gap) / 2f
        val stroke = Stroke(width = 1.6.dp.toPx())
        for (r in 0..1) for (c in 0..1) {
            drawRoundRect(
                color = color,
                topLeft = Offset(start + c * (g + gap), start + r * (g + gap)),
                size = Size(g, g),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = stroke,
            )
        }
    }
}

@Composable
fun LassoGlyph(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val s = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        drawOval(
            color = color,
            topLeft = Offset(size.width * 0.12f, size.height * 0.14f),
            size = Size(size.width * 0.76f, size.height * 0.56f),
            style = s,
        )
        val tail = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.68f)
            quadraticTo(size.width * 0.82f, size.height * 0.88f, size.width * 0.38f, size.height * 0.88f)
        }
        drawPath(tail, color, style = s)
    }
}

@Composable
fun CropGlyph(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val s = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p1 = Path().apply {
            moveTo(size.width * 0.26f, size.height * 0.10f)
            lineTo(size.width * 0.26f, size.height * 0.74f)
            lineTo(size.width * 0.90f, size.height * 0.74f)
        }
        val p2 = Path().apply {
            moveTo(size.width * 0.10f, size.height * 0.26f)
            lineTo(size.width * 0.74f, size.height * 0.26f)
            lineTo(size.width * 0.74f, size.height * 0.90f)
        }
        drawPath(p1, color, style = s)
        drawPath(p2, color, style = s)
    }
}

@Composable
fun SparkleCutoutGlyph(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val rOuter = size.minDimension * 0.44f
        val rInner = size.minDimension * 0.13f
        val path = Path().apply {
            moveTo(cx, cy - rOuter)
            lineTo(cx + rInner, cy - rInner)
            lineTo(cx + rOuter, cy)
            lineTo(cx + rInner, cy + rInner)
            lineTo(cx, cy + rOuter)
            lineTo(cx - rInner, cy + rInner)
            lineTo(cx - rOuter, cy)
            lineTo(cx - rInner, cy - rInner)
            close()
        }
        drawPath(path, color, style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round))
    }
}

@Composable
fun WheelModeGlyph(color: Color, isWheel: Boolean, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        if (isWheel) {
            drawCircle(color, radius = size.minDimension * 0.38f, style = s)
            drawCircle(color, radius = size.minDimension * 0.12f)
        } else {
            val w = size.width * 0.22f
            drawLine(color, Offset(w, size.height * 0.15f), Offset(w, size.height * 0.85f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, Offset(size.width * 0.5f, size.height * 0.08f), Offset(size.width * 0.5f, size.height * 0.92f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, Offset(size.width - w, size.height * 0.22f), Offset(size.width - w, size.height * 0.78f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
fun EyeHideGlyph(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val eye = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.50f)
            quadraticTo(size.width * 0.50f, size.height * 0.14f, size.width * 0.92f, size.height * 0.50f)
            quadraticTo(size.width * 0.50f, size.height * 0.86f, size.width * 0.08f, size.height * 0.50f)
        }
        drawPath(eye, color, style = s)
        drawCircle(color, radius = size.minDimension * 0.13f)
        // Slash to indicate Hide UI
        drawLine(
            color = color,
            start = Offset(size.width * 0.16f, size.height * 0.84f),
            end = Offset(size.width * 0.84f, size.height * 0.16f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

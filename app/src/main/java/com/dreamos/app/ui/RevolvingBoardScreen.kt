package com.dreamos.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dreamos.app.vault.BoardMeta
import com.dreamos.app.vault.BoardSession
import com.dreamos.app.vault.DisplayMode
import com.dreamos.app.vault.ImageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private class HitTarget(
    val entry: ImageEntry,
    val cx: Float,
    val cy: Float,
    val halfW: Float,
    val halfH: Float,
    val tiltDeg: Float,
) {
    fun contains(p: Offset): Boolean {
        val dx = p.x - cx
        val dy = p.y - cy
        val rad = Math.toRadians(-tiltDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = dx * c - dy * s
        val ly = dx * s + dy * c
        if (abs(lx) > halfW || abs(ly) > halfH) return false
        // For transparent cutouts in dense/maximum mode, ignore taps on purely transparent empty corners!
        if (entry.hasAlpha && entry.opaqueMask != -1L && entry.opaqueMask != 0L) {
            val nx = ((lx + halfW) / (halfW * 2f).coerceAtLeast(1f)).coerceIn(0f, 0.999f)
            val ny = ((ly + halfH) / (halfH * 2f).coerceAtLeast(1f)).coerceIn(0f, 0.999f)
            val gx = (nx * 8f).toInt().coerceIn(0, 7)
            val gy = (ny * 8f).toInt().coerceIn(0, 7)
            // Check exact cell + 4-connected neighbors for finger tolerance
            for (oy in -1..1) {
                for (ox in -1..1) {
                    if (abs(ox) + abs(oy) > 1) continue
                    val cx8 = (gx + ox).coerceIn(0, 7)
                    val cy8 = (gy + oy).coerceIn(0, 7)
                    if (((entry.opaqueMask ushr (cy8 * 8 + cx8)) and 1L) != 0L) return true
                }
            }
            return false
        }
        return true
    }
}

private class RenderItem(
    val entry: ImageEntry,
    val bmp: ImageBitmap?,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val tiltDeg: Float,
    val alpha: Float,
    val zOrder: Float,
    val isHero: Boolean,
)

@Composable
fun RevolvingBoardScreen(
    session: BoardSession,
    isOverlayOpen: Boolean = false,
    onLock: () -> Unit,
    onOpenGallery: () -> Unit,
    onAddImages: () -> Unit,
    onImageSelected: (ImageEntry) -> Unit,
    onChangePin: (String?, () -> Unit) -> Unit,
) {
    val meta = session.meta
    val images = meta.images
    val swatch = DreamPalette.swatch(session.info.color)
    val neon = swatch.primary
    val secondaryNeon = swatch.secondary

    // Decrypted thumbnail bitmaps keyed by cacheKey
    val bitmaps = remember(session) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(images) {
        for (entry in images) {
            val cached = session.cachedThumb(entry)
            if (cached != null) {
                bitmaps[entry.cacheKey] = cached
            } else {
                val loaded = withContext(Dispatchers.IO) { session.loadThumb(entry) }
                if (loaded != null) bitmaps[entry.cacheKey] = loaded
            }
        }
    }

    var isFingerDown by remember { mutableStateOf(false) }
    val hudVisible = session.wheelHudVisible ?: !session.meta.startUiHidden
    fun setHudVisible(v: Boolean) {
        session.wheelHudVisible = v
    }
    var showSettings by remember { mutableStateOf(false) }
    var hiddenHintVisible by remember { mutableStateOf(false) }

    // True full-screen immersive bars when HUD is hidden
    val view = LocalView.current
    DisposableEffect(hudVisible, images.isEmpty(), isOverlayOpen) {
        if (!isOverlayOpen) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (!hudVisible && images.isNotEmpty()) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        onDispose {
            if (!isOverlayOpen) {
                val w = (view.context as? Activity)?.window
                if (w != null) {
                    WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    // When HUD is completely hidden, pressing Back brings the HUD back first instead of locking
    BackHandler(enabled = !isOverlayOpen && !hudVisible && images.isNotEmpty()) {
        setHudVisible(true)
    }

    LaunchedEffect(hiddenHintVisible) {
        if (hiddenHintVisible) {
            delay(1800L)
            hiddenHintVisible = false
        }
    }

    var flingAngularVel by remember { mutableFloatStateOf(0f) } // rad/sec

    val baseSpeedRadPerSec = 0.44f * meta.speedMultiplier

    // Smooth 60/120fps physics loop (freezes in place while Fullscreen viewer is open!)
    LaunchedEffect(session.wheelAutoRotating, isFingerDown, isOverlayOpen, baseSpeedRadPerSec, images.isEmpty()) {
        if (images.isEmpty() || isOverlayOpen) return@LaunchedEffect
        var prevNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - prevNanos) / 1_000_000_000f).coerceIn(0f, 0.06f)
            prevNanos = now

            if (!isFingerDown && !isOverlayOpen) {
                val cruise = if (session.wheelAutoRotating) baseSpeedRadPerSec else 0f
                val omega = cruise + flingAngularVel
                session.wheelPhase += omega * dt
                flingAngularVel *= exp(-dt * 2.5f)
                if (abs(flingAngularVel) < 0.012f) flingAngularVel = 0f
            }
        }
    }

    val hitTargetsRef = remember { ArrayList<HitTarget>(48) }
    val packCache = remember(session) { LinkedHashMap<PackCacheKey, List<PackedBeltSlot>>(16, 0.75f, true) }
    val currentOnSelect by rememberUpdatedState(onImageSelected)

    val wallBaseColor = if (meta.showWallBackground) DreamPalette.VoidBlack else Color.Black

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(wallBaseColor)
    ) {
        // Optional ambient radial neon aura behind the wheel (can be turned completely off in Settings!)
        if (meta.showWallBackground) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            neon.copy(alpha = 0.13f),
                            secondaryNeon.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = size.maxDimension * 0.62f,
                    ),
                    radius = size.maxDimension * 0.62f,
                    center = center,
                )
            }
        }

        // Subtle multi-phrase background layer (fixed or random timing, custom per-phrase color & low opacity)
        val activePhrases = remember(meta.phrases, meta.mantra) {
            if (meta.phrases.isNotEmpty()) meta.phrases
            else if (meta.mantra.isNotBlank()) listOf(com.dreamos.app.vault.PhraseItem("legacy", meta.mantra, 0))
            else emptyList()
        }
        if (activePhrases.isNotEmpty()) {
            var phraseIdx by remember(activePhrases.size) { mutableIntStateOf(0) }
            var phraseTick by remember { mutableIntStateOf(1) }

            val needsCycling = activePhrases.size > 1 || activePhrases.first().colorArgb == PHRASE_COLOR_RANDOM
            LaunchedEffect(
                activePhrases,
                meta.phraseRandomTiming,
                meta.phraseIntervalSec,
                meta.phraseRandomMinSec,
                meta.phraseRandomMaxSec,
                meta.phraseShuffleOrder,
            ) {
                if (!needsCycling) return@LaunchedEffect
                while (true) {
                    val waitMs = if (meta.phraseRandomTiming) {
                        val minMs = (meta.phraseRandomMinSec.coerceAtLeast(2) * 1000L)
                        val maxMs = (meta.phraseRandomMaxSec.coerceAtLeast(meta.phraseRandomMinSec + 1) * 1000L)
                        kotlin.random.Random.nextLong(minMs, maxMs + 1L)
                    } else {
                        meta.phraseIntervalSec.coerceAtLeast(2) * 1000L
                    }
                    delay(waitMs)
                    if (activePhrases.size > 1) {
                        phraseIdx = if (meta.phraseShuffleOrder && activePhrases.size > 2) {
                            var candidate = kotlin.random.Random.nextInt(activePhrases.size - 1)
                            if (candidate >= phraseIdx) candidate += 1
                            candidate
                        } else {
                            (phraseIdx + 1) % activePhrases.size
                        }
                    }
                    phraseTick += 1
                }
            }

            Crossfade(
                targetState = (phraseIdx.coerceIn(0, activePhrases.lastIndex)) to phraseTick,
                animationSpec = tween(durationMillis = 850),
                label = "BackgroundPhraseCrossfade",
                modifier = Modifier.fillMaxSize(),
            ) { (idx, tick) ->
                val item = activePhrases.getOrNull(idx) ?: activePhrases.first()
                val baseCol = resolvePhraseBaseColor(item.colorArgb, neon, randomSeed = tick * 31 + idx)
                val len = item.text.length
                val fontSz = when {
                    len <= 24 -> 38.sp
                    len <= 56 -> 29.sp
                    len <= 100 -> 23.sp
                    else -> 19.sp
                }
                val lineHt = when {
                    len <= 24 -> 43.sp
                    len <= 56 -> 34.sp
                    len <= 100 -> 28.sp
                    else -> 24.sp
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 26.dp, vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.text.uppercase(),
                        color = baseCol.copy(alpha = meta.phraseOpacity.coerceIn(0.04f, 0.55f)),
                        fontSize = fontSz,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        lineHeight = lineHt,
                    )
                }
            }
        }

        if (images.isEmpty()) {
            EmptyShrinePrompt(
                neon = neon,
                boardName = session.info.name,
                onAddImages = onAddImages,
                onOpenSettings = { showSettings = true },
            )
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(images.size) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            isFingerDown = true
                            flingAngularVel = 0f
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            val startPos = down.position
                            var totalDrag = 0f
                            var dragged = false
                            val isHorizScreen = size.width > size.height
                            val radPerPx = (if (isHorizScreen) 1.55f else 2.9f) / size.height.coerceAtLeast(1).toFloat()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change: PointerInputChange =
                                    event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    break
                                }
                                val delta = change.positionChange()
                                totalDrag += hypot(delta.x, delta.y)
                                if (totalDrag > slop) dragged = true
                                if (dragged) {
                                    val primaryDelta = delta.y + delta.x * (if (isHorizScreen) 0.45f else 0.22f)
                                    session.wheelPhase += primaryDelta * radPerPx
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                }
                            }

                            isFingerDown = false
                            if (dragged) {
                                val v = velocityTracker.calculateVelocity()
                                val vy = (v.y + v.x * (if (isHorizScreen) 0.45f else 0.22f)).coerceIn(-12000f, 12000f)
                                flingAngularVel = vy * radPerPx
                            } else {
                                val tapped = hitTargetsRef.lastOrNull { it.contains(startPos) }
                                if (tapped != null) {
                                    session.bumpHeat(tapped.entry.id)
                                    currentOnSelect(tapped.entry)
                                } else {
                                    setHudVisible(!hudVisible)
                                }
                            }
                        }
                    }
            ) {
                drawRevolvingCollage(
                    images = images,
                    bitmaps = bitmaps,
                    meta = meta,
                    phase = session.wheelPhase,
                    seed = session.wheelSeed,
                    neon = neon,
                    secondaryNeon = secondaryNeon,
                    packCache = packCache,
                    hitTargetsOut = hitTargetsRef,
                )
            }
        }

        // Brief non-intrusive toast when user hides all UI so they know how to restore it
        AnimatedVisibility(
            visible = hiddenHintVisible && !hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(DreamPalette.VoidBlack.copy(alpha = 0.78f))
                    .border(1.dp, neon.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "UI HIDDEN • TAP WALL OR BACK TO SHOW",
                    color = DreamPalette.SilverText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Top Floating Neon HUD
        AnimatedVisibility(
            visible = hudVisible || images.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DreamPalette.VoidBlack.copy(alpha = 0.78f))
                    .border(1.dp, neon.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 9.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NeonHudIconButton(neon = neon, padding = PaddingValues(8.dp), onClick = onLock) {
                    Icon(Icons.Default.Lock, contentDescription = "Lock Shrine", tint = neon, modifier = Modifier.size(16.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.info.name,
                        color = DreamPalette.PureWhite,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isFingerDown) "HOLDING"
                        else if (!session.wheelAutoRotating) "PAUSED"
                        else buildString {
                            if (meta.maximumMode) append("MAX • ")
                            append(meta.mode.name)
                            append(" • ")
                            append(String.format(Locale.US, "%.2fx", meta.speedMultiplier))
                        },
                        color = neon.copy(alpha = 0.85f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                if (images.isNotEmpty()) {
                    // Explicit Hide All UI button
                    NeonHudIconButton(
                        neon = neon,
                        padding = PaddingValues(8.dp),
                        onClick = {
                            setHudVisible(false)
                            hiddenHintVisible = true
                        },
                    ) {
                        EyeHideGlyph(color = DreamPalette.SilverText, modifier = Modifier.size(16.dp))
                    }
                }

                // Settings button
                NeonHudIconButton(
                    neon = neon,
                    padding = PaddingValues(8.dp),
                    onClick = { showSettings = true },
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DreamPalette.PureWhite, modifier = Modifier.size(16.dp))
                }

                // Backend Gallery button
                NeonHudIconButton(
                    neon = neon,
                    padding = PaddingValues(horizontal = 9.dp, vertical = 8.dp),
                    onClick = onOpenGallery,
                ) {
                    GridGalleryIcon(color = DreamPalette.PureWhite, modifier = Modifier.size(15.dp))
                }

                NeonHudIconButton(neon = neon, active = true, padding = PaddingValues(8.dp), onClick = onAddImages) {
                    Icon(Icons.Default.Add, contentDescription = "Add Images", tint = neon, modifier = Modifier.size(17.dp))
                }
            }
        }

        // Bottom Floating Controls (Pause/Play, Mode, Granular Speed Quick-Step, Spin)
        AnimatedVisibility(
            visible = hudVisible && images.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(DreamPalette.VoidBlack.copy(alpha = 0.80f))
                    .border(1.dp, neon.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                NeonHudIconButton(
                    neon = neon,
                    active = session.wheelAutoRotating,
                    padding = PaddingValues(horizontal = 11.dp, vertical = 8.dp),
                    onClick = { session.wheelAutoRotating = !session.wheelAutoRotating },
                ) {
                    if (session.wheelAutoRotating) {
                        PauseBarsIcon(color = neon, modifier = Modifier.size(15.dp))
                        Text("PAUSE", color = neon, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = DreamPalette.PureWhite, modifier = Modifier.size(16.dp))
                        Text("PLAY", color = DreamPalette.PureWhite, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }

                NeonHudIconButton(
                    neon = neon,
                    padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    onClick = {
                        val next = if (meta.mode == DisplayMode.WHEEL) DisplayMode.WATERFALL else DisplayMode.WHEEL
                        session.setMode(next)
                    },
                ) {
                    WheelModeGlyph(
                        color = DreamPalette.SilverText,
                        isWheel = meta.mode == DisplayMode.WHEEL,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = if (meta.mode == DisplayMode.WHEEL) "WHEEL" else "FALL",
                        color = DreamPalette.PureWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                // 1-Tap MAXIMUM MODE toggle (Sweet-Spot Safe high-density reference wall)
                NeonHudIconButton(
                    neon = neon,
                    active = meta.maximumMode,
                    padding = PaddingValues(horizontal = 9.dp, vertical = 8.dp),
                    onClick = { session.setMaximumMode(!meta.maximumMode) },
                ) {
                    Text(
                        text = "MAX",
                        color = if (meta.maximumMode) neon else DreamPalette.SilverText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                // Granular inline speed − / value / + (up to 12.0x; tapping value opens Settings)
                NeonHudIconButton(
                    neon = neon,
                    padding = PaddingValues(horizontal = 7.dp, vertical = 8.dp),
                    onClick = {
                        val step = when {
                            meta.speedMultiplier > 5f -> 0.50f
                            meta.speedMultiplier > 2f -> 0.25f
                            else -> 0.15f
                        }
                        session.setSpeedMultiplier((meta.speedMultiplier - step).coerceAtLeast(0.05f))
                    },
                ) {
                    Text("−", color = DreamPalette.SilverText, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                NeonHudIconButton(
                    neon = neon,
                    padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    onClick = { showSettings = true },
                ) {
                    Text(
                        text = if (meta.speedMultiplier >= 10f) String.format(Locale.US, "%.1fx", meta.speedMultiplier)
                        else String.format(Locale.US, "%.2fx", meta.speedMultiplier),
                        color = neon,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                NeonHudIconButton(
                    neon = neon,
                    padding = PaddingValues(horizontal = 7.dp, vertical = 8.dp),
                    onClick = {
                        val step = when {
                            meta.speedMultiplier >= 5f -> 0.50f
                            meta.speedMultiplier >= 2f -> 0.25f
                            else -> 0.15f
                        }
                        session.setSpeedMultiplier((meta.speedMultiplier + step).coerceAtMost(12.0f))
                    },
                ) {
                    Text("+", color = DreamPalette.SilverText, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                NeonHudIconButton(
                    neon = neon,
                    padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    onClick = {
                        flingAngularVel += 3.4f
                        session.wheelSeed = (session.wheelSeed * 1664525 + 1013904223)
                    },
                ) {
                    Text(
                        text = "SPIN",
                        color = DreamPalette.SilverText,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    if (showSettings) {
        VaultSettingsDialog(
            session = session,
            onDismiss = { showSettings = false },
            onChangePin = onChangePin,
        )
    }
}

@Composable
private fun EmptyShrinePrompt(
    neon: Color,
    boardName: String,
    onAddImages: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        NeonCardBox(neon = neon, glowAlpha = 0.28f, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = boardName.uppercase(),
                    color = neon,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.4.sp,
                )
                Text(
                    text = "Feed Your Revolving Poster Board",
                    color = DreamPalette.PureWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Drop photos into this shrine. Use the Gallery to isolate subjects as borderless transparent PNGs and spawn zoom/crop variations.",
                    color = DreamPalette.SilverText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = "SETTINGS",
                        neon = DreamPalette.SilverText,
                        onClick = onOpenSettings,
                    )
                    NeonButton(
                        text = "+ IMPORT IMAGES",
                        neon = neon,
                        filled = true,
                        onClick = onAddImages,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Deterministic per-revolution mixer

private fun mixHash(a: Int, b: Int, c: Int): Int {
    var h = a * -1640531527 xor (b * 1597334677) xor c
    h = (h xor (h ushr 16)) * -2048144789
    h = (h xor (h ushr 13)) * -1028477387
    return h xor (h ushr 16)
}

private fun hashFloat01(h: Int): Float = ((h ushr 8) and 0xFFFFFF).toFloat() / 16777215f

private fun pickImageIndex(slot: Int, rev: Int, count: Int, seed: Int): Int {
    if (count <= 1) return 0
    val rawStride = 1 + (abs(mixHash(rev, seed, 0x517CC1B7)) % (count - 1).coerceAtLeast(1))
    val stride = coprimeTo(rawStride, count)
    val offset = abs(mixHash(rev, seed, 0x27D4EB2F)) % count
    return ((slot * stride + offset) % count + count) % count
}

private fun gcd(a: Int, b: Int): Int {
    var x = abs(a)
    var y = abs(b)
    while (y != 0) {
        val t = x % y
        x = y
        y = t
    }
    return x
}

private fun coprimeTo(start: Int, n: Int): Int {
    var s = start.coerceAtLeast(1)
    if (n >= 5) {
        // Prefer strides in [2 .. n-2] so consecutive slots jump across the gallery
        // rather than stepping +1/-1 into adjacent variations of the same source photo.
        for (attempt in 0 until n) {
            val cand = 2 + ((s - 2 + attempt) % (n - 3)).let { if (it < 0) it + (n - 3) else it }
            if (gcd(cand, n) == 1) return cand
        }
    }
    while (gcd(s, n) != 1) {
        s = (s % (n - 1).coerceAtLeast(1)) + 1
    }
    return s
}

/**
 * Generates a deterministic per-revolution sequence of image indices where consecutive
 * slots (including the wrap-around from the last slot back to slot 0) never repeat the
 * same photo or, whenever possible, sibling variations sharing the same `sourceId`.
 */
private fun buildDeclusteredSlotSequence(
    slotCount: Int,
    rev: Int,
    images: List<ImageEntry>,
    seed: Int,
): IntArray {
    val n = images.size
    if (n <= 1) return IntArray(slotCount) { 0 }
    val uniqueSources = images.map { it.sourceId }.toSet().size

    // Group indices by sourceId and interleave them round-robin so sibling variations
    // are maximally separated around the base ring before striding.
    val bySource = LinkedHashMap<String, MutableList<Int>>()
    for (i in images.indices) {
        bySource.getOrPut(images[i].sourceId) { ArrayList() }.add(i)
    }
    val groups = bySource.values.toList().sortedBy { grp ->
        mixHash(grp.first(), rev, seed xor 0x3C6EF372)
    }
    val ring = IntArray(n)
    var writePos = 0
    var depth = 0
    while (writePos < n) {
        for (g in groups) {
            if (depth < g.size) {
                ring[writePos++] = g[depth]
            }
        }
        depth++
    }

    val seq = IntArray(slotCount)
    val usage = IntArray(n)
    for (slot in 0 until slotCount) {
        val baseRingIdx = pickImageIndex(slot, rev, n, seed)
        val prevImg = if (slot > 0) seq[slot - 1] else -1
        val prev2Img = if (slot > 1) seq[slot - 2] else -1
        val wrapFirstImg = if (slot == slotCount - 1 && slotCount > 1) seq[0] else -1

        var bestImg = ring[baseRingIdx]
        var bestScore = Float.NEGATIVE_INFINITY

        for (step in 0 until n) {
            val candImg = ring[(baseRingIdx + step) % n]
            val candSrc = images[candImg].sourceId
            var sc = -usage[candImg] * 40f - step * 1.5f

            if (prevImg >= 0) {
                if (candImg == prevImg) sc -= 100_000f
                else if (uniqueSources > 1 && candSrc == images[prevImg].sourceId) sc -= 25_000f
            }
            if (prev2Img >= 0 && n >= 3) {
                if (candImg == prev2Img) sc -= 12_000f
                else if (uniqueSources > 2 && candSrc == images[prev2Img].sourceId) sc -= 4_000f
            }
            if (wrapFirstImg >= 0) {
                if (candImg == wrapFirstImg) sc -= 80_000f
                else if (uniqueSources > 1 && candSrc == images[wrapFirstImg].sourceId) sc -= 20_000f
            }

            if (sc > bestScore) {
                bestScore = sc
                bestImg = candImg
                if (sc >= -usage[candImg] * 40f && step == 0) break
            }
        }
        seq[slot] = bestImg
        usage[bestImg]++
    }
    return seq
}

/**
 * 5 horizontal lane centers ordered so consecutive slots ALWAYS jump to an opposite/distant
 * side of the screen (Left -> Right -> Center -> Far-Left -> Far-Right), preventing vertical
 * neighbors from stacking or covering one another!
 */
private val StaggeredLanes = floatArrayOf(0.24f, 0.76f, 0.50f, 0.21f, 0.79f)

/**
 * Stratified size buckets blended with per-revolution randomness so any single screen view
 * simultaneously showcases small, medium, and large hero subjects across [minSize..maxSize].
 */
private val SizeStratification = floatArrayOf(0.15f, 0.85f, 0.45f, 0.96f, 0.30f, 0.68f)

private fun computeDynamicSize(
    slot: Int,
    rev: Int,
    seed: Int,
    @Suppress("UNUSED_PARAMETER") entry: ImageEntry,
    minSize: Float,
    maxSize: Float,
): Float {
    val span = (maxSize - minSize).coerceAtLeast(0.05f)
    val strata = SizeStratification[((slot + abs(rev) * 3) % SizeStratification.size)]
    val jitter = (hashFloat01(mixHash(slot, rev, seed xor 0x2468ACE0)) - 0.5f) * 0.36f
    val t = (strata + jitter).coerceIn(0f, 1f)
    return minSize + span * t
}

private data class PackCacheKey(
    val rev: Int,
    val seed: Int,
    val wBucket: Int,
    val hBucket: Int,
    val maxMode: Boolean,
    val minSizeBits: Int,
    val maxSizeBits: Int,
    val spacingBits: Int,
    val imagesSig: Int,
)

private class PackedBeltSlot(
    val imgIdx: Int,
    val xNorm: Float,
    val beltU: Float,
    val sizeMult: Float,
    val tiltDeg: Float,
    val zOrder: Float,
    val isHero: Boolean,
)

private fun effectiveSweetMask(entry: ImageEntry): Long {
    if (entry.sweetMask != 0L) return entry.sweetMask
    if (entry.edits.sweetConfigured) return 0L
    return autoFocalSweetMask(entry.opaqueMask)
}

private fun autoFocalSweetMask(opaqueMask: Long): Long {
    if (opaqueMask == 0L) return 0L
    var minX = 8; var maxX = -1; var minY = 8; var maxY = -1
    for (gy in 0 until 8) {
        for (gx in 0 until 8) {
            if (((opaqueMask ushr (gy * 8 + gx)) and 1L) != 0L) {
                if (gx < minX) minX = gx
                if (gx > maxX) maxX = gx
                if (gy < minY) minY = gy
                if (gy > maxY) maxY = gy
            }
        }
    }
    if (maxX < minX || maxY < minY) return 0L
    val spanX = maxX - minX + 1
    val spanY = maxY - minY + 1
    val sx0 = (minX + (spanX * 0.25f).toInt()).coerceIn(minX, maxX)
    val sx1 = (maxX - (spanX * 0.25f).toInt()).coerceAtLeast(sx0).coerceIn(minX, maxX)
    val sy0 = (minY + (spanY * 0.14f).toInt()).coerceIn(minY, maxY)
    val sy1 = (minY + (spanY * 0.52f).toInt()).coerceAtLeast(sy0).coerceIn(minY, maxY)
    var sweet = 0L
    for (gy in sy0..sy1) {
        for (gx in sx0..sx1) {
            val bit = 1L shl (gy * 8 + gx)
            if ((opaqueMask and bit) != 0L) sweet = sweet or bit
        }
    }
    return sweet
}

private fun computeImagesSignature(images: List<ImageEntry>): Int {
    var h = images.size
    for (img in images) {
        h = h * 31 + img.id.hashCode()
        h = h * 31 + img.version
        h = h * 31 + img.sweetMask.hashCode()
        h = h * 31 + img.opaqueMask.hashCode()
    }
    return h
}

/**
 * Compensates for the reduced opaque pixel area of irregular/jagged cutouts so they
 * command the same visual presence on the wall as full rectangular photos.
 */
private fun cutoutVisualBoost(entry: ImageEntry): Float {
    if (!entry.hasAlpha) return 1.0f
    val bits = entry.opaqueMask.countOneBits().coerceIn(8, 64)
    val fillRatio = bits / 64f
    return (1f / sqrt(fillRatio)).coerceIn(1.0f, 1.32f)
}

/**
 * Front-to-Back Silhouette + Notch-Nestling + Sweet-Spot Collage Packer.
 * Places items from frontmost layer to backmost layer on a periodic belt grid (GW x GH),
 * interlocking transparent cutout notches, sealing seams between jagged edges, and
 * automatically plugging any remaining black holes from behind while protecting Sweet Spots.
 */
private fun packRevolutionBelt(
    rev: Int,
    seed: Int,
    images: List<ImageEntry>,
    meta: BoardMeta,
    w: Float,
    beltPx: Float,
    baseCardSide: Float,
    slotCount: Int,
): List<PackedBeltSlot> {
    val gw = 56
    val gh = 84
    val coveredByFront = BooleanArray(gw * gh)
    val isLandscape = w > beltPx * 0.72f
    val hasCutouts = images.any { it.hasAlpha }

    data class DraftSlot(
        val slot: Int,
        val imgIdx: Int,
        val entry: ImageEntry,
        val rawSize: Float,
        val frontPriority: Float,
    )

    val effMin = if (meta.maximumMode) (meta.minSize * 0.80f).coerceIn(0.28f, 0.95f) else meta.minSize
    val effMax = if (meta.maximumMode) (meta.maxSize * 0.88f).coerceIn(effMin + 0.12f, 1.42f) else meta.maxSize

    val slotSeq = buildDeclusteredSlotSequence(slotCount, rev, images, seed)
    val drafts = ArrayList<DraftSlot>(slotCount)
    for (slot in 0 until slotCount) {
        val imgIdx = slotSeq[slot]
        val entry = images[imgIdx]
        val sz = computeDynamicSize(slot, rev, seed, entry, effMin, effMax) * cutoutVisualBoost(entry)
        val prio = (if (entry.hasCustomSweetSpot) 0.34f else 0f) +
            (if (entry.hasAlpha) 0.22f else 0f) +
            (if (entry.isVariation || entry.edits.crop != null) 0.18f else 0f) +
            hashFloat01(mixHash(slot, rev, seed xor 0x6C8E9CF5)) * 0.65f -
            sz * 0.18f
        drafts.add(DraftSlot(slot, imgIdx, entry, sz, prio))
    }
    drafts.sortByDescending { it.frontPriority }

    val xMin = if (meta.maximumMode || isLandscape || hasCutouts) 0.10f else 0.15f
    val xMax = if (meta.maximumMode || isLandscape || hasCutouts) 0.90f else 0.85f
    val heroThreshold = effMin + (effMax - effMin) * 0.70f

    val result = ArrayList<PackedBeltSlot>(slotCount + 20)
    val uniqueIds = images.size
    val uniqueSources = images.map { it.sourceId }.toSet().size
    val minSameDistPx = max(w * 0.78f, baseCardSide * 2.15f)
    val minSiblingDistPx = max(w * 0.56f, baseCardSide * 1.55f)

    fun proximityPenalty(cxNorm: Float, beltU: Float, imgIdx: Int, sourceId: String): Float {
        if (uniqueIds <= 1 || result.isEmpty()) return 0f
        var pen = 0f
        for (placed in result) {
            val sameId = placed.imgIdx == imgIdx
            val sameSource = !sameId && uniqueSources > 1 && images[placed.imgIdx].sourceId == sourceId
            if (!sameId && !sameSource) continue
            val dxPx = (cxNorm - placed.xNorm) * w
            val rawDu = abs(beltU - placed.beltU)
            val duNorm = min(rawDu, 1f - rawDu) // toroidal belt distance across seam!
            val dyPx = duNorm * beltPx
            val distPx = hypot(dxPx, dyPx)
            if (sameId && distPx < minSameDistPx) {
                val t = 1f - (distPx / minSameDistPx)
                pen += (32_000f + t * t * 72_000f)
            } else if (sameSource && distPx < minSiblingDistPx) {
                val t = 1f - (distPx / minSiblingDistPx)
                pen += (12_000f + t * t * 28_000f)
            }
        }
        return pen
    }

    fun hasCoveredNeighbor(gx: Int, gy: Int): Boolean {
        val left = (gx - 1).coerceAtLeast(0)
        val right = (gx + 1).coerceAtMost(gw - 1)
        val up = (gy - 1 + gh) % gh
        val down = (gy + 1) % gh
        return coveredByFront[gy * gw + left] ||
            coveredByFront[gy * gw + right] ||
            coveredByFront[up * gw + gx] ||
            coveredByFront[down * gw + gx]
    }

    fun scoreCandidate(
        cxNorm: Float,
        beltU: Float,
        itemW: Float,
        itemH: Float,
        opaqueBits: Long,
        sweetBits: Long,
        sweetPenaltyWeight: Float,
    ): Pair<Float, Int> {
        val leftNorm = (cxNorm - (itemW * 0.5f) / w)
        val rightNorm = (cxNorm + (itemW * 0.5f) / w)
        val halfUNorm = (itemH * 0.5f) / beltPx

        val gx0 = (leftNorm * gw).toInt().coerceIn(0, gw - 1)
        val gx1 = (rightNorm * gw).toInt().coerceIn(gx0, gw - 1)
        val rawGy0 = floor(((beltU - halfUNorm) * gh).toDouble()).toInt()
        val rawGy1 = floor(((beltU + halfUNorm) * gh).toDouble()).toInt().coerceAtLeast(rawGy0)

        val clipPenalty = (max(0f, 0.01f - leftNorm) + max(0f, rightNorm - 0.99f)) * 420f

        val spanGx = (rightNorm - leftNorm).coerceAtLeast(0.001f) * gw
        val spanGy = ((halfUNorm * 2f).coerceAtLeast(0.001f)) * gh
        val originGx = leftNorm * gw
        val originGy = (beltU - halfUNorm) * gh

        var sweetCovered = 0
        var visibleOpaque = 0
        var hiddenOpaque = 0
        var notchInterlocks = 0
        var seamTouches = 0

        for (ry in rawGy0..rawGy1) {
            val gy = ((ry % gh) + gh) % gh
            val ly8 = (((ry + 0.5f - originGy) / spanGy) * 8f).toInt().coerceIn(0, 7)
            val rowOff = gy * gw
            for (gx in gx0..gx1) {
                val lx8 = (((gx + 0.5f - originGx) / spanGx) * 8f).toInt().coerceIn(0, 7)
                val bit = 1L shl (ly8 * 8 + lx8)
                val isOpaque = (opaqueBits and bit) != 0L
                val isSweet = (sweetBits and bit) != 0L
                val covered = coveredByFront[rowOff + gx]

                if (isSweet && covered) sweetCovered++
                if (isOpaque) {
                    if (covered) {
                        hiddenOpaque++
                    } else {
                        visibleOpaque++
                        if (hasCoveredNeighbor(gx, gy)) seamTouches++
                    }
                } else if (covered) {
                    // Transparent cutout notch interlocking over an already-placed subject!
                    notchInterlocks++
                }
            }
        }

        val overlapPenalty = if (meta.maximumMode || hasCutouts) 2.2f else 3.8f
        val score = visibleOpaque * 13.5f +
            notchInterlocks * 5.2f +
            seamTouches * 6.8f -
            hiddenOpaque * overlapPenalty -
            sweetCovered * sweetPenaltyWeight -
            clipPenalty
        return score to sweetCovered
    }

    fun stampOpaque(cxNorm: Float, beltU: Float, itemW: Float, itemH: Float, opaqueBits: Long) {
        val leftNorm = cxNorm - (itemW * 0.5f) / w
        val rightNorm = cxNorm + (itemW * 0.5f) / w
        val halfUNorm = (itemH * 0.5f) / beltPx
        val gx0 = (leftNorm * gw).toInt().coerceIn(0, gw - 1)
        val gx1 = (rightNorm * gw).toInt().coerceIn(gx0, gw - 1)
        val rawGy0 = floor(((beltU - halfUNorm) * gh).toDouble()).toInt()
        val rawGy1 = floor(((beltU + halfUNorm) * gh).toDouble()).toInt().coerceAtLeast(rawGy0)
        val spanGx = (rightNorm - leftNorm).coerceAtLeast(0.001f) * gw
        val spanGy = ((halfUNorm * 2f).coerceAtLeast(0.001f)) * gh
        val originGx = leftNorm * gw
        val originGy = (beltU - halfUNorm) * gh

        for (ry in rawGy0..rawGy1) {
            val gy = ((ry % gh) + gh) % gh
            val ly8 = (((ry + 0.5f - originGy) / spanGy) * 8f).toInt().coerceIn(0, 7)
            val rowOff = gy * gw
            for (gx in gx0..gx1) {
                val lx8 = (((gx + 0.5f - originGx) / spanGx) * 8f).toInt().coerceIn(0, 7)
                val bit = 1L shl (ly8 * 8 + lx8)
                if ((opaqueBits and bit) != 0L) {
                    coveredByFront[rowOff + gx] = true
                }
            }
        }
    }

    val candidateCount = if (meta.maximumMode || hasCutouts) 32 else 18

    for ((rank, d) in drafts.withIndex()) {
        val entry = d.entry
        val opaqueBits = entry.opaqueMask
        val sweetBits = effectiveSweetMask(entry)
        val sweetWeight = if (entry.hasCustomSweetSpot) 50_000f else 15_000f

        var bestX = 0.5f
        var bestU = (d.slot.toFloat() / slotCount.toFloat())
        var bestSize = d.rawSize
        var bestScore = Float.NEGATIVE_INFINITY
        var bestSweetHits = Int.MAX_VALUE

        val dimsFull = fitAspect(entry.aspect, baseCardSide * d.rawSize)

        for (c in 0 until candidateCount) {
            val candX: Float
            val candU: Float
            if (c == 0) {
                val baseLane = ((d.slot * 0.61803395f + abs(rev) * 0.29f) % 1f)
                candX = xMin + baseLane * (xMax - xMin)
                candU = ((d.slot + 0.5f) / slotCount.toFloat() + hashFloat01(mixHash(d.slot, rev, seed)) * 0.08f) % 1f
            } else {
                val hc1 = mixHash(d.slot * 37 + c, rev, seed xor 0x1B873593)
                val hc2 = mixHash(d.slot * 53 + c, rev, seed xor 0x85EBCA6B.toInt())
                candX = xMin + hashFloat01(hc1) * (xMax - xMin)
                candU = hashFloat01(hc2)
            }
            val normU = ((candU % 1f) + 1f) % 1f
            val (rawSc, sh) = scoreCandidate(candX, normU, dimsFull.width, dimsFull.height, opaqueBits, sweetBits, sweetWeight)
            val sc = rawSc - proximityPenalty(candX, normU, d.imgIdx, entry.sourceId)
            if (sc > bestScore) {
                bestScore = sc
                bestSweetHits = sh
                bestX = candX
                bestU = normU
                bestSize = d.rawSize
            }
        }

        if (bestSweetHits > 0 && sweetBits != 0L) {
            val compactMult = (d.rawSize * 0.78f).coerceAtLeast(effMin * 0.85f)
            val dimsCompact = fitAspect(entry.aspect, baseCardSide * compactMult)
            for (c in 0 until 14) {
                val hc1 = mixHash(d.slot * 71 + c, rev, seed xor 0xC2B2AE35.toInt())
                val hc2 = mixHash(d.slot * 89 + c, rev, seed xor 0x27D4EB2F)
                val candX = xMin + hashFloat01(hc1) * (xMax - xMin)
                val candU = hashFloat01(hc2)
                val (rawSc, sh) = scoreCandidate(candX, candU, dimsCompact.width, dimsCompact.height, opaqueBits, sweetBits, sweetWeight)
                val sc = rawSc - proximityPenalty(candX, candU, d.imgIdx, entry.sourceId)
                if (sh < bestSweetHits || (sh == bestSweetHits && sc > bestScore)) {
                    bestScore = sc
                    bestSweetHits = sh
                    bestX = candX
                    bestU = candU
                    bestSize = compactMult
                }
            }
        }

        val finalDims = fitAspect(entry.aspect, baseCardSide * bestSize)
        stampOpaque(bestX, bestU, finalDims.width, finalDims.height, opaqueBits)

        val hTilt = mixHash(d.slot, rev, seed xor 0x7F4A7C15)
        val tiltDeg = (hashFloat01(hTilt) - 0.5f) * 2f * meta.tiltDegrees
        val zOrder = (slotCount - rank).toFloat()

        result.add(
            PackedBeltSlot(
                imgIdx = d.imgIdx,
                xNorm = bestX,
                beltU = bestU,
                sizeMult = bestSize,
                tiltDeg = tiltDeg,
                zOrder = zOrder,
                isHero = bestSize >= heroThreshold,
            )
        )
    }

    // Automatic Black-Hole Sealer Pass:
    // Scans the belt grid for any uncovered pockets left between irregular cutouts and nestles
    // additional subjects right into those gaps from behind (keeping their Sweet Spots visible!).
    if (meta.maximumMode || hasCutouts || meta.spacing <= 1.28f) {
        val gapBudget = when {
            meta.maximumMode && hasCutouts -> 18
            meta.maximumMode -> 12
            hasCutouts -> 14
            else -> 6
        }

        data class HoleWindow(val cxNorm: Float, val beltU: Float, val emptyCells: Int)
        val holes = ArrayList<HoleWindow>()
        val gxStart = (xMin * gw).toInt().coerceIn(2, gw - 8)
        val gxEnd = (xMax * gw).toInt().coerceIn(gxStart + 1, gw - 3)
        val winW = 7
        val winH = 7

        var wy = 0
        while (wy < gh) {
            var wx = gxStart
            while (wx <= gxEnd - winW + 1) {
                var empty = 0
                for (dy in 0 until winH) {
                    val gy = (wy + dy) % gh
                    val rOff = gy * gw
                    for (dx in 0 until winW) {
                        if (!coveredByFront[rOff + wx + dx]) empty++
                    }
                }
                if (empty >= 22) { // >= 45% of 7x7 window is uncovered black void
                    val hx = ((wx + winW * 0.5f) / gw.toFloat()).coerceIn(xMin, xMax)
                    val hu = (((wy + winH * 0.5f) / gh.toFloat()) % 1f + 1f) % 1f
                    holes.add(HoleWindow(hx, hu, empty))
                }
                wx += 6
            }
            wy += 6
        }

        holes.sortByDescending { it.emptyCells }

        var filledGaps = 0
        for ((hIdx, hole) in holes.withIndex()) {
            if (filledGaps >= gapBudget) break
            // Re-verify the center of this hole wasn't already sealed by the previous gap-filler
            val hgx = (hole.cxNorm * gw).toInt().coerceIn(0, gw - 1)
            val hgy = (hole.beltU * gh).toInt().coerceIn(0, gh - 1)
            var stillEmpty = 0
            for (dy in -2..2) {
                val rOff = ((hgy + dy + gh) % gh) * gw
                for (dx in -2..2) {
                    val cx = (hgx + dx).coerceIn(0, gw - 1)
                    if (!coveredByFront[rOff + cx]) stillEmpty++
                }
            }
            if (stillEmpty < 10) continue

            val fillerSlot = slotCount + filledGaps
            var imgIdx = pickImageIndex(fillerSlot + hIdx, rev xor 0x33557799, images.size, seed)
            if (images.size > 1) {
                var bestImgPen = Float.POSITIVE_INFINITY
                val maxImgTries = min(images.size, 10)
                for (k in 0 until maxImgTries) {
                    val candIdx = pickImageIndex(fillerSlot + hIdx + k, rev xor 0x33557799, images.size, seed)
                    val pen = proximityPenalty(hole.cxNorm, hole.beltU, candIdx, images[candIdx].sourceId)
                    if (pen < bestImgPen) {
                        bestImgPen = pen
                        imgIdx = candIdx
                        if (pen == 0f) break
                    }
                }
            }
            val entry = images[imgIdx]
            val opaqueBits = entry.opaqueMask
            val sweetBits = effectiveSweetMask(entry)
            val sweetWeight = if (entry.hasCustomSweetSpot) 50_000f else 15_000f

            val baseFillerSize = ((effMin * 0.88f + effMax * 0.34f) * cutoutVisualBoost(entry)).coerceIn(effMin * 0.80f, effMax)
            val snugFillerSize = (baseFillerSize * 0.76f).coerceAtLeast(effMin * 0.72f)

            var bestX = hole.cxNorm
            var bestU = hole.beltU
            var bestSz = baseFillerSize
            var bestScore = Float.NEGATIVE_INFINITY
            var bestSweetHits = Int.MAX_VALUE

            for (probe in 0 until 9) {
                val szTry = if (probe < 5) baseFillerSize else snugFillerSize
                val dimsTry = fitAspect(entry.aspect, baseCardSide * szTry)
                val jx = if (probe == 0) 0f else (hashFloat01(mixHash(fillerSlot, probe, rev xor seed)) - 0.5f) * 0.14f
                val ju = if (probe == 0) 0f else (hashFloat01(mixHash(fillerSlot * 19, probe, rev)) - 0.5f) * 0.10f
                val cxTry = (hole.cxNorm + jx).coerceIn(xMin, xMax)
                val cuTry = ((hole.beltU + ju) % 1f + 1f) % 1f
                val (rawSc, sh) = scoreCandidate(cxTry, cuTry, dimsTry.width, dimsTry.height, opaqueBits, sweetBits, sweetWeight)
                val sc = rawSc - proximityPenalty(cxTry, cuTry, imgIdx, entry.sourceId)
                if (sh < bestSweetHits || (sh == bestSweetHits && sc > bestScore)) {
                    bestSweetHits = sh
                    bestScore = sc
                    bestX = cxTry
                    bestU = cuTry
                    bestSz = szTry
                }
            }

            if (bestSweetHits <= 1 && bestScore > -500f) {
                val finalDims = fitAspect(entry.aspect, baseCardSide * bestSz)
                stampOpaque(bestX, bestU, finalDims.width, finalDims.height, opaqueBits)
                val hTilt = mixHash(fillerSlot, rev, seed xor 0x5A5A5A5A)
                val tiltDeg = (hashFloat01(hTilt) - 0.5f) * 2f * meta.tiltDegrees
                result.add(
                    PackedBeltSlot(
                        imgIdx = imgIdx,
                        xNorm = bestX,
                        beltU = bestU,
                        sizeMult = bestSz,
                        tiltDeg = tiltDeg,
                        zOrder = -(filledGaps + 1).toFloat(),
                        isHero = false,
                    )
                )
                filledGaps++
            }
        }
    }

    return result
}

private fun DrawScope.drawRevolvingCollage(
    images: List<ImageEntry>,
    bitmaps: Map<String, ImageBitmap>,
    meta: BoardMeta,
    phase: Float,
    seed: Int,
    neon: Color,
    secondaryNeon: Color,
    packCache: LinkedHashMap<PackCacheKey, List<PackedBeltSlot>>,
    hitTargetsOut: MutableList<HitTarget>,
) {
    hitTargetsOut.clear()
    val w = size.width
    val h = size.height
    if (w <= 1f || h <= 1f || images.isEmpty()) return

    val isLandscape = w > h
    val longAxis = max(w, h)
    val hasCutouts = images.any { it.hasAlpha || it.hasCustomSweetSpot }
    val useSweetSpotPacker = meta.maximumMode || hasCutouts || meta.spacing < 1.36f

    val items = ArrayList<RenderItem>(if (meta.maximumMode || hasCutouts) 64 else 32)

    if (useSweetSpotPacker) {
        val baseCardSide = min(w, h) * (if (isLandscape) 0.43f else 0.38f)
        val cutoutFraction = images.count { it.hasAlpha }.toFloat() / images.size.coerceAtLeast(1).toFloat()
        val cutoutSlotBonus = (cutoutFraction * 10f).roundToInt()
        val slotCount = if (meta.maximumMode) {
            (if (isLandscape) 32 else 28) + cutoutSlotBonus
        } else {
            val baseSlots = max(14, min(26, max(images.size, 14)))
            (baseSlots + cutoutSlotBonus).coerceIn(14, 34)
        }
        val beltPx = if (meta.maximumMode) {
            max(h * 1.65f, baseCardSide * 3.5f)
        } else {
            val densityScale = if (hasCutouts) 0.72f else 0.86f
            val stepPx = baseCardSide * densityScale * meta.spacing
            max(slotCount * stepPx * 0.65f, h * 1.65f)
        }

        val travelPx = phase * (longAxis * 0.38f)
        val margin = baseCardSide * max(meta.maxSize, 1.1f) * 0.95f
        val imagesSig = computeImagesSignature(images)

        val minRev = floor(((-margin - travelPx) / beltPx).toDouble()).toInt() - 1
        val maxRev = floor(((h + margin - travelPx) / beltPx).toDouble()).toInt() + 1

        for (rev in minRev..maxRev) {
            val key = PackCacheKey(
                rev = rev,
                seed = seed,
                wBucket = (w / 32f).roundToInt(),
                hBucket = (h / 32f).roundToInt(),
                maxMode = meta.maximumMode,
                minSizeBits = (meta.minSize * 100f).roundToInt(),
                maxSizeBits = (meta.maxSize * 100f).roundToInt(),
                spacingBits = (meta.spacing * 100f).roundToInt(),
                imagesSig = imagesSig,
            )
            val packed = packCache[key] ?: packRevolutionBelt(
                rev = rev,
                seed = seed,
                images = images,
                meta = meta,
                w = w,
                beltPx = beltPx,
                baseCardSide = baseCardSide,
                slotCount = slotCount,
            ).also {
                if (packCache.size >= 14) {
                    val eldest = packCache.keys.firstOrNull()
                    if (eldest != null) packCache.remove(eldest)
                }
                packCache[key] = it
            }

            for (slot in packed) {
                if (slot.imgIdx !in images.indices) continue
                val entry = images[slot.imgIdx]
                val worldY = (rev + slot.beltU) * beltPx + travelPx

                if (meta.mode == DisplayMode.WHEEL) {
                    val localAngle = (worldY - h * 0.5f) / (h * 0.64f)
                    if (abs(localAngle) > 1.44f) continue
                    val depth = cos(localAngle)
                    if (depth <= -0.04f) continue
                    val perspective = 0.82f + 0.20f * depth.coerceAtLeast(0f)
                    val dims = fitAspect(entry.aspect, baseCardSide * slot.sizeMult * perspective)
                    val cx = w * slot.xNorm
                    val cy = h * 0.50f + sin(localAngle) * (h * 0.56f)
                    val edgeAlpha = ((depth + 0.04f) / 0.24f).coerceIn(0f, 1f)
                    items.add(
                        RenderItem(
                            entry = entry,
                            bmp = bitmaps[entry.cacheKey],
                            cx = cx,
                            cy = cy,
                            w = dims.width,
                            h = dims.height,
                            tiltDeg = slot.tiltDeg,
                            alpha = edgeAlpha,
                            zOrder = slot.zOrder,
                            isHero = slot.isHero,
                        )
                    )
                } else {
                    if (worldY < -margin || worldY > h + margin) continue
                    val dims = fitAspect(entry.aspect, baseCardSide * slot.sizeMult)
                    items.add(
                        RenderItem(
                            entry = entry,
                            bmp = bitmaps[entry.cacheKey],
                            cx = w * slot.xNorm,
                            cy = worldY,
                            w = dims.width,
                            h = dims.height,
                            tiltDeg = slot.tiltDeg,
                            alpha = 1f,
                            zOrder = slot.zOrder,
                            isHero = slot.isHero,
                        )
                    )
                }
            }
        }
    } else if (meta.mode == DisplayMode.WHEEL) {
        val slotCount = max(12, min(24, max(images.size, 12)))
        val baseCardSide = min(w, h) * (if (isLandscape) 0.42f else 0.37f)
        val radiusY = h * 0.56f
        val centerY = h * 0.50f
        val stepAngle = (0.58f * meta.spacing).coerceAtLeast(0.28f)
        val beltAngle = max(slotCount * stepAngle, 4.2f)
        val visibleLimitRad = 1.44f
        val effectivePhase = if (isLandscape) phase * (w / h).coerceIn(1f, 2.15f) else phase
        val revSeqCache = HashMap<Int, IntArray>()

        for (slot in 0 until slotCount) {
            val u = (slot * stepAngle + effectivePhase) / beltAngle
            val rev = floor((u + 0.5f).toDouble()).toInt()
            val localAngle = (u - rev) * beltAngle
            if (abs(localAngle) > visibleLimitRad) continue

            val depth = cos(localAngle)
            if (depth <= -0.04f) continue

            val seq = revSeqCache.getOrPut(rev) { buildDeclusteredSlotSequence(slotCount, rev, images, seed) }
            val imgIdx = seq[slot]
            val entry = images[imgIdx]

            val h1 = mixHash(slot, rev, seed xor 0x13579BDF)
            val h3 = mixHash(slot, rev, seed xor 0x7F4A7C15)

            val laneAnchor = StaggeredLanes[(slot + abs(rev) * 2) % StaggeredLanes.size]
            val xNorm = if (isLandscape) {
                (0.12f + (laneAnchor - 0.21f) / 0.58f * 0.76f + (hashFloat01(h1) - 0.5f) * 0.12f).coerceIn(0.12f, 0.88f)
            } else {
                (laneAnchor + (hashFloat01(h1) - 0.5f) * 0.11f).coerceIn(0.16f, 0.84f)
            }

            val sizeMult = computeDynamicSize(slot, rev, seed, entry, meta.minSize, meta.maxSize)
            val heroThreshold = meta.minSize + (meta.maxSize - meta.minSize) * 0.72f
            val isHero = sizeMult >= heroThreshold

            val tiltDeg = (hashFloat01(h3) - 0.5f) * 2f * meta.tiltDegrees

            val perspective = 0.72f + 0.32f * depth.coerceAtLeast(0f)
            val cardScale = sizeMult * perspective
            val dims = fitAspect(entry.aspect, baseCardSide * cardScale)

            val cx = w * xNorm
            val cy = centerY + sin(localAngle) * radiusY
            val edgeAlpha = ((depth + 0.04f) / 0.28f).coerceIn(0f, 1f)

            items.add(
                RenderItem(
                    entry = entry,
                    bmp = bitmaps[entry.cacheKey],
                    cx = cx,
                    cy = cy,
                    w = dims.width,
                    h = dims.height,
                    tiltDeg = tiltDeg,
                    alpha = edgeAlpha,
                    zOrder = depth * 1.1f + sizeMult * 0.25f,
                    isHero = isHero,
                )
            )
        }
    } else {
        val slotCount = max(12, min(24, max(images.size, 12)))
        val baseCardSide = min(w, h) * (if (isLandscape) 0.42f else 0.37f)
        val margin = baseCardSide * max(meta.maxSize, 1.0f) * 0.85f
        val stepPx = baseCardSide * 0.88f * meta.spacing
        val beltPx = max(slotCount * stepPx, h + margin * 2.2f)
        val travelPx = phase * (longAxis * 0.38f)
        val revSeqCache = HashMap<Int, IntArray>()

        for (slot in 0 until slotCount) {
            val u = (slot * stepPx + travelPx) / beltPx
            val rev = floor(u.toDouble()).toInt()
            val y = -margin + (u - rev) * beltPx
            if (y < -margin || y > h + margin) continue

            val seq = revSeqCache.getOrPut(rev) { buildDeclusteredSlotSequence(slotCount, rev, images, seed) }
            val imgIdx = seq[slot]
            val entry = images[imgIdx]

            val h1 = mixHash(slot, rev, seed xor 0x3C6EF372)
            val h3 = mixHash(slot, rev, seed xor 0x510E527F)

            val laneAnchor = StaggeredLanes[(slot + abs(rev) * 2) % StaggeredLanes.size]
            val xNorm = if (isLandscape) {
                (0.12f + (laneAnchor - 0.21f) / 0.58f * 0.76f + (hashFloat01(h1) - 0.5f) * 0.12f).coerceIn(0.12f, 0.88f)
            } else {
                (laneAnchor + (hashFloat01(h1) - 0.5f) * 0.11f).coerceIn(0.16f, 0.84f)
            }

            val sizeMult = computeDynamicSize(slot, rev, seed, entry, meta.minSize, meta.maxSize)
            val heroThreshold = meta.minSize + (meta.maxSize - meta.minSize) * 0.72f
            val isHero = sizeMult >= heroThreshold
            val tiltDeg = (hashFloat01(h3) - 0.5f) * 2f * meta.tiltDegrees

            val dims = fitAspect(entry.aspect, baseCardSide * sizeMult)
            val cx = w * xNorm

            items.add(
                RenderItem(
                    entry = entry,
                    bmp = bitmaps[entry.cacheKey],
                    cx = cx,
                    cy = y,
                    w = dims.width,
                    h = dims.height,
                    tiltDeg = tiltDeg,
                    alpha = 1f,
                    zOrder = sizeMult,
                    isHero = isHero,
                )
            )
        }
    }

    items.sortBy { it.zOrder }

    for (item in items) {
        drawPosterItem(
            item = item,
            showBorders = meta.showBorders,
            showCutoutOutline = meta.showCutoutOutline,
            neon = neon,
            secondaryNeon = secondaryNeon,
        )
        hitTargetsOut.add(
            HitTarget(
                entry = item.entry,
                cx = item.cx,
                cy = item.cy,
                halfW = item.w * 0.5f,
                halfH = item.h * 0.5f,
                tiltDeg = item.tiltDeg,
            )
        )
    }
}

private fun fitAspect(aspect: Float, targetBox: Float): Size {
    val clamped = aspect.coerceIn(0.35f, 2.8f)
    return if (clamped >= 1f) {
        Size(targetBox * 1.08f, (targetBox * 1.08f) / clamped)
    } else {
        Size((targetBox * 1.12f) * clamped, targetBox * 1.12f)
    }
}

private val StickerOffsets = arrayOf(
    Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f), Offset(0f, 1f),
    Offset(-0.7f, -0.7f), Offset(0.7f, -0.7f), Offset(-0.7f, 0.7f), Offset(0.7f, 0.7f),
)

private fun DrawScope.drawPosterItem(
    item: RenderItem,
    showBorders: Boolean,
    showCutoutOutline: Boolean,
    neon: Color,
    secondaryNeon: Color,
) {
    val dstW = item.w.roundToInt().coerceAtLeast(2)
    val dstH = item.h.roundToInt().coerceAtLeast(2)
    val topLeftX = -dstW / 2
    val topLeftY = -dstH / 2
    val accent = if (item.isHero) neon else secondaryNeon

    withTransform({
        translate(item.cx, item.cy)
        rotate(item.tiltDeg, Offset.Zero)
    }) {
        val bmp = item.bmp ?: return@withTransform

        if (item.entry.hasAlpha) {
            if (showCutoutOutline) {
                if (item.isHero) {
                    val glowPad = 5.dp.toPx().roundToInt()
                    val glowTint = ColorFilter.tint(neon.copy(alpha = 0.24f * item.alpha), BlendMode.SrcIn)
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(topLeftX - glowPad, topLeftY - glowPad),
                        dstSize = IntSize(dstW + glowPad * 2, dstH + glowPad * 2),
                        colorFilter = glowTint,
                    )
                }
                val rimPx = (if (item.isHero) 2.5.dp else 1.7.dp).toPx()
                val rimColor = if (item.isHero) neon.copy(alpha = 0.92f * item.alpha) else DreamPalette.PureWhite.copy(alpha = 0.78f * item.alpha)
                val rimTint = ColorFilter.tint(rimColor, BlendMode.SrcIn)
                for (dir in StickerOffsets) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(
                            topLeftX + (dir.x * rimPx).roundToInt(),
                            topLeftY + (dir.y * rimPx).roundToInt(),
                        ),
                        dstSize = IntSize(dstW, dstH),
                        colorFilter = rimTint,
                    )
                }
            }
            // Pure borderless transparent PNG subject
            drawImage(
                image = bmp,
                dstOffset = IntOffset(topLeftX, topLeftY),
                dstSize = IntSize(dstW, dstH),
                alpha = item.alpha,
            )
        } else {
            if (!showBorders) {
                // Clean borderless image
                drawImage(
                    image = bmp,
                    dstOffset = IntOffset(topLeftX, topLeftY),
                    dstSize = IntSize(dstW, dstH),
                    alpha = item.alpha,
                )
            } else {
                val pad = 4.dp.toPx()
                val corner = CornerRadius(10.dp.toPx())
                if (item.isHero) {
                    drawRoundRect(
                        color = neon.copy(alpha = 0.24f * item.alpha),
                        topLeft = Offset(topLeftX - pad * 2.2f, topLeftY - pad * 2.2f),
                        size = Size(dstW + pad * 4.4f, dstH + pad * 4.4f),
                        cornerRadius = CornerRadius(14.dp.toPx()),
                    )
                }
                drawRoundRect(
                    color = DreamPalette.VoidBlack.copy(alpha = 0.92f * item.alpha),
                    topLeft = Offset(topLeftX - pad, topLeftY - pad),
                    size = Size(dstW + pad * 2f, dstH + pad * 2f),
                    cornerRadius = corner,
                )
                drawImage(
                    image = bmp,
                    dstOffset = IntOffset(topLeftX, topLeftY),
                    dstSize = IntSize(dstW, dstH),
                    alpha = item.alpha,
                )
                drawRoundRect(
                    color = accent.copy(alpha = (if (item.isHero) 0.90f else 0.42f) * item.alpha),
                    topLeft = Offset(topLeftX - pad, topLeftY - pad),
                    size = Size(dstW + pad * 2f, dstH + pad * 2f),
                    cornerRadius = corner,
                    style = Stroke(width = if (item.isHero) 2.dp.toPx() else 1.dp.toPx()),
                )
            }
        }
    }
}

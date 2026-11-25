package com.dreamos.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.app.vault.BoardSession
import com.dreamos.app.vault.ImageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MAX_VIEWER_ZOOM = 10f

private fun clampZoomOffset(
    raw: Offset,
    scale: Float,
    boxSize: IntSize,
    imgW: Int,
    imgH: Int,
): Offset {
    if (scale <= 1.01f || boxSize.width <= 1 || boxSize.height <= 1) return Offset.Zero
    val bw = boxSize.width.toFloat()
    val bh = boxSize.height.toFloat()
    val fitScale = if (imgW > 0 && imgH > 0) {
        min(bw / imgW.toFloat(), bh / imgH.toFloat())
    } else 1f
    val fitW = if (imgW > 0) imgW * fitScale else bw
    val fitH = if (imgH > 0) imgH * fitScale else bh
    val maxX = max((fitW * scale - bw) * 0.5f, fitW * (scale - 1f) * 0.5f)
    val maxY = max((fitH * scale - bh) * 0.5f, fitH * (scale - 1f) * 0.5f)
    return Offset(
        raw.x.coerceIn(-maxX, maxX),
        raw.y.coerceIn(-maxY, maxY),
    )
}

@Composable
fun FullscreenViewerScreen(
    session: BoardSession,
    startImageId: String,
    onContinueWheel: () -> Unit,
    onEditImage: (String) -> Unit,
    onDeleteImage: (String) -> Unit = {},
) {
    val images = session.meta.images
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val neon = DreamPalette.neonColor(session.info.color)

    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onContinueWheel() }
        return
    }

    val imageCount = images.size
    val virtualPageCount = if (imageCount > 1) 1_000_000 else 1
    val startVirtualPage = remember(startImageId, imageCount) {
        val idx = images.indexOfFirst { it.id == startImageId }.coerceAtLeast(0)
        if (imageCount > 1) {
            ((virtualPageCount / 2) / imageCount) * imageCount + idx
        } else {
            0
        }
    }
    val pagerState = rememberPagerState(initialPage = startVirtualPage) { virtualPageCount }
    val currentRealIndex = pagerState.currentPage.mod(imageCount)
    val scope = rememberCoroutineScope()
    var hudVisible by remember { mutableStateOf(true) }

    var activeScale by remember { mutableFloatStateOf(1f) }
    var activeOffset by remember { mutableStateOf(Offset.Zero) }
    var activeBoxSize by remember { mutableStateOf(IntSize.Zero) }
    var activeImgW by remember { mutableStateOf(0) }
    var activeImgH by remember { mutableStateOf(0) }
    var zoomAnimJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(pagerState.currentPage) {
        zoomAnimJob?.cancel()
        activeScale = 1f
        activeOffset = Offset.Zero
    }

    fun animateZoomTo(targetScale: Float, targetOffset: Offset = Offset.Zero) {
        zoomAnimJob?.cancel()
        val startS = activeScale
        val startO = activeOffset
        val endS = targetScale.coerceIn(1f, MAX_VIEWER_ZOOM)
        val endO = clampZoomOffset(targetOffset, endS, activeBoxSize, activeImgW, activeImgH)
        zoomAnimJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(190)) { t, _ ->
                val curS = startS + (endS - startS) * t
                val rawO = Offset(
                    startO.x + (endO.x - startO.x) * t,
                    startO.y + (endO.y - startO.y) * t,
                )
                activeScale = if (curS <= 1.01f && endS <= 1.01f) 1f else curS
                activeOffset = clampZoomOffset(rawO, activeScale, activeBoxSize, activeImgW, activeImgH)
            }
        }
    }

    val activePageZoomed = activeScale > 1.03f
    val bgColor = if (session.meta.showWallBackground) DreamPalette.VoidBlack else Color.Black

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .onSizeChanged { activeBoxSize = it }
    ) {
        if (session.meta.showWallBackground) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(neon.copy(alpha = 0.10f), Color.Transparent),
                        )
                    )
            )
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !activePageZoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val entry = images[page.mod(imageCount)]
            val isCurrent = page == pagerState.currentPage
            ZoomableDecryptedPage(
                pageKey = page,
                session = session,
                entry = entry,
                isCurrent = isCurrent,
                scale = if (isCurrent) activeScale else 1f,
                offset = if (isCurrent) activeOffset else Offset.Zero,
                onImageDimensions = { w, h ->
                    if (isCurrent) {
                        activeImgW = w
                        activeImgH = h
                    }
                },
                onTransform = { newScale, rawOffset, pageSize, imgW, imgH ->
                    if (isCurrent) {
                        zoomAnimJob?.cancel()
                        activeBoxSize = pageSize
                        activeImgW = imgW
                        activeImgH = imgH
                        if (newScale <= 1.01f) {
                            activeScale = 1f
                            activeOffset = Offset.Zero
                        } else {
                            activeScale = newScale.coerceIn(1f, MAX_VIEWER_ZOOM)
                            activeOffset = clampZoomOffset(rawOffset, activeScale, pageSize, imgW, imgH)
                        }
                    }
                },
                onDoubleTapZoom = { tapPos, pageSize, imgW, imgH ->
                    if (isCurrent) {
                        activeBoxSize = pageSize
                        activeImgW = imgW
                        activeImgH = imgH
                        if (activeScale > 1.08f) {
                            animateZoomTo(1f, Offset.Zero)
                        } else {
                            val targetS = 3.0f
                            val center = Offset(pageSize.width * 0.5f, pageSize.height * 0.5f)
                            val focalOffset = (center - tapPos) * (targetS - 1f)
                            animateZoomTo(targetS, focalOffset)
                        }
                    }
                },
                onSingleTap = { hudVisible = !hudVisible },
            )
        }

        if (hudVisible) {
            // Top bar: page counter + quick edit button
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(DreamPalette.VoidBlack.copy(alpha = 0.78f))
                        .border(1.dp, neon.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "${currentRealIndex + 1} / $imageCount",
                        color = neon,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeonHudIconButton(
                        neon = neon,
                        padding = PaddingValues(8.dp),
                        onClick = { hudVisible = false },
                    ) {
                        EyeHideGlyph(color = DreamPalette.SilverText, modifier = Modifier.size(16.dp))
                    }

                    NeonHudIconButton(
                        neon = Color(0xFFFF3B5C),
                        padding = PaddingValues(8.dp),
                        onClick = { pendingDeleteId = images[currentRealIndex].id },
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                            contentDescription = "Delete photo",
                            tint = Color(0xFFFF6B82),
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    NeonHudIconButton(
                        neon = neon,
                        padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        onClick = { onEditImage(images[currentRealIndex].id) },
                    ) {
                        LassoGlyph(color = neon, modifier = Modifier.size(16.dp))
                        Text(
                            text = "CUTOUT / CROP",
                            color = DreamPalette.PureWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                }
            }

            // Endless circular Left / Right parse chevrons (never stop at start or end)
            if (imageCount > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DreamPalette.VoidBlack.copy(alpha = 0.76f))
                        .border(1.dp, neon.copy(alpha = 0.55f), CircleShape)
                        .clickable {
                            zoomAnimJob?.cancel()
                            activeScale = 1f
                            activeOffset = Offset.Zero
                            val prevPage = (pagerState.targetPage - 1).coerceAtLeast(0)
                            scope.launch { pagerState.animateScrollToPage(prevPage) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous",
                        tint = DreamPalette.PureWhite,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DreamPalette.VoidBlack.copy(alpha = 0.76f))
                        .border(1.dp, neon.copy(alpha = 0.55f), CircleShape)
                        .clickable {
                            zoomAnimJob?.cancel()
                            activeScale = 1f
                            activeOffset = Offset.Zero
                            val nextPage = (pagerState.targetPage + 1).coerceAtMost(virtualPageCount - 1)
                            scope.launch { pagerState.animateScrollToPage(nextPage) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next",
                        tint = DreamPalette.PureWhite,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            // Bottom HUD: Zoom Controls (− / 1.0x / +) + Continue Wheel/Waterfall button
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(DreamPalette.VoidBlack.copy(alpha = 0.84f))
                        .border(
                            1.dp,
                            if (activePageZoomed) neon.copy(alpha = 0.75f) else DreamPalette.GraphiteLine,
                            RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(enabled = activeScale > 1.02f) {
                                val nextS = (activeScale / 1.5f).let { if (it < 1.12f) 1f else it }
                                val nextO = if (nextS <= 1.01f) Offset.Zero else activeOffset * (nextS / activeScale)
                                animateZoomTo(nextS, nextO)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "−",
                            color = if (activeScale > 1.02f) DreamPalette.PureWhite else DreamPalette.SteelMuted,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (activePageZoomed) neon.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable {
                                if (activePageZoomed) {
                                    animateZoomTo(1f, Offset.Zero)
                                } else {
                                    animateZoomTo(2.5f, Offset.Zero)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val zoomText = String.format(Locale.US, "%.1fx", activeScale)
                        Text(
                            text = if (activePageZoomed) "$zoomText ↺" else "ZOOM $zoomText",
                            color = if (activePageZoomed) neon else DreamPalette.SilverText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(enabled = activeScale < MAX_VIEWER_ZOOM - 0.05f) {
                                val nextS = (activeScale * 1.5f).coerceAtMost(MAX_VIEWER_ZOOM)
                                val nextO = activeOffset * (nextS / activeScale.coerceAtLeast(1f))
                                animateZoomTo(nextS, nextO)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+",
                            color = if (activeScale < MAX_VIEWER_ZOOM - 0.05f) DreamPalette.PureWhite else DreamPalette.SteelMuted,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .neonGlowBox(neon = neon, cornerRadius = 28.dp, glowAlpha = 0.35f, surfaceColor = DreamPalette.SlateElevated)
                        .clickable(onClick = onContinueWheel)
                        .padding(horizontal = 26.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Continue Wheel",
                        tint = neon,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "CONTINUE ${session.meta.mode.name}",
                        color = DreamPalette.PureWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp,
                    )
                }
            }
        }
    }

    val delId = pendingDeleteId
    val delEntry = delId?.let { id -> images.firstOrNull { it.id == id } }
    if (delEntry != null) {
        val siblingCount = images.count { it.sourceId == delEntry.sourceId && it.id != delEntry.id }
        val danger = Color(0xFFFF3B5C)
        val body = if (delEntry.isVariation) {
            "This permanently deletes THIS VARIATION only. The main photo and any other variations stay."
        } else if (siblingCount > 0) {
            "This permanently deletes the MAIN PHOTO. Its $siblingCount variation(s) will be kept."
        } else {
            "This permanently shreds this photo from the shrine. It cannot be recovered."
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            containerColor = DreamPalette.VoidBlack,
            title = {
                Text(
                    text = if (delEntry.isVariation) "DELETE VARIATION FOR GOOD?" else "DELETE PHOTO FOR GOOD?",
                    color = danger,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                )
            },
            text = { Text(text = body, color = DreamPalette.PureWhite, fontSize = 13.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingDeleteId = null
                    onDeleteImage(delEntry.id)
                }) {
                    Text("DELETE FOREVER", color = danger, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteId = null }) {
                    Text("CANCEL", color = DreamPalette.PureWhite, fontFamily = FontFamily.Monospace)
                }
            },
        )
    }
}

@Composable
private fun ZoomableDecryptedPage(
    pageKey: Int,
    session: BoardSession,
    entry: ImageEntry,
    isCurrent: Boolean,
    scale: Float,
    offset: Offset,
    onImageDimensions: (Int, Int) -> Unit,
    onTransform: (newScale: Float, rawOffset: Offset, pageSize: IntSize, imgW: Int, imgH: Int) -> Unit,
    onDoubleTapZoom: (tapPos: Offset, pageSize: IntSize, imgW: Int, imgH: Int) -> Unit,
    onSingleTap: () -> Unit,
) {
    var bmp by remember(entry.cacheKey) {
        mutableStateOf<ImageBitmap?>(session.cachedDisplay(entry) ?: session.cachedThumb(entry))
    }
    var highResLoaded by remember(entry.cacheKey) { mutableStateOf(false) }

    LaunchedEffect(entry.cacheKey) {
        val disp = session.cachedDisplay(entry) ?: withContext(Dispatchers.IO) { session.loadDisplay(entry) }
        if (disp != null) {
            bmp = disp
            onImageDimensions(disp.width, disp.height)
        }
    }

    // Upgrade to crisp master resolution on demand when the user zooms in
    LaunchedEffect(entry.cacheKey, isCurrent, scale > 1.5f) {
        if (isCurrent && scale > 1.5f && !highResLoaded) {
            val hi = withContext(Dispatchers.IO) { session.loadHighResDisplay(entry) }
            if (hi != null) {
                bmp = hi
                highResLoaded = true
                onImageDimensions(hi.width, hi.height)
            }
        }
    }

    val latestScale by rememberUpdatedState(scale)
    val latestOffset by rememberUpdatedState(offset)
    val latestBmp by rememberUpdatedState(bmp)
    val latestOnTransform by rememberUpdatedState(onTransform)
    val latestOnDoubleTap by rememberUpdatedState(onDoubleTapZoom)
    val latestOnSingleTap by rememberUpdatedState(onSingleTap)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(pageKey, entry.cacheKey) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var panDragging = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        val imgW = latestBmp?.width ?: entry.renderW
                        val imgH = latestBmp?.height ?: entry.renderH

                        if (pressed.size >= 2) {
                            multiTouch = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val curScale = latestScale
                            val curOffset = latestOffset
                            val newScale = (curScale * zoomChange).coerceIn(1f, MAX_VIEWER_ZOOM)
                            val ratio = newScale / curScale.coerceAtLeast(0.01f)
                            val center = Offset(size.width * 0.5f, size.height * 0.5f)
                            val rawOffset = (curOffset + centroid - center) * ratio - (centroid - center) + panChange
                            latestOnTransform(newScale, rawOffset, size, imgW, imgH)
                            event.changes.forEach { it.consume() }
                        } else if (!multiTouch && latestScale > 1.02f) {
                            val ptr = event.changes.firstOrNull { it.id == down.id } ?: pressed.first()
                            val movedFromDown = hypot(
                                ptr.position.x - down.position.x,
                                ptr.position.y - down.position.y,
                            )
                            if (panDragging || movedFromDown > touchSlop * 0.45f) {
                                panDragging = true
                                val dp = ptr.position - ptr.previousPosition
                                latestOnTransform(latestScale, latestOffset + dp, size, imgW, imgH)
                                ptr.consume()
                            }
                        }
                    }

                    // Snap cleanly back to 1.0x if user pinched almost all the way out
                    if (multiTouch && latestScale < 1.04f) {
                        val imgW = latestBmp?.width ?: entry.renderW
                        val imgH = latestBmp?.height ?: entry.renderH
                        latestOnTransform(1f, Offset.Zero, size, imgW, imgH)
                    }
                }
            }
            .pointerInput(pageKey, entry.cacheKey) {
                detectTapGestures(
                    onDoubleTap = { tapPos ->
                        val imgW = latestBmp?.width ?: entry.renderW
                        val imgH = latestBmp?.height ?: entry.renderH
                        latestOnDoubleTap(tapPos, size, imgW, imgH)
                    },
                    onTap = { latestOnSingleTap() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

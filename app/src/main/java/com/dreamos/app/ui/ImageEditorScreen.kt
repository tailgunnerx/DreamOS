package com.dreamos.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.app.image.ImageOps
import com.dreamos.app.image.Segmenter
import com.dreamos.app.vault.BoardSession
import com.dreamos.app.vault.Edits
import com.dreamos.app.vault.LassoMode
import com.dreamos.app.vault.MaskOp
import com.dreamos.app.vault.NRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class WizardStep { CROP, CUTOUT, SWEET_SPOT, REVIEW }

private enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("FREE", null),
    SQ_1_1("1:1", 1f),
    PORT_4_5("4:5", 0.8f),
    PORT_3_4("3:4", 0.75f),
    TALL_9_16("9:16", 9f / 16f),
    WIDE_16_9("16:9", 16f / 9f),
}

private enum class CutoutDrawTool { KEEP_LOOP, CUT_HOLE_LOOP, ERASER_BRUSH, RESTORE_BRUSH }

private enum class SweetSpotTool { PAINT_BRUSH, LOOP_SPOT, ERASE_BRUSH }

private enum class CropDragTarget {
    NONE, TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM, INSIDE, MOVE_KNOB
}

private data class PreviewBundle(
    val fullMasked: ImageBitmap,
    val croppedCutout: ImageBitmap,
    val trimmedFinal: ImageBitmap,
    val croppedSweetOverlay: ImageBitmap?,
    val liveSweetBits: Long,
    val liveOpaqueBits: Long,
)

@Composable
fun ImageEditorScreen(
    session: BoardSession,
    imageId: String,
    onCancel: () -> Unit,
    onSave: (Edits, Map<String, Bitmap>) -> Unit,
    onSwitchVariation: (targetVariantId: String, currentEdits: Edits, newMasks: Map<String, Bitmap>, isDirty: Boolean) -> Unit,
    onCreateVariation: (copyCutoutMasks: Boolean, currentEdits: Edits, newMasks: Map<String, Bitmap>, isDirty: Boolean) -> Unit,
    onDeleteVariation: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val neon = DreamPalette.neonColor(session.info.color)
    val sweetGold = Color(0xFFFFD54F)

    val entry = remember(imageId, session.meta) {
        session.meta.images.firstOrNull { it.id == imageId }
    }

    if (entry == null) {
        LaunchedEffect(Unit) { onCancel() }
        return
    }

    val siblingVariants = remember(entry.sourceId, session.meta) {
        session.meta.images.filter { it.sourceId == entry.sourceId }
    }
    val variantIndex = remember(imageId, siblingVariants) {
        (siblingVariants.indexOfFirst { it.id == imageId } + 1).coerceAtLeast(1)
    }

    var originalBmp by remember(imageId) { mutableStateOf<Bitmap?>(null) }
    var originalHasNativeAlpha by remember(imageId) { mutableStateOf(false) }
    val persistedMasks = remember(imageId) { mutableStateMapOf<String, Bitmap>() }
    val newMasks = remember(imageId) { mutableStateMapOf<String, Bitmap>() }

    var edits by remember(imageId) { mutableStateOf(entry.edits) }
    val undoStack = remember(imageId) { mutableStateListOf<Edits>() }
    val redoStack = remember(imageId) { mutableStateListOf<Edits>() }
    val isDirty = undoStack.isNotEmpty() || edits != entry.edits

    var step by remember(imageId) { mutableStateOf(WizardStep.CROP) }
    var cropAspect by remember(imageId) { mutableStateOf(CropAspect.FREE) }
    var autoFocusCrop by remember { mutableStateOf(false) }
    var focusAnimJob by remember { mutableStateOf<Job?>(null) }

    var cutoutTool by remember(imageId) { mutableStateOf(CutoutDrawTool.KEEP_LOOP) }
    var sweetTool by remember(imageId) { mutableStateOf(SweetSpotTool.PAINT_BRUSH) }
    var brushSizeDp by remember { mutableFloatStateOf(24f) }
    var showReviewCheckerboard by remember { mutableStateOf(false) }

    // Previews for each wizard step
    var fullMaskedBitmap by remember(imageId) { mutableStateOf<ImageBitmap?>(null) }
    var croppedCutoutBitmap by remember(imageId) { mutableStateOf<ImageBitmap?>(null) }
    var trimmedFinalBitmap by remember(imageId) { mutableStateOf<ImageBitmap?>(null) }
    var croppedSweetBitmap by remember(imageId) { mutableStateOf<ImageBitmap?>(null) }
    var liveSweetBits by remember(imageId) { mutableStateOf(entry.sweetMask) }
    var liveOpaqueBits by remember(imageId) { mutableStateOf(entry.opaqueMask) }

    var aiBusy by remember { mutableStateOf(false) }
    var wizardBanner by remember(imageId) { mutableStateOf<String?>(null) }

    var viewZoom by remember(imageId, step) { mutableFloatStateOf(1f) }
    var viewPan by remember(imageId, step) { mutableStateOf(Offset.Zero) }
    var canvasPixelSize by remember { mutableStateOf(Size(1f, 1f)) }

    BackHandler(enabled = step != WizardStep.CROP) {
        step = when (step) {
            WizardStep.REVIEW -> WizardStep.SWEET_SPOT
            WizardStep.SWEET_SPOT -> WizardStep.CUTOUT
            WizardStep.CUTOUT -> WizardStep.CROP
            WizardStep.CROP -> WizardStep.CROP
        }
    }

    fun pushEdits(next: Edits) {
        undoStack.add(edits)
        redoStack.clear()
        edits = next
    }

    val canUndoInCutout = undoStack.isNotEmpty() || edits.ops.isNotEmpty()

    fun performUndo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(edits)
            edits = undoStack.removeAt(undoStack.lastIndex)
            wizardBanner = if (step == WizardStep.CUTOUT) "Undid last cutout action." else null
        } else if (step == WizardStep.CUTOUT && edits.ops.isNotEmpty()) {
            redoStack.add(edits)
            edits = edits.copy(ops = edits.ops.dropLast(1))
            if (edits.ops.isEmpty()) cutoutTool = CutoutDrawTool.KEEP_LOOP
            wizardBanner = "Undid previous cutout stroke."
        }
    }

    fun performRedo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(edits)
            edits = redoStack.removeAt(redoStack.lastIndex)
            wizardBanner = "Redid cutout action."
        }
    }

    // Load untouched encrypted master + existing AI masks
    LaunchedEffect(imageId) {
        withContext(Dispatchers.IO) {
            val orig = session.loadOriginal(entry, ImageOps.EDITOR_MAX)
            val nativeAlpha = orig?.let { ImageOps.hasTransparentPixels(it) } ?: false
            val existingMaskIds = entry.edits.ops.filterIsInstance<MaskOp.AiCutout>().map { it.maskId }.distinct()
            val loaded = existingMaskIds.mapNotNull { id -> session.loadMask(id)?.let { id to it } }
            withContext(Dispatchers.Main) {
                loaded.forEach { (k, v) -> persistedMasks[k] = v }
                originalHasNativeAlpha = nativeAlpha
                if (nativeAlpha || entry.edits.ops.isNotEmpty()) {
                    cutoutTool = CutoutDrawTool.CUT_HOLE_LOOP
                }
                originalBmp = orig
            }
        }
    }

    var rawMaskedBmp by remember(imageId) { mutableStateOf<Bitmap?>(null) }

    // 1) Only re-apply cutout masks when originalBmp or cutout ops change (NEVER on crop drag!)
    LaunchedEffect(originalBmp, edits.ops, newMasks.size, persistedMasks.size) {
        val orig = originalBmp ?: return@LaunchedEffect
        val masked = withContext(Dispatchers.Default) {
            ImageOps.applyMasks(orig, edits.ops) { id -> newMasks[id] ?: persistedMasks[id] }
        }
        rawMaskedBmp = masked
        fullMaskedBitmap = masked.asImageBitmap()
    }

    // 2) Rebuild cropped/trimmed/sweet-spot bitmaps when needed (without touching fullMaskedBitmap!)
    LaunchedEffect(rawMaskedBmp, edits.crop, edits.sweetOps, step) {
        val orig = originalBmp ?: return@LaunchedEffect
        val masked = rawMaskedBmp ?: return@LaunchedEffect
        val results = withContext(Dispatchers.Default) {
            val cropped = ImageOps.crop(masked, edits.crop)
            val hasAlpha = originalHasNativeAlpha || edits.hasTransparency
            val trimmed = if (hasAlpha) ImageOps.trimTransparentBounds(cropped) else cropped
            val opaque8 = if (hasAlpha) ImageOps.computeGridBitmask8x8(cropped, 32, 0.10f) else -1L
            val fullSweet = ImageOps.buildSweetMask(orig.width, orig.height, edits.sweetOps)
            val croppedSweet = fullSweet?.let { ImageOps.crop(it, edits.crop) }
            val sweet8 = croppedSweet?.let { ImageOps.computeGridBitmask8x8(it, 64, 0.06f) } ?: 0L
            Triple(
                cropped.asImageBitmap() to trimmed.asImageBitmap(),
                croppedSweet?.asImageBitmap(),
                sweet8 to opaque8,
            )
        }
        croppedCutoutBitmap = results.first.first
        trimmedFinalBitmap = results.first.second
        croppedSweetBitmap = results.second
        liveSweetBits = results.third.first
        liveOpaqueBits = results.third.second
    }

    fun computeFitRect(bmp: ImageBitmap, cSize: Size, z: Float, p: Offset): Rect {
        val pad = with(density) { 42.dp.toPx() }
        val availW = (cSize.width - pad * 2f).coerceAtLeast(1f)
        val availH = (cSize.height - pad * 2f).coerceAtLeast(1f)
        val fitScale = min(availW / bmp.width.toFloat(), availH / bmp.height.toFloat())
        val drawW = bmp.width * fitScale * z
        val drawH = bmp.height * fitScale * z
        val left = (cSize.width - drawW) * 0.5f + p.x
        val top = (cSize.height - drawH) * 0.5f + p.y
        return Rect(left, top, left + drawW, top + drawH)
    }

    fun focusCameraOntoCrop(targetCrop: NRect?, animateTransition: Boolean = true) {
        val bmp = fullMaskedBitmap ?: return
        if (canvasPixelSize.width <= 10f || canvasPixelSize.height <= 10f) return
        focusAnimJob?.cancel()

        val targetZoom: Float
        val targetPan: Offset
        if (targetCrop == null || (targetCrop.width >= 0.94f && targetCrop.height >= 0.94f)) {
            targetZoom = 1f
            targetPan = Offset.Zero
        } else {
            val baseRect = computeFitRect(bmp, canvasPixelSize, 1f, Offset.Zero)
            val cropW1 = (targetCrop.width * baseRect.width).coerceAtLeast(1f)
            val cropH1 = (targetCrop.height * baseRect.height).coerceAtLeast(1f)
            val z = min(
                (canvasPixelSize.width * 0.72f) / cropW1,
                (canvasPixelSize.height * 0.72f) / cropH1,
            ).coerceIn(1f, 7.5f)
            val mcx = (targetCrop.left + targetCrop.right) * 0.5f
            val mcy = (targetCrop.top + targetCrop.bottom) * 0.5f
            val drawW = baseRect.width * z
            val drawH = baseRect.height * z
            targetZoom = z
            targetPan = Offset((0.5f - mcx) * drawW, (0.5f - mcy) * drawH)
        }

        if (!animateTransition) {
            viewZoom = targetZoom
            viewPan = targetPan
            return
        }

        val startZ = viewZoom
        val startP = viewPan
        focusAnimJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis = 240)) { frac, _ ->
                viewZoom = startZ + (targetZoom - startZ) * frac
                viewPan = Offset(
                    startP.x + (targetPan.x - startP.x) * frac,
                    startP.y + (targetPan.y - startP.y) * frac,
                )
            }
        }
    }

    // Auto-frame existing tight crop when opening an already-cropped variation in Step 1
    LaunchedEffect(fullMaskedBitmap != null, canvasPixelSize.width > 10f, step == WizardStep.CROP) {
        if (step == WizardStep.CROP && autoFocusCrop && edits.crop != null && viewZoom == 1f && viewPan == Offset.Zero) {
            focusCameraOntoCrop(edits.crop, animateTransition = false)
        }
    }

    fun applyAspectPreset(preset: CropAspect) {
        cropAspect = preset
        val ratio = preset.ratio ?: return
        val bmp = fullMaskedBitmap ?: return
        val imgAspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
        // Desired wNorm / hNorm so that (wNorm * bmp.w) / (hNorm * bmp.h) == ratio
        val normRatio = (ratio / imgAspect).coerceIn(0.05f, 20f)
        val cur = edits.crop ?: NRect.FULL
        val cx = (cur.left + cur.right) * 0.5f
        val cy = (cur.top + cur.bottom) * 0.5f
        var nw = cur.width
        var nh = nw / normRatio
        if (nh > cur.height) {
            nh = cur.height
            nw = nh * normRatio
        }
        nw = nw.coerceIn(0.04f, 1f)
        nh = (nw / normRatio).coerceIn(0.04f, 1f)
        if (nh * normRatio <= 1f) nw = nh * normRatio
        val nl = (cx - nw * 0.5f).coerceIn(0f, 1f - nw)
        val nt = (cy - nh * 0.5f).coerceIn(0f, 1f - nh)
        val nextCrop = NRect(nl, nt, nl + nw, nt + nh)
        pushEdits(edits.copy(crop = nextCrop))
        if (autoFocusCrop) focusCameraOntoCrop(nextCrop)
    }

    fun snapCropToCurrentZoom() {
        val bmp = fullMaskedBitmap ?: return
        val r = computeFitRect(bmp, canvasPixelSize, viewZoom, viewPan)
        val nl = ((0f - r.left) / r.width.coerceAtLeast(1f)).coerceIn(0f, 0.985f)
        val nt = ((0f - r.top) / r.height.coerceAtLeast(1f)).coerceIn(0f, 0.985f)
        val nr = ((canvasPixelSize.width - r.left) / r.width.coerceAtLeast(1f)).coerceIn(nl + 0.015f, 1f)
        val nb = ((canvasPixelSize.height - r.top) / r.height.coerceAtLeast(1f)).coerceIn(nt + 0.015f, 1f)
        val nextCrop = NRect(nl, nt, nr, nb)
        pushEdits(edits.copy(crop = nextCrop))
        wizardBanner = "Framed zoomed area!"
        if (autoFocusCrop) focusCameraOntoCrop(nextCrop)
    }

    fun setRectSweetSpot(localRect: NRect) {
        val activeCrop = edits.crop ?: NRect.FULL
        val l = (activeCrop.left + localRect.left * activeCrop.width).coerceIn(0f, 1f)
        val t = (activeCrop.top + localRect.top * activeCrop.height).coerceIn(0f, 1f)
        val r = (activeCrop.left + localRect.right * activeCrop.width).coerceIn(l + 0.01f, 1f)
        val b = (activeCrop.top + localRect.bottom * activeCrop.height).coerceIn(t + 0.01f, 1f)
        val poly = floatArrayOf(l, t, r, t, r, b, l, b)
        pushEdits(edits.copy(sweetOps = listOf(MaskOp.Lasso(LassoMode.KEEP, poly)), sweetConfigured = true))
    }

    fun runAutoCutout() {
        val orig = originalBmp ?: return
        aiBusy = true
        wizardBanner = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val activeCrop = edits.crop
                    if (activeCrop != null) {
                        val sub = ImageOps.crop(orig, activeCrop)
                        val subMask = Segmenter.segment(context, sub)
                        val fullMask = Bitmap.createBitmap(orig.width, orig.height, Bitmap.Config.ARGB_8888)
                        fullMask.setHasAlpha(true)
                        val dst = RectF(
                            activeCrop.left * orig.width,
                            activeCrop.top * orig.height,
                            activeCrop.right * orig.width,
                            activeCrop.bottom * orig.height,
                        )
                        AndroidCanvas(fullMask).drawBitmap(subMask, null, dst, null)
                        subMask.recycle()
                        fullMask
                    } else {
                        Segmenter.segment(context, orig)
                    }
                }
            }
            aiBusy = false
            result.onSuccess { maskBmp ->
                val maskId = UUID.randomUUID().toString().replace("-", "")
                newMasks[maskId] = maskBmp
                pushEdits(edits.copy(ops = edits.ops + MaskOp.AiCutout(maskId)))
                cutoutTool = CutoutDrawTool.ERASER_BRUSH
                wizardBanner = "Subject cut out! Clean up edges or tap Next to mark its Sweet Spot."
            }.onFailure {
                wizardBanner = "Couldn't auto-detect a subject — trace around it with Keep Loop."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamPalette.VoidBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // --- WIZARD TOP HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NeonHudIconButton(neon = neon, padding = PaddingValues(7.dp), onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = DreamPalette.SilverText, modifier = Modifier.size(17.dp))
            }

            // Clickable 4-step Wizard breadcrumb
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DreamPalette.AbyssGray)
                    .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                WizardStepPill("1.CROP", step == WizardStep.CROP, neon) { step = WizardStep.CROP }
                Text("›", color = DreamPalette.SteelMuted, fontSize = 11.sp)
                WizardStepPill("2.CUT", step == WizardStep.CUTOUT, neon) { step = WizardStep.CUTOUT }
                Text("›", color = DreamPalette.SteelMuted, fontSize = 11.sp)
                WizardStepPill("3.SWEET", step == WizardStep.SWEET_SPOT, sweetGold) { step = WizardStep.SWEET_SPOT }
                Text("›", color = DreamPalette.SteelMuted, fontSize = 11.sp)
                WizardStepPill("4.VARS(${siblingVariants.size})", step == WizardStep.REVIEW, neon) { step = WizardStep.REVIEW }
            }

            if (undoStack.isNotEmpty() || (step == WizardStep.CUTOUT && canUndoInCutout)) {
                NeonChip(
                    label = "↶ UNDO",
                    selected = false,
                    neon = neon,
                    onClick = { performUndo() },
                )
            }

            NeonHudIconButton(
                neon = neon,
                active = true,
                padding = PaddingValues(7.dp),
                onClick = { onSave(edits, newMasks.toMap()) },
            ) {
                Icon(Icons.Default.Check, contentDescription = "Save Now", tint = neon, modifier = Modifier.size(17.dp))
            }
        }

        // Plain-English Wizard guidance banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            val guidance = wizardBanner ?: when (step) {
                WizardStep.CROP -> "V#$variantIndex Crop Wizard — Drag corners (with live loupe) or slide box; Auto-Zoom frames your crop!"
                WizardStep.CUTOUT -> when (cutoutTool) {
                    CutoutDrawTool.KEEP_LOOP -> "KEEP LOOP: Draw a loop around what you want to keep (outside disappears)."
                    CutoutDrawTool.CUT_HOLE_LOOP -> "CUT HOLE: Circle any unwanted piece to cut it out to transparent."
                    CutoutDrawTool.ERASER_BRUSH -> "ERASER: Rub with 1 finger to erase background (2 fingers to zoom)."
                    CutoutDrawTool.RESTORE_BRUSH -> "RESTORE: Rub with 1 finger to bring back original pixels."
                }
                WizardStep.SWEET_SPOT -> "SWEET SPOT SHIELD: Paint or circle the part that MUST NOT be covered up in dense / Maximum Mode!"
                WizardStep.REVIEW -> "Variation #$variantIndex ready — save to shrine or spawn another variation!"
            }
            Text(
                text = guidance,
                color = when {
                    wizardBanner != null -> neon
                    step == WizardStep.SWEET_SPOT -> sweetGold
                    else -> DreamPalette.SilverText
                },
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 14.5.sp,
            )
        }

        Spacer(Modifier.height(5.dp))

        // --- MAIN STAGE AREA ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, if (step == WizardStep.SWEET_SPOT) sweetGold.copy(alpha = 0.55f) else DreamPalette.GraphiteLine, RoundedCornerShape(14.dp))
                .clipToBounds()
                .onSizeChanged { canvasPixelSize = Size(it.width.toFloat(), it.height.toFloat()) },
            contentAlignment = Alignment.Center,
        ) {
            when (step) {
                WizardStep.CROP -> {
                    TransparencyCheckerboard(modifier = Modifier.fillMaxSize())
                    val bmp = fullMaskedBitmap
                    if (bmp == null) {
                        CircularProgressIndicator(color = neon)
                    } else {
                        val imgAspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                        val lockedNormRatio = cropAspect.ratio?.let { (it / imgAspect).coerceIn(0.05f, 20f) }
                        Step1CropCanvas(
                            imageBitmap = bmp,
                            crop = edits.crop,
                            lockedNormAspect = lockedNormRatio,
                            neon = neon,
                            zoom = viewZoom,
                            pan = viewPan,
                            onTransformChange = { z, p ->
                                focusAnimJob?.cancel()
                                viewZoom = z
                                viewPan = p
                            },
                            onCropDragStarted = {
                                focusAnimJob?.cancel()
                                undoStack.add(edits)
                                redoStack.clear()
                            },
                            onCropChanged = { c ->
                                edits = edits.copy(crop = c)
                                wizardBanner = null
                            },
                            onCropDragFinished = {
                                if (autoFocusCrop) {
                                    focusCameraOntoCrop(edits.crop, animateTransition = true)
                                }
                            },
                        )
                    }
                }

                WizardStep.CUTOUT -> {
                    TransparencyCheckerboard(modifier = Modifier.fillMaxSize())
                    val bmp = croppedCutoutBitmap
                    val orig = originalBmp
                    if (bmp == null || orig == null) {
                        CircularProgressIndicator(color = neon)
                    } else {
                        val brushScreenPx = with(density) { brushSizeDp.dp.toPx() }
                        Step2CutoutCanvas(
                            croppedBitmap = bmp,
                            origW = orig.width,
                            origH = orig.height,
                            activeCrop = edits.crop ?: NRect.FULL,
                            drawTool = cutoutTool,
                            brushRadiusScreenPx = brushScreenPx,
                            neon = neon,
                            zoom = viewZoom,
                            pan = viewPan,
                            onTransformChange = { z, p -> viewZoom = z; viewPan = p },
                            onOpCommitted = { op ->
                                pushEdits(edits.copy(ops = edits.ops + op))
                                wizardBanner = null
                                if (op is MaskOp.Lasso && op.mode == LassoMode.KEEP) {
                                    cutoutTool = CutoutDrawTool.CUT_HOLE_LOOP
                                    wizardBanner = "Background removed! Circle leftover spots to cut holes, or tap Next for Sweet Spot."
                                }
                            },
                            onTooShortTap = {
                                wizardBanner = "Drag your finger in a loop around the area you want to cut."
                            },
                        )
                    }

                    if (originalHasNativeAlpha && edits.ops.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DreamPalette.VoidBlack.copy(alpha = 0.85f))
                                .border(1.dp, neon.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "NATIVE TRANSPARENT PNG DETECTED",
                                color = neon,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                WizardStep.SWEET_SPOT -> {
                    TransparencyCheckerboard(modifier = Modifier.fillMaxSize())
                    val bmp = croppedCutoutBitmap
                    val orig = originalBmp
                    if (bmp == null || orig == null) {
                        CircularProgressIndicator(color = sweetGold)
                    } else {
                        val brushScreenPx = with(density) { brushSizeDp.dp.toPx() }
                        Step3SweetSpotCanvas(
                            croppedBitmap = bmp,
                            sweetOverlayBitmap = croppedSweetBitmap,
                            sweetGridBits = liveSweetBits,
                            opaqueGridBits = liveOpaqueBits,
                            sweetConfigured = edits.sweetConfigured,
                            origW = orig.width,
                            origH = orig.height,
                            activeCrop = edits.crop ?: NRect.FULL,
                            tool = sweetTool,
                            brushRadiusScreenPx = brushScreenPx,
                            accentGold = sweetGold,
                            zoom = viewZoom,
                            pan = viewPan,
                            onTransformChange = { z, p -> viewZoom = z; viewPan = p },
                            onSweetOpCommitted = { op ->
                                pushEdits(
                                    edits.copy(
                                        sweetOps = edits.sweetOps + op,
                                        sweetConfigured = true,
                                    )
                                )
                                wizardBanner = "Sweet Spot updated! Protected cells won't be covered in Maximum / Dense mode."
                            },
                        )
                    }
                }

                WizardStep.REVIEW -> {
                    if (showReviewCheckerboard) {
                        TransparencyCheckerboard(modifier = Modifier.fillMaxSize())
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    }

                    val finalBmp = trimmedFinalBitmap
                    if (finalBmp == null) {
                        CircularProgressIndicator(color = neon)
                    } else {
                        Image(
                            bitmap = finalBmp,
                            contentDescription = "Final Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DreamPalette.VoidBlack.copy(alpha = 0.82f))
                            .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(8.dp))
                            .clickable { showReviewCheckerboard = !showReviewCheckerboard }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (showReviewCheckerboard) "VIEW ON PITCH BLACK" else "VIEW CHECKERBOARD",
                            color = DreamPalette.SilverText,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            if (aiBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DreamPalette.VoidBlack.copy(alpha = 0.76f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = neon)
                        Text(
                            text = "REMOVING BACKGROUND ON DEVICE...",
                            color = neon,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            if (viewZoom > 1.04f && step != WizardStep.REVIEW) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DreamPalette.VoidBlack.copy(alpha = 0.85f))
                        .border(1.dp, neon, RoundedCornerShape(8.dp))
                        .clickable {
                            focusAnimJob?.cancel()
                            viewZoom = 1f
                            viewPan = Offset.Zero
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Refresh, null, tint = neon, modifier = Modifier.size(13.dp))
                        Text("${(viewZoom * 100).roundToInt()}% SEE FULL", color = neon, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // --- STEP-SPECIFIC WIZARD CONTROLS ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (step) {
                WizardStep.CROP -> {
                    // Row 1: Zoom-to-Crop + Aspect Ratio Presets + Auto-Zoom toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (edits.crop != null) {
                            NeonChip(
                                label = "🔍 ZOOM CROP",
                                selected = false,
                                neon = neon,
                                onClick = { focusCameraOntoCrop(edits.crop, animateTransition = true) },
                            )
                        }
                        for (preset in CropAspect.entries) {
                            NeonChip(
                                label = preset.label,
                                selected = cropAspect == preset,
                                neon = neon,
                                onClick = { applyAspectPreset(preset) },
                            )
                        }
                        NeonChip(
                            label = if (autoFocusCrop) "AUTO-ZOOM: ON" else "AUTO-ZOOM: OFF",
                            selected = autoFocusCrop,
                            neon = neon,
                            onClick = {
                                autoFocusCrop = !autoFocusCrop
                                if (autoFocusCrop && edits.crop != null) focusCameraOntoCrop(edits.crop)
                            },
                        )
                    }

                    // Row 2: Quick Framing Actions + 1-Tap Save & New Crop Variation
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (viewZoom > 1.08f) {
                            NeonChip(
                                label = "SNAP TO VIEW",
                                selected = false,
                                neon = neon,
                                onClick = { snapCropToCurrentZoom() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (edits.crop != null) {
                            NeonChip(
                                label = "FULL PHOTO",
                                selected = false,
                                neon = DreamPalette.SilverText,
                                onClick = {
                                    cropAspect = CropAspect.FREE
                                    pushEdits(edits.copy(crop = null))
                                    focusAnimJob?.cancel()
                                    viewZoom = 1f
                                    viewPan = Offset.Zero
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        NeonChip(
                            label = "+ SAVE & CROP ANOTHER",
                            selected = false,
                            neon = DreamPalette.Neons[2].primary,
                            onClick = { onCreateVariation(false, edits, newMasks.toMap(), true) },
                            modifier = Modifier.weight(1.3f),
                        )
                    }

                    // Row 3: Primary navigation
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NeonButton(
                            text = "SAVE",
                            neon = DreamPalette.SilverText,
                            onClick = { onSave(edits, newMasks.toMap()) },
                        )
                        NeonButton(
                            text = "NEXT: CUTOUT →",
                            neon = neon,
                            filled = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                wizardBanner = null
                                step = WizardStep.CUTOUT
                            },
                        )
                    }
                }

                WizardStep.CUTOUT -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NeonChip(
                            label = "✨ AUTO AI",
                            selected = false,
                            neon = neon,
                            onClick = { if (!aiBusy) runAutoCutout() },
                            modifier = Modifier.weight(1f),
                        )
                        NeonChip(
                            label = "KEEP LOOP",
                            selected = cutoutTool == CutoutDrawTool.KEEP_LOOP,
                            neon = neon,
                            onClick = {
                                cutoutTool = CutoutDrawTool.KEEP_LOOP
                                wizardBanner = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        NeonChip(
                            label = "CUT HOLE",
                            selected = cutoutTool == CutoutDrawTool.CUT_HOLE_LOOP,
                            neon = DreamPalette.DangerNeon,
                            onClick = {
                                cutoutTool = CutoutDrawTool.CUT_HOLE_LOOP
                                wizardBanner = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        NeonChip(
                            label = "BRUSH",
                            selected = cutoutTool == CutoutDrawTool.ERASER_BRUSH || cutoutTool == CutoutDrawTool.RESTORE_BRUSH,
                            neon = DreamPalette.Neons[2].primary,
                            onClick = {
                                cutoutTool = CutoutDrawTool.ERASER_BRUSH
                                wizardBanner = null
                            },
                            modifier = Modifier.weight(0.9f),
                        )
                    }

                    if (cutoutTool == CutoutDrawTool.ERASER_BRUSH || cutoutTool == CutoutDrawTool.RESTORE_BRUSH) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(DreamPalette.AbyssGray)
                                .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NeonChip(
                                label = "ERASE",
                                selected = cutoutTool == CutoutDrawTool.ERASER_BRUSH,
                                neon = DreamPalette.DangerNeon,
                                onClick = { cutoutTool = CutoutDrawTool.ERASER_BRUSH },
                            )
                            NeonChip(
                                label = "RESTORE",
                                selected = cutoutTool == CutoutDrawTool.RESTORE_BRUSH,
                                neon = DreamPalette.Neons[2].primary,
                                onClick = { cutoutTool = CutoutDrawTool.RESTORE_BRUSH },
                            )
                            Slider(
                                value = brushSizeDp,
                                onValueChange = { brushSizeDp = it },
                                valueRange = 8f..54f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = neon,
                                    activeTrackColor = neon,
                                    inactiveTrackColor = DreamPalette.GraphiteLine,
                                ),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (canUndoInCutout) neon.copy(alpha = 0.14f) else DreamPalette.AbyssGray)
                                .border(
                                    1.dp,
                                    if (canUndoInCutout) neon.copy(alpha = 0.75f) else DreamPalette.GraphiteLine,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable(enabled = canUndoInCutout) { performUndo() }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "↶ UNDO",
                                color = if (canUndoInCutout) DreamPalette.PureWhite else DreamPalette.SteelMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (redoStack.isNotEmpty()) neon.copy(alpha = 0.14f) else DreamPalette.AbyssGray)
                                .border(
                                    1.dp,
                                    if (redoStack.isNotEmpty()) neon.copy(alpha = 0.75f) else DreamPalette.GraphiteLine,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable(enabled = redoStack.isNotEmpty()) { performRedo() }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "↷ REDO",
                                color = if (redoStack.isNotEmpty()) DreamPalette.PureWhite else DreamPalette.SteelMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1.1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (edits.ops.isNotEmpty()) DreamPalette.DangerNeon.copy(alpha = 0.14f) else DreamPalette.AbyssGray)
                                .border(
                                    1.dp,
                                    if (edits.ops.isNotEmpty()) DreamPalette.DangerNeon.copy(alpha = 0.70f) else DreamPalette.GraphiteLine,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable(enabled = edits.ops.isNotEmpty()) {
                                    pushEdits(edits.copy(ops = emptyList()))
                                    cutoutTool = CutoutDrawTool.KEEP_LOOP
                                    wizardBanner = "Cutout cleared — back to full pixels."
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "CLEAR CUT",
                                color = if (edits.ops.isNotEmpty()) DreamPalette.DangerNeon else DreamPalette.SteelMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NeonHudIconButton(
                            neon = neon,
                            padding = PaddingValues(horizontal = 11.dp, vertical = 10.dp),
                            onClick = {
                                wizardBanner = null
                                step = WizardStep.CROP
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DreamPalette.PureWhite, modifier = Modifier.size(15.dp))
                            Text("CROP", color = DreamPalette.PureWhite, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        NeonButton(
                            text = "NEXT: SWEET SPOT →",
                            neon = neon,
                            filled = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                wizardBanner = null
                                step = WizardStep.SWEET_SPOT
                            },
                        )
                    }
                }

                WizardStep.SWEET_SPOT -> {
                    // Tool selector: Paint Brush / Loop / Erase + Brush Size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NeonChip(
                            label = "★ PAINT SPOT",
                            selected = sweetTool == SweetSpotTool.PAINT_BRUSH,
                            neon = sweetGold,
                            onClick = { sweetTool = SweetSpotTool.PAINT_BRUSH },
                            modifier = Modifier.weight(1.1f),
                        )
                        NeonChip(
                            label = "LOOP SPOT",
                            selected = sweetTool == SweetSpotTool.LOOP_SPOT,
                            neon = sweetGold,
                            onClick = { sweetTool = SweetSpotTool.LOOP_SPOT },
                            modifier = Modifier.weight(1f),
                        )
                        NeonChip(
                            label = "ERASE",
                            selected = sweetTool == SweetSpotTool.ERASE_BRUSH,
                            neon = DreamPalette.DangerNeon,
                            onClick = { sweetTool = SweetSpotTool.ERASE_BRUSH },
                            modifier = Modifier.weight(0.85f),
                        )
                    }

                    if (sweetTool != SweetSpotTool.LOOP_SPOT) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(DreamPalette.AbyssGray)
                                .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("SIZE", color = sweetGold, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Slider(
                                value = brushSizeDp,
                                onValueChange = { brushSizeDp = it },
                                valueRange = 10f..60f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = sweetGold,
                                    activeTrackColor = sweetGold,
                                    inactiveTrackColor = DreamPalette.GraphiteLine,
                                ),
                            )
                        }
                    }

                    // Quick 1-Tap Presets: Auto Focal / Protect All / Allow Full Overlap (Filler)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NeonChip(
                            label = "AUTO FOCAL",
                            selected = !edits.sweetConfigured && edits.sweetOps.isEmpty(),
                            neon = sweetGold,
                            onClick = {
                                pushEdits(edits.copy(sweetOps = emptyList(), sweetConfigured = false))
                                wizardBanner = "Using smart Auto-Focal Sweet Spot protection."
                            },
                            modifier = Modifier.weight(1f),
                        )
                        NeonChip(
                            label = "PROTECT ALL",
                            selected = false,
                            neon = sweetGold,
                            onClick = {
                                setRectSweetSpot(NRect(0.04f, 0.04f, 0.96f, 0.96f))
                                wizardBanner = "Entire subject marked as Sweet Spot!"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        NeonChip(
                            label = "FILLER (OK TO COVER)",
                            selected = edits.sweetConfigured && edits.sweetOps.isEmpty(),
                            neon = DreamPalette.SilverText,
                            onClick = {
                                pushEdits(edits.copy(sweetOps = emptyList(), sweetConfigured = true))
                                wizardBanner = "Marked as background filler — other images can overlap freely."
                            },
                            modifier = Modifier.weight(1.25f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NeonHudIconButton(
                            neon = neon,
                            padding = PaddingValues(horizontal = 11.dp, vertical = 10.dp),
                            onClick = {
                                wizardBanner = null
                                step = WizardStep.CUTOUT
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DreamPalette.PureWhite, modifier = Modifier.size(15.dp))
                            Text("CUTOUT", color = DreamPalette.PureWhite, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        NeonButton(
                            text = "NEXT: VARIATIONS & SAVE →",
                            neon = neon,
                            filled = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                wizardBanner = null
                                step = WizardStep.REVIEW
                            },
                        )
                    }
                }

                WizardStep.REVIEW -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DreamPalette.AbyssGray)
                            .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "VARIATIONS:",
                            color = DreamPalette.SilverText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                        siblingVariants.forEachIndexed { idx, variant ->
                            val isCur = variant.id == imageId
                            VariationMiniThumb(
                                session = session,
                                entry = variant,
                                indexLabel = "V${idx + 1}",
                                selected = isCur,
                                neon = neon,
                                onClick = {
                                    if (!isCur) {
                                        onSwitchVariation(variant.id, edits, newMasks.toMap(), isDirty)
                                    }
                                },
                            )
                        }

                        if (siblingVariants.size > 1) {
                            Row(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DreamPalette.DangerNeon.copy(alpha = 0.14f))
                                    .border(1.dp, DreamPalette.DangerNeon.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .clickable(onClick = onDeleteVariation)
                                    .padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Default.Delete, null, tint = DreamPalette.DangerNeon, modifier = Modifier.size(14.dp))
                                Text("DEL V$variantIndex", color = DreamPalette.DangerNeon, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    NeonButton(
                        text = "+ ADD ANOTHER VARIATION FROM THIS IMAGE",
                        neon = neon,
                        onClick = { onCreateVariation(false, edits, newMasks.toMap(), isDirty) },
                        modifier = Modifier.fillMaxWidth(),
                        leading = { Icon(Icons.Default.Add, null, tint = DreamPalette.PureWhite, modifier = Modifier.size(16.dp)) },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NeonHudIconButton(
                            neon = neon,
                            padding = PaddingValues(horizontal = 11.dp, vertical = 10.dp),
                            onClick = { step = WizardStep.SWEET_SPOT },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DreamPalette.PureWhite, modifier = Modifier.size(15.dp))
                            Text("SWEET", color = sweetGold, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        NeonButton(
                            text = "FINISH & SAVE",
                            neon = neon,
                            filled = true,
                            modifier = Modifier.weight(1f),
                            leading = { Icon(Icons.Default.Check, null, tint = neon, modifier = Modifier.size(16.dp)) },
                            onClick = { onSave(edits, newMasks.toMap()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardStepPill(label: String, active: Boolean, neon: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) neon.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (active) neon else DreamPalette.SilverText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ============================================================================
// STEP 1 CANVAS: Simple, granular Crop & Pinch-Zoom
// ============================================================================

@Composable
private fun Step1CropCanvas(
    imageBitmap: ImageBitmap,
    crop: NRect?,
    lockedNormAspect: Float?,
    neon: Color,
    zoom: Float,
    pan: Offset,
    onTransformChange: (Float, Offset) -> Unit,
    onCropDragStarted: () -> Unit,
    onCropChanged: (NRect?) -> Unit,
    onCropDragFinished: () -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val fitPadPx = with(density) { 42.dp.toPx() }
    var activeDrag by remember { mutableStateOf(CropDragTarget.NONE) }
    var dragFingerPos by remember { mutableStateOf<Offset?>(null) }

    val latestBmpW by rememberUpdatedState(imageBitmap.width)
    val latestBmpH by rememberUpdatedState(imageBitmap.height)
    val latestCrop by rememberUpdatedState(crop)
    val latestLockedAspect by rememberUpdatedState(lockedNormAspect)
    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(pan)
    val latestOnCropChanged by rememberUpdatedState(onCropChanged)
    val latestOnCropDragStarted by rememberUpdatedState(onCropDragStarted)
    val latestOnCropDragFinished by rememberUpdatedState(onCropDragFinished)
    val latestOnTransformChange by rememberUpdatedState(onTransformChange)

    fun computeImageRect(canvasSize: Size, z: Float, p: Offset): Rect {
        val bw = latestBmpW.coerceAtLeast(1).toFloat()
        val bh = latestBmpH.coerceAtLeast(1).toFloat()
        val availW = (canvasSize.width - fitPadPx * 2f).coerceAtLeast(1f)
        val availH = (canvasSize.height - fitPadPx * 2f).coerceAtLeast(1f)
        val fitScale = min(availW / bw, availH / bh)
        val drawW = bw * fitScale * z
        val drawH = bh * fitScale * z
        val left = (canvasSize.width - drawW) * 0.5f + p.x
        val top = (canvasSize.height - drawH) * 0.5f + p.y
        return Rect(left, top, left + drawW, top + drawH)
    }

    // Offset horizontally away from edge midpoints so it NEVER blocks the top/bottom edge handles!
    fun computeMoveKnob(
        cl: Float,
        ct: Float,
        cr: Float,
        cb: Float,
        canvasW: Float,
        canvasH: Float,
        offsetPx: Float,
        marginPx: Float,
    ): Pair<Offset, Offset> {
        val cw = cr - cl
        val anchorX = if (cw >= marginPx * 4f) cl + cw * 0.22f else ((cl + cr) * 0.5f - offsetPx * 0.85f)
        val kx = anchorX.coerceIn(marginPx, (canvasW - marginPx).coerceAtLeast(marginPx))
        val belowY = cb + offsetPx
        val aboveY = ct - offsetPx
        return when {
            belowY <= canvasH - marginPx -> Offset(kx, belowY) to Offset(anchorX.coerceIn(cl, cr), cb)
            aboveY >= marginPx -> Offset(kx, aboveY) to Offset(anchorX.coerceIn(cl, cr), ct)
            else -> {
                val cy = ((ct + cb) * 0.5f).coerceIn(marginPx, (canvasH - marginPx).coerceAtLeast(marginPx))
                Offset((cl + cr) * 0.5f, cy) to Offset((cl + cr) * 0.5f, cy)
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .systemGestureExclusion()
            .pointerInput(Unit) {
                val cornerHit = 34.dp.toPx()
                val pillHit = 30.dp.toPx()
                val outerEdgeHit = 40.dp.toPx()
                val edgeSpanPad = 20.dp.toPx()
                val knobHit = 26.dp.toPx()
                val knobOffsetPx = 44.dp.toPx()
                val knobMarginPx = 20.dp.toPx()
                val minSpan = 0.015f

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var curZoom = latestZoom
                    var curPan = latestPan
                    var multiTouch = false

                    val canvasW = size.width.toFloat()
                    val canvasH = size.height.toFloat()
                    val startRect = computeImageRect(Size(canvasW, canvasH), curZoom, curPan)
                    val rawInitialCrop = latestCrop ?: NRect.FULL
                    val ratioLock = latestLockedAspect
                    val canMoveCrop = rawInitialCrop.width < 0.992f || rawInitialCrop.height < 0.992f
                    var historyPushed = false

                    val cLeft = startRect.left + rawInitialCrop.left * startRect.width
                    val cTop = startRect.top + rawInitialCrop.top * startRect.height
                    val cRight = startRect.left + rawInitialCrop.right * startRect.width
                    val cBottom = startRect.top + rawInitialCrop.bottom * startRect.height
                    val cw = cRight - cLeft
                    val ch = cBottom - cTop
                    val mx = (cLeft + cRight) * 0.5f
                    val my = (cTop + cBottom) * 0.5f
                    val (knobCenter, _) = computeMoveKnob(cLeft, cTop, cRight, cBottom, canvasW, canvasH, knobOffsetPx, knobMarginPx)

                    val p0 = down.position
                    val dKnob = hypot(p0.x - knobCenter.x, p0.y - knobCenter.y)
                    val dCenter = hypot(p0.x - mx, p0.y - my)
                    val centerMoveHit = min(22.dp.toPx(), min(cw, ch) * 0.22f).coerceAtLeast(10.dp.toPx())

                    val dTL = hypot(p0.x - cLeft, p0.y - cTop)
                    val dTR = hypot(p0.x - cRight, p0.y - cTop)
                    val dBL = hypot(p0.x - cLeft, p0.y - cBottom)
                    val dBR = hypot(p0.x - cRight, p0.y - cBottom)

                    val bestCorner = listOf(
                        CropDragTarget.TL to dTL,
                        CropDragTarget.TR to dTR,
                        CropDragTarget.BL to dBL,
                        CropDragTarget.BR to dBR,
                    ).minBy { it.second }

                    val bestPill = listOf(
                        CropDragTarget.TOP to (if (cw >= 24.dp.toPx()) hypot(p0.x - mx, p0.y - cTop) else Float.MAX_VALUE),
                        CropDragTarget.BOTTOM to (if (cw >= 24.dp.toPx()) hypot(p0.x - mx, p0.y - cBottom) else Float.MAX_VALUE),
                        CropDragTarget.LEFT to (if (ch >= 24.dp.toPx()) hypot(p0.x - cLeft, p0.y - my) else Float.MAX_VALUE),
                        CropDragTarget.RIGHT to (if (ch >= 24.dp.toPx()) hypot(p0.x - cRight, p0.y - my) else Float.MAX_VALUE),
                    ).minBy { it.second }

                    val innerEdgeHitX = if (!canMoveCrop) 44.dp.toPx() else min(22.dp.toPx(), cw * 0.22f).coerceAtLeast(12.dp.toPx())
                    val innerEdgeHitY = if (!canMoveCrop) 44.dp.toPx() else min(22.dp.toPx(), ch * 0.22f).coerceAtLeast(12.dp.toPx())

                    val scoreLeft = if (p0.y in (cTop - edgeSpanPad)..(cBottom + edgeSpanPad)) {
                        val dx = p0.x - cLeft
                        if (dx <= 0f) (-dx) / outerEdgeHit else dx / innerEdgeHitX
                    } else Float.MAX_VALUE

                    val scoreRight = if (p0.y in (cTop - edgeSpanPad)..(cBottom + edgeSpanPad)) {
                        val dx = p0.x - cRight
                        if (dx >= 0f) dx / outerEdgeHit else (-dx) / innerEdgeHitX
                    } else Float.MAX_VALUE

                    val scoreTop = if (p0.x in (cLeft - edgeSpanPad)..(cRight + edgeSpanPad)) {
                        val dy = p0.y - cTop
                        if (dy <= 0f) (-dy) / outerEdgeHit else dy / innerEdgeHitY
                    } else Float.MAX_VALUE

                    val scoreBottom = if (p0.x in (cLeft - edgeSpanPad)..(cRight + edgeSpanPad)) {
                        val dy = p0.y - cBottom
                        if (dy >= 0f) dy / outerEdgeHit else (-dy) / innerEdgeHitY
                    } else Float.MAX_VALUE

                    val bestEdge = listOf(
                        CropDragTarget.LEFT to scoreLeft,
                        CropDragTarget.RIGHT to scoreRight,
                        CropDragTarget.TOP to scoreTop,
                        CropDragTarget.BOTTOM to scoreBottom,
                    ).minBy { it.second }

                    val target = when {
                        dKnob <= knobHit && dKnob < bestPill.second && dKnob < bestCorner.second -> CropDragTarget.MOVE_KNOB
                        dCenter <= centerMoveHit && dCenter < bestPill.second && dCenter < bestCorner.second -> CropDragTarget.INSIDE
                        bestPill.second <= pillHit && bestPill.second < bestCorner.second -> bestPill.first
                        bestCorner.second <= cornerHit -> bestCorner.first
                        bestEdge.second <= 1.0f -> bestEdge.first
                        canMoveCrop && p0.x in cLeft..cRight && p0.y in cTop..cBottom -> CropDragTarget.INSIDE
                        !canMoveCrop && p0.x in cLeft..cRight && p0.y in cTop..cBottom -> bestEdge.first
                        else -> CropDragTarget.NONE
                    }

                    // If user grabs the Move Knob or Center Crosshair while box is still 100% full-frame,
                    // auto-detach the box to 78% so it can immediately slide freely!
                    val initialCrop = if ((target == CropDragTarget.MOVE_KNOB || target == CropDragTarget.INSIDE) && !canMoveCrop) {
                        NRect(0.11f, 0.11f, 0.89f, 0.89f)
                    } else {
                        rawInitialCrop
                    }

                    if (target != CropDragTarget.NONE) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }

                    activeDrag = target
                    dragFingerPos = p0
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                multiTouch = true
                                activeDrag = CropDragTarget.NONE
                                dragFingerPos = null
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val newZoom = (curZoom * zoomChange).coerceIn(1f, 10f)
                                val ratio = newZoom / curZoom
                                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                                val rawPan = (curPan + centroid - center) * ratio - (centroid - center) + panChange
                                val nextRect = computeImageRect(Size(canvasW, canvasH), newZoom, Offset.Zero)
                                val maxPanX = max(canvasW * 0.45f, nextRect.width * 0.5f)
                                val maxPanY = max(canvasH * 0.45f, nextRect.height * 0.5f)
                                curZoom = newZoom
                                curPan = if (newZoom <= 1.01f) Offset.Zero else Offset(
                                    rawPan.x.coerceIn(-maxPanX, maxPanX),
                                    rawPan.y.coerceIn(-maxPanY, maxPanY),
                                )
                                latestOnTransformChange(curZoom, curPan)
                                event.changes.forEach { it.consume() }
                            } else if (!multiTouch) {
                                val ptr = event.changes.firstOrNull { it.id == down.id } ?: pressed.first()
                                dragFingerPos = ptr.position
                                if (target != CropDragTarget.NONE) {
                                    val movedPx = hypot(ptr.position.x - p0.x, ptr.position.y - p0.y)
                                    if (movedPx > 0.5f || historyPushed) {
                                        if (!historyPushed) {
                                            latestOnCropDragStarted()
                                            historyPushed = true
                                        }
                                        val totalDx = (ptr.position.x - p0.x) / startRect.width.coerceAtLeast(1f)
                                        val totalDy = (ptr.position.y - p0.y) / startRect.height.coerceAtLeast(1f)

                                        val updatedCrop = if (ratioLock != null && target != CropDragTarget.INSIDE && target != CropDragTarget.MOVE_KNOB && target != CropDragTarget.NONE) {
                                            val r = ratioLock.coerceIn(0.05f, 20f)
                                            val minW = max(minSpan, minSpan * r)
                                            val minH = max(minSpan, minSpan / r)
                                            when (target) {
                                                CropDragTarget.BR -> {
                                                    val ax = initialCrop.left
                                                    val ay = initialCrop.top
                                                    val rawW = (initialCrop.right + totalDx) - ax
                                                    val rawH = (initialCrop.bottom + totalDy) - ay
                                                    val maxW = min(1f - ax, (1f - ay) * r)
                                                    val w = ((rawW + rawH * r) * 0.5f).coerceIn(minW, maxW.coerceAtLeast(minW))
                                                    val h = (w / r).coerceIn(minSpan, (1f - ay).coerceAtLeast(minSpan))
                                                    NRect(ax, ay, (ax + w).coerceAtMost(1f), (ay + h).coerceAtMost(1f))
                                                }
                                                CropDragTarget.TL -> {
                                                    val ax = initialCrop.right
                                                    val ay = initialCrop.bottom
                                                    val rawW = ax - (initialCrop.left + totalDx)
                                                    val rawH = ay - (initialCrop.top + totalDy)
                                                    val maxW = min(ax, ay * r)
                                                    val w = ((rawW + rawH * r) * 0.5f).coerceIn(minW, maxW.coerceAtLeast(minW))
                                                    val h = (w / r).coerceIn(minSpan, ay.coerceAtLeast(minSpan))
                                                    NRect((ax - w).coerceAtLeast(0f), (ay - h).coerceAtLeast(0f), ax, ay)
                                                }
                                                CropDragTarget.TR -> {
                                                    val ax = initialCrop.left
                                                    val ay = initialCrop.bottom
                                                    val rawW = (initialCrop.right + totalDx) - ax
                                                    val rawH = ay - (initialCrop.top + totalDy)
                                                    val maxW = min(1f - ax, ay * r)
                                                    val w = ((rawW + rawH * r) * 0.5f).coerceIn(minW, maxW.coerceAtLeast(minW))
                                                    val h = (w / r).coerceIn(minSpan, ay.coerceAtLeast(minSpan))
                                                    NRect(ax, (ay - h).coerceAtLeast(0f), (ax + w).coerceAtMost(1f), ay)
                                                }
                                                CropDragTarget.BL -> {
                                                    val ax = initialCrop.right
                                                    val ay = initialCrop.top
                                                    val rawW = ax - (initialCrop.left + totalDx)
                                                    val rawH = (initialCrop.bottom + totalDy) - ay
                                                    val maxW = min(ax, (1f - ay) * r)
                                                    val w = ((rawW + rawH * r) * 0.5f).coerceIn(minW, maxW.coerceAtLeast(minW))
                                                    val h = (w / r).coerceIn(minSpan, (1f - ay).coerceAtLeast(minSpan))
                                                    NRect((ax - w).coerceAtLeast(0f), ay, ax, (ay + h).coerceAtMost(1f))
                                                }
                                                CropDragTarget.LEFT -> {
                                                    val ax = initialCrop.right
                                                    val cy = (initialCrop.top + initialCrop.bottom) * 0.5f
                                                    val maxW = min(ax, r)
                                                    val w = (ax - (initialCrop.left + totalDx)).coerceIn(minW, maxW.coerceAtLeast(minW))
                                                    val h = (w / r).coerceIn(minSpan, 1f)
                                                    val nt = (cy - h * 0.5f).coerceIn(0f, (1f - h).coerceAtLeast(0f))
                                                    NRect((ax - w).coerceAtLeast(0f), nt, ax, (nt + h).coerceAtMost(1f))
                                                }
                                                CropDragTarget.RIGHT -> {
                                                    val ax = initialCrop.left
                                                    val cy = (initialCrop.top + initialCrop.bottom) * 0.5f
                                                    val maxW = min(1f - ax, r)
                                                    val w = ((initialCrop.right + totalDx) - ax).coerceIn(minW, maxW.coerceAtLeast(minW))
                                                    val h = (w / r).coerceIn(minSpan, 1f)
                                                    val nt = (cy - h * 0.5f).coerceIn(0f, (1f - h).coerceAtLeast(0f))
                                                    NRect(ax, nt, (ax + w).coerceAtMost(1f), (nt + h).coerceAtMost(1f))
                                                }
                                                CropDragTarget.TOP -> {
                                                    val ay = initialCrop.bottom
                                                    val cx = (initialCrop.left + initialCrop.right) * 0.5f
                                                    val maxH = min(ay, 1f / r)
                                                    val h = (ay - (initialCrop.top + totalDy)).coerceIn(minH, maxH.coerceAtLeast(minH))
                                                    val w = (h * r).coerceIn(minSpan, 1f)
                                                    val nl = (cx - w * 0.5f).coerceIn(0f, (1f - w).coerceAtLeast(0f))
                                                    NRect(nl, (ay - h).coerceAtLeast(0f), (nl + w).coerceAtMost(1f), ay)
                                                }
                                                CropDragTarget.BOTTOM -> {
                                                    val ay = initialCrop.top
                                                    val cx = (initialCrop.left + initialCrop.right) * 0.5f
                                                    val maxH = min(1f - ay, 1f / r)
                                                    val h = ((initialCrop.bottom + totalDy) - ay).coerceIn(minH, maxH.coerceAtLeast(minH))
                                                    val w = (h * r).coerceIn(minSpan, 1f)
                                                    val nl = (cx - w * 0.5f).coerceIn(0f, (1f - w).coerceAtLeast(0f))
                                                    NRect(nl, ay, (nl + w).coerceAtMost(1f), (ay + h).coerceAtMost(1f))
                                                }
                                                else -> initialCrop
                                            }
                                        } else {
                                            when (target) {
                                                CropDragTarget.TL -> initialCrop.copy(
                                                    left = (initialCrop.left + totalDx).coerceIn(0f, initialCrop.right - minSpan),
                                                    top = (initialCrop.top + totalDy).coerceIn(0f, initialCrop.bottom - minSpan),
                                                )
                                                CropDragTarget.TR -> initialCrop.copy(
                                                    right = (initialCrop.right + totalDx).coerceIn(initialCrop.left + minSpan, 1f),
                                                    top = (initialCrop.top + totalDy).coerceIn(0f, initialCrop.bottom - minSpan),
                                                )
                                                CropDragTarget.BL -> initialCrop.copy(
                                                    left = (initialCrop.left + totalDx).coerceIn(0f, initialCrop.right - minSpan),
                                                    bottom = (initialCrop.bottom + totalDy).coerceIn(initialCrop.top + minSpan, 1f),
                                                )
                                                CropDragTarget.BR -> initialCrop.copy(
                                                    right = (initialCrop.right + totalDx).coerceIn(initialCrop.left + minSpan, 1f),
                                                    bottom = (initialCrop.bottom + totalDy).coerceIn(initialCrop.top + minSpan, 1f),
                                                )
                                                CropDragTarget.LEFT -> initialCrop.copy(
                                                    left = (initialCrop.left + totalDx).coerceIn(0f, initialCrop.right - minSpan),
                                                )
                                                CropDragTarget.RIGHT -> initialCrop.copy(
                                                    right = (initialCrop.right + totalDx).coerceIn(initialCrop.left + minSpan, 1f),
                                                )
                                                CropDragTarget.TOP -> initialCrop.copy(
                                                    top = (initialCrop.top + totalDy).coerceIn(0f, initialCrop.bottom - minSpan),
                                                )
                                                CropDragTarget.BOTTOM -> initialCrop.copy(
                                                    bottom = (initialCrop.bottom + totalDy).coerceIn(initialCrop.top + minSpan, 1f),
                                                )
                                                CropDragTarget.INSIDE, CropDragTarget.MOVE_KNOB -> {
                                                    val wSpan = initialCrop.width
                                                    val hSpan = initialCrop.height
                                                    val nl = (initialCrop.left + totalDx).coerceIn(0f, 1f - wSpan)
                                                    val nt = (initialCrop.top + totalDy).coerceIn(0f, 1f - hSpan)
                                                    NRect(nl, nt, nl + wSpan, nt + hSpan)
                                                }
                                                CropDragTarget.NONE -> initialCrop
                                            }
                                        }
                                        latestOnCropChanged(updatedCrop)
                                    }
                                    ptr.consume()
                                } else if (curZoom > 1.02f) {
                                    val dp = ptr.position - ptr.previousPosition
                                    val curRect = computeImageRect(Size(canvasW, canvasH), curZoom, Offset.Zero)
                                    val maxPanX = max(canvasW * 0.45f, curRect.width * 0.5f)
                                    val maxPanY = max(canvasH * 0.45f, curRect.height * 0.5f)
                                    curPan = Offset(
                                        (curPan.x + dp.x).coerceIn(-maxPanX, maxPanX),
                                        (curPan.y + dp.y).coerceIn(-maxPanY, maxPanY),
                                    )
                                    latestOnTransformChange(curZoom, curPan)
                                    ptr.consume()
                                }
                            }
                        }
                        if (historyPushed && !multiTouch && target != CropDragTarget.NONE) {
                            latestOnCropDragFinished()
                        }
                    } finally {
                        activeDrag = CropDragTarget.NONE
                        dragFingerPos = null
                    }
                }
            }
    ) {
        val imgRect = computeImageRect(size, zoom, pan)
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(imgRect.left.roundToInt(), imgRect.top.roundToInt()),
            dstSize = IntSize(imgRect.width.roundToInt().coerceAtLeast(1), imgRect.height.roundToInt().coerceAtLeast(1)),
        )

        val activeCrop = crop ?: NRect.FULL
        val cl = imgRect.left + activeCrop.left * imgRect.width
        val ct = imgRect.top + activeCrop.top * imgRect.height
        val cr = imgRect.left + activeCrop.right * imgRect.width
        val cb = imgRect.top + activeCrop.bottom * imgRect.height

        val veil = DreamPalette.VoidBlack.copy(alpha = 0.64f)
        drawRect(veil, Offset(imgRect.left, imgRect.top), Size(imgRect.width, (ct - imgRect.top).coerceAtLeast(0f)))
        drawRect(veil, Offset(imgRect.left, cb), Size(imgRect.width, (imgRect.bottom - cb).coerceAtLeast(0f)))
        drawRect(veil, Offset(imgRect.left, ct), Size((cl - imgRect.left).coerceAtLeast(0f), (cb - ct).coerceAtLeast(0f)))
        drawRect(veil, Offset(cr, ct), Size((imgRect.right - cr).coerceAtLeast(0f), (cb - ct).coerceAtLeast(0f)))

        val cw = cr - cl
        val ch = cb - ct
        val canMoveCrop = activeCrop.width < 0.992f || activeCrop.height < 0.992f
        val isMovingBox = activeDrag == CropDragTarget.INSIDE || activeDrag == CropDragTarget.MOVE_KNOB

        if (isMovingBox) {
            drawRect(neon.copy(alpha = 0.10f), Offset(cl, ct), Size(cw, ch))
        }

        val guide = neon.copy(alpha = if (isMovingBox) 0.42f else 0.25f)
        drawLine(guide, Offset(cl + cw / 3f, ct), Offset(cl + cw / 3f, cb), 1.dp.toPx())
        drawLine(guide, Offset(cl + cw * 2f / 3f, ct), Offset(cl + cw * 2f / 3f, cb), 1.dp.toPx())
        drawLine(guide, Offset(cl, ct + ch / 3f), Offset(cr, ct + ch / 3f), 1.dp.toPx())
        drawLine(guide, Offset(cl, ct + ch * 2f / 3f), Offset(cr, ct + ch * 2f / 3f), 1.dp.toPx())

        drawRect(
            color = if (isMovingBox) DreamPalette.PureWhite else neon,
            topLeft = Offset(cl, ct),
            size = Size(cw, ch),
            style = Stroke(width = if (isMovingBox) 2.5.dp.toPx() else 2.dp.toPx()),
        )

        // Bright active highlight along whichever edge is currently being dragged
        when (activeDrag) {
            CropDragTarget.TOP -> drawLine(DreamPalette.PureWhite, Offset(cl, ct), Offset(cr, ct), 3.5.dp.toPx(), StrokeCap.Round)
            CropDragTarget.BOTTOM -> drawLine(DreamPalette.PureWhite, Offset(cl, cb), Offset(cr, cb), 3.5.dp.toPx(), StrokeCap.Round)
            CropDragTarget.LEFT -> drawLine(DreamPalette.PureWhite, Offset(cl, ct), Offset(cl, cb), 3.5.dp.toPx(), StrokeCap.Round)
            CropDragTarget.RIGHT -> drawLine(DreamPalette.PureWhite, Offset(cr, ct), Offset(cr, cb), 3.5.dp.toPx(), StrokeCap.Round)
            else -> Unit
        }

        val mx = (cl + cr) * 0.5f
        val my = (ct + cb) * 0.5f

        // Center interior move crosshair handle
        if (cw >= 52.dp.toPx() && ch >= 52.dp.toPx()) {
            val crossHalf = 8.dp.toPx()
            val crossColor = if (isMovingBox) neon else DreamPalette.PureWhite.copy(alpha = 0.80f)
            drawCircle(DreamPalette.VoidBlack.copy(alpha = 0.65f), radius = 14.dp.toPx(), center = Offset(mx, my))
            drawCircle(if (isMovingBox) neon else neon.copy(alpha = 0.55f), radius = 14.dp.toPx(), center = Offset(mx, my), style = Stroke(width = 1.5.dp.toPx()))
            drawLine(crossColor, Offset(mx - crossHalf, my), Offset(mx + crossHalf, my), 2.dp.toPx(), StrokeCap.Round)
            drawLine(crossColor, Offset(mx, my - crossHalf), Offset(mx, my + crossHalf), 2.dp.toPx(), StrokeCap.Round)
        }

        // Prominent tactile Edge Grab Capsules on all 4 sides
        if (cw >= 24.dp.toPx()) {
            val pillHalf = (min(34.dp.toPx(), cw * 0.42f).coerceAtLeast(16.dp.toPx())) * 0.5f
            val gripHalf = pillHalf * 0.52f

            val topActive = activeDrag == CropDragTarget.TOP
            val topHalfThick = if (topActive) 6.5.dp.toPx() else 5.5.dp.toPx()
            drawRoundRect(
                color = if (topActive) neon else DreamPalette.VoidBlack.copy(alpha = 0.92f),
                topLeft = Offset(mx - pillHalf, ct - topHalfThick),
                size = Size(pillHalf * 2f, topHalfThick * 2f),
                cornerRadius = CornerRadius(topHalfThick),
            )
            drawRoundRect(
                color = if (topActive) DreamPalette.PureWhite else neon,
                topLeft = Offset(mx - pillHalf, ct - topHalfThick),
                size = Size(pillHalf * 2f, topHalfThick * 2f),
                cornerRadius = CornerRadius(topHalfThick),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawLine(
                color = if (topActive) DreamPalette.VoidBlack else DreamPalette.PureWhite,
                start = Offset(mx - gripHalf, ct),
                end = Offset(mx + gripHalf, ct),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val botActive = activeDrag == CropDragTarget.BOTTOM
            val botHalfThick = if (botActive) 6.5.dp.toPx() else 5.5.dp.toPx()
            drawRoundRect(
                color = if (botActive) neon else DreamPalette.VoidBlack.copy(alpha = 0.92f),
                topLeft = Offset(mx - pillHalf, cb - botHalfThick),
                size = Size(pillHalf * 2f, botHalfThick * 2f),
                cornerRadius = CornerRadius(botHalfThick),
            )
            drawRoundRect(
                color = if (botActive) DreamPalette.PureWhite else neon,
                topLeft = Offset(mx - pillHalf, cb - botHalfThick),
                size = Size(pillHalf * 2f, botHalfThick * 2f),
                cornerRadius = CornerRadius(botHalfThick),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawLine(
                color = if (botActive) DreamPalette.VoidBlack else DreamPalette.PureWhite,
                start = Offset(mx - gripHalf, cb),
                end = Offset(mx + gripHalf, cb),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (ch >= 24.dp.toPx()) {
            val pillHalf = (min(34.dp.toPx(), ch * 0.42f).coerceAtLeast(16.dp.toPx())) * 0.5f
            val gripHalf = pillHalf * 0.52f

            val leftActive = activeDrag == CropDragTarget.LEFT
            val leftHalfThick = if (leftActive) 6.5.dp.toPx() else 5.5.dp.toPx()
            drawRoundRect(
                color = if (leftActive) neon else DreamPalette.VoidBlack.copy(alpha = 0.92f),
                topLeft = Offset(cl - leftHalfThick, my - pillHalf),
                size = Size(leftHalfThick * 2f, pillHalf * 2f),
                cornerRadius = CornerRadius(leftHalfThick),
            )
            drawRoundRect(
                color = if (leftActive) DreamPalette.PureWhite else neon,
                topLeft = Offset(cl - leftHalfThick, my - pillHalf),
                size = Size(leftHalfThick * 2f, pillHalf * 2f),
                cornerRadius = CornerRadius(leftHalfThick),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawLine(
                color = if (leftActive) DreamPalette.VoidBlack else DreamPalette.PureWhite,
                start = Offset(cl, my - gripHalf),
                end = Offset(cl, my + gripHalf),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val rightActive = activeDrag == CropDragTarget.RIGHT
            val rightHalfThick = if (rightActive) 6.5.dp.toPx() else 5.5.dp.toPx()
            drawRoundRect(
                color = if (rightActive) neon else DreamPalette.VoidBlack.copy(alpha = 0.92f),
                topLeft = Offset(cr - rightHalfThick, my - pillHalf),
                size = Size(rightHalfThick * 2f, pillHalf * 2f),
                cornerRadius = CornerRadius(rightHalfThick),
            )
            drawRoundRect(
                color = if (rightActive) DreamPalette.PureWhite else neon,
                topLeft = Offset(cr - rightHalfThick, my - pillHalf),
                size = Size(rightHalfThick * 2f, pillHalf * 2f),
                cornerRadius = CornerRadius(rightHalfThick),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawLine(
                color = if (rightActive) DreamPalette.VoidBlack else DreamPalette.PureWhite,
                start = Offset(cr, my - gripHalf),
                end = Offset(cr, my + gripHalf),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Corner L-brackets + solid touch-node circles
        val hLen = min(18.dp.toPx(), min(cw, ch) * 0.34f).coerceAtLeast(6.dp.toPx())
        val hStroke = 3.5.dp.toPx()
        drawLine(neon, Offset(cl, ct), Offset(cl + hLen, ct), hStroke, StrokeCap.Round)
        drawLine(neon, Offset(cl, ct), Offset(cl, ct + hLen), hStroke, StrokeCap.Round)
        drawLine(neon, Offset(cr, ct), Offset(cr - hLen, ct), hStroke, StrokeCap.Round)
        drawLine(neon, Offset(cr, ct), Offset(cr, ct + hLen), hStroke, StrokeCap.Round)
        drawLine(neon, Offset(cl, cb), Offset(cl + hLen, cb), hStroke, StrokeCap.Round)
        drawLine(neon, Offset(cl, cb), Offset(cl, cb - hLen), hStroke, StrokeCap.Round)
        drawLine(neon, Offset(cr, cb), Offset(cr - hLen, cb), hStroke, StrokeCap.Round)
        drawLine(neon, Offset(cr, cb), Offset(cr, cb - hLen), hStroke, StrokeCap.Round)

        val corners = listOf(
            Offset(cl, ct) to (activeDrag == CropDragTarget.TL),
            Offset(cr, ct) to (activeDrag == CropDragTarget.TR),
            Offset(cl, cb) to (activeDrag == CropDragTarget.BL),
            Offset(cr, cb) to (activeDrag == CropDragTarget.BR),
        )
        corners.forEach { (pt, grabbed) ->
            val r = if (grabbed) 11.dp.toPx() else 8.dp.toPx()
            drawCircle(DreamPalette.VoidBlack, radius = r, center = pt)
            drawCircle(if (grabbed) DreamPalette.PureWhite else neon, radius = r, center = pt, style = Stroke(width = 2.5.dp.toPx()))
            drawCircle(DreamPalette.PureWhite, radius = 2.4.dp.toPx(), center = pt)
        }

        // Tethered floating ✥ Move Handle (offset 44.dp away from edge midpoints)
        run {
            val (knobCenter, knobAnchor) = computeMoveKnob(
                cl, ct, cr, cb, size.width, size.height, 44.dp.toPx(), 20.dp.toPx()
            )
            if (hypot(knobCenter.x - knobAnchor.x, knobCenter.y - knobAnchor.y) > 4.dp.toPx()) {
                drawLine(
                    color = neon.copy(alpha = 0.65f),
                    start = knobAnchor,
                    end = knobCenter,
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                )
            }
            val knobGrabbed = activeDrag == CropDragTarget.MOVE_KNOB
            val knobRadius = if (knobGrabbed) 17.dp.toPx() else 14.dp.toPx()
            drawCircle(
                color = if (knobGrabbed) neon else DreamPalette.AbyssGray.copy(alpha = 0.94f),
                radius = knobRadius,
                center = knobCenter,
            )
            drawCircle(
                color = if (knobGrabbed) DreamPalette.PureWhite else neon,
                radius = knobRadius,
                center = knobCenter,
                style = Stroke(width = 2.dp.toPx()),
            )
            val arm = 6.dp.toPx()
            val tip = 2.6.dp.toPx()
            val iconCol = if (knobGrabbed) DreamPalette.VoidBlack else DreamPalette.PureWhite
            drawLine(iconCol, Offset(knobCenter.x - arm, knobCenter.y), Offset(knobCenter.x + arm, knobCenter.y), 2.dp.toPx(), StrokeCap.Round)
            drawLine(iconCol, Offset(knobCenter.x, knobCenter.y - arm), Offset(knobCenter.x, knobCenter.y + arm), 2.dp.toPx(), StrokeCap.Round)
            // 4 directional arrowheads on the move knob
            drawLine(iconCol, Offset(knobCenter.x - arm, knobCenter.y), Offset(knobCenter.x - arm + tip, knobCenter.y - tip), 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(iconCol, Offset(knobCenter.x - arm, knobCenter.y), Offset(knobCenter.x - arm + tip, knobCenter.y + tip), 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(iconCol, Offset(knobCenter.x + arm, knobCenter.y), Offset(knobCenter.x + arm - tip, knobCenter.y - tip), 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(iconCol, Offset(knobCenter.x + arm, knobCenter.y), Offset(knobCenter.x + arm - tip, knobCenter.y + tip), 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(iconCol, Offset(knobCenter.x, knobCenter.y - arm), Offset(knobCenter.x - tip, knobCenter.y - arm + tip), 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(iconCol, Offset(knobCenter.x, knobCenter.y - arm), Offset(knobCenter.x + tip, knobCenter.y - arm + tip), 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(iconCol, Offset(knobCenter.x, knobCenter.y + arm), Offset(knobCenter.x - tip, knobCenter.y + arm - tip), 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(iconCol, Offset(knobCenter.x, knobCenter.y + arm), Offset(knobCenter.x + tip, knobCenter.y + arm - tip), 1.8.dp.toPx(), StrokeCap.Round)
        }

        // Live 2.8x Precision Magnifier Loupe when dragging corner/edge resize handles
        val finger = dragFingerPos
        val isResizeHandle = activeDrag == CropDragTarget.TL ||
            activeDrag == CropDragTarget.TR ||
            activeDrag == CropDragTarget.BL ||
            activeDrag == CropDragTarget.BR ||
            activeDrag == CropDragTarget.LEFT ||
            activeDrag == CropDragTarget.RIGHT ||
            activeDrag == CropDragTarget.TOP ||
            activeDrag == CropDragTarget.BOTTOM
        if (isResizeHandle && finger != null) {
            val fingerNormX = ((finger.x - imgRect.left) / imgRect.width.coerceAtLeast(1f)).coerceIn(activeCrop.left, activeCrop.right)
            val fingerNormY = ((finger.y - imgRect.top) / imgRect.height.coerceAtLeast(1f)).coerceIn(activeCrop.top, activeCrop.bottom)
            val (focusNormX, focusNormY) = when (activeDrag) {
                CropDragTarget.TL -> activeCrop.left to activeCrop.top
                CropDragTarget.TR -> activeCrop.right to activeCrop.top
                CropDragTarget.BL -> activeCrop.left to activeCrop.bottom
                CropDragTarget.BR -> activeCrop.right to activeCrop.bottom
                CropDragTarget.LEFT -> activeCrop.left to fingerNormY
                CropDragTarget.RIGHT -> activeCrop.right to fingerNormY
                CropDragTarget.TOP -> fingerNormX to activeCrop.top
                CropDragTarget.BOTTOM -> fingerNormX to activeCrop.bottom
                else -> 0.5f to 0.5f
            }

            val loupeRadius = 52.dp.toPx()
            val loupePad = 14.dp.toPx()
            val leftSlot = Offset(loupePad + loupeRadius, loupePad + loupeRadius)
            val rightSlot = Offset(size.width - loupePad - loupeRadius, loupePad + loupeRadius)
            var loupeCenter = if (finger.x > size.width * 0.5f) leftSlot else rightSlot
            if (hypot(finger.x - loupeCenter.x, finger.y - loupeCenter.y) < loupeRadius * 2.1f) {
                loupeCenter = if (loupeCenter == leftSlot) rightSlot else leftSlot
            }

            val mag = 2.8f
            val magW = imgRect.width * mag
            val magH = imgRect.height * mag
            val magLeft = loupeCenter.x - focusNormX * magW
            val magTop = loupeCenter.y - focusNormY * magH

            val mcl = magLeft + activeCrop.left * magW
            val mct = magTop + activeCrop.top * magH
            val mcr = magLeft + activeCrop.right * magW
            val mcb = magTop + activeCrop.bottom * magH

            val loupeClip = Path().apply {
                addOval(
                    Rect(
                        loupeCenter.x - loupeRadius,
                        loupeCenter.y - loupeRadius,
                        loupeCenter.x + loupeRadius,
                        loupeCenter.y + loupeRadius,
                    )
                )
            }

            // Outer shadow halo
            drawCircle(DreamPalette.VoidBlack.copy(alpha = 0.72f), radius = loupeRadius + 4.dp.toPx(), center = loupeCenter)

            clipPath(loupeClip) {
                drawRect(
                    color = Color(0xFF10131A),
                    topLeft = Offset(loupeCenter.x - loupeRadius, loupeCenter.y - loupeRadius),
                    size = Size(loupeRadius * 2f, loupeRadius * 2f),
                )
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(magLeft.roundToInt(), magTop.roundToInt()),
                    dstSize = IntSize(magW.roundToInt().coerceAtLeast(1), magH.roundToInt().coerceAtLeast(1)),
                )
                val lVeil = DreamPalette.VoidBlack.copy(alpha = 0.62f)
                drawRect(lVeil, Offset(magLeft, magTop), Size(magW, (mct - magTop).coerceAtLeast(0f)))
                drawRect(lVeil, Offset(magLeft, mcb), Size(magW, (magTop + magH - mcb).coerceAtLeast(0f)))
                drawRect(lVeil, Offset(magLeft, mct), Size((mcl - magLeft).coerceAtLeast(0f), (mcb - mct).coerceAtLeast(0f)))
                drawRect(lVeil, Offset(mcr, mct), Size((magLeft + magW - mcr).coerceAtLeast(0f), (mcb - mct).coerceAtLeast(0f)))

                drawRect(
                    color = neon,
                    topLeft = Offset(mcl, mct),
                    size = Size((mcr - mcl).coerceAtLeast(1f), (mcb - mct).coerceAtLeast(1f)),
                    style = Stroke(width = 2.dp.toPx()),
                )

                val chArm = 10.dp.toPx()
                drawLine(DreamPalette.PureWhite.copy(alpha = 0.90f), Offset(loupeCenter.x - chArm, loupeCenter.y), Offset(loupeCenter.x + chArm, loupeCenter.y), 1.3.dp.toPx())
                drawLine(DreamPalette.PureWhite.copy(alpha = 0.90f), Offset(loupeCenter.x, loupeCenter.y - chArm), Offset(loupeCenter.x, loupeCenter.y + chArm), 1.3.dp.toPx())
            }

            drawCircle(neon, radius = loupeRadius, center = loupeCenter, style = Stroke(width = 2.5.dp.toPx()))
            drawCircle(DreamPalette.PureWhite.copy(alpha = 0.85f), radius = loupeRadius - 2.dp.toPx(), center = loupeCenter, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

// ============================================================================
// STEP 2 CANVAS: Zoomed-onto-crop Lasso Loop + Finger Eraser/Restore Brush
// ============================================================================

@Composable
private fun Step2CutoutCanvas(
    croppedBitmap: ImageBitmap,
    origW: Int,
    origH: Int,
    activeCrop: NRect,
    drawTool: CutoutDrawTool,
    brushRadiusScreenPx: Float,
    neon: Color,
    zoom: Float,
    pan: Offset,
    onTransformChange: (Float, Offset) -> Unit,
    onOpCommitted: (MaskOp) -> Unit,
    onTooShortTap: () -> Unit,
) {
    // Points recorded in local (0..1) croppedBitmap coordinates for exact screen feedback
    val strokePoints = remember { mutableStateListOf<Offset>() }

    // CRITICAL: Always read live updated state inside pointerInput!
    val latestDrawTool by rememberUpdatedState(drawTool)
    val latestBrushPx by rememberUpdatedState(brushRadiusScreenPx)
    val latestActiveCrop by rememberUpdatedState(activeCrop)
    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(pan)
    val latestOrigW by rememberUpdatedState(origW)
    val latestOrigH by rememberUpdatedState(origH)
    val latestCroppedW by rememberUpdatedState(croppedBitmap.width)
    val latestOnTransformChange by rememberUpdatedState(onTransformChange)
    val latestOnOpCommitted by rememberUpdatedState(onOpCommitted)
    val latestOnTooShort by rememberUpdatedState(onTooShortTap)

    fun computeCroppedRect(canvasSize: Size, bmpW: Int, bmpH: Int, z: Float, p: Offset): Rect {
        val pad = 16f
        val availW = (canvasSize.width - pad * 2f).coerceAtLeast(1f)
        val availH = (canvasSize.height - pad * 2f).coerceAtLeast(1f)
        val fitScale = min(availW / bmpW.toFloat().coerceAtLeast(1f), availH / bmpH.toFloat().coerceAtLeast(1f))
        val drawW = bmpW * fitScale * z
        val drawH = bmpH * fitScale * z
        val left = (canvasSize.width - drawW) * 0.5f + p.x
        val top = (canvasSize.height - drawH) * 0.5f + p.y
        return Rect(left, top, left + drawW, top + drawH)
    }

    val latestCroppedH by rememberUpdatedState(croppedBitmap.height)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    strokePoints.clear()
                    var curZoom = latestZoom
                    var curPan = latestPan
                    val toolAtStart = latestDrawTool
                    val cropAtStart = latestActiveCrop
                    var multiTouch = false
                    var totalPixelTravel = 0f

                    fun screenToLocalNorm(pt: Offset): Offset {
                        val r = computeCroppedRect(
                            Size(size.width.toFloat(), size.height.toFloat()),
                            latestCroppedW,
                            latestCroppedH,
                            curZoom,
                            curPan,
                        )
                        // Allow tracing slightly outside the image edge (-0.04..1.04) so edge cutouts close cleanly!
                        return Offset(
                            ((pt.x - r.left) / r.width.coerceAtLeast(1f)).coerceIn(-0.04f, 1.04f),
                            ((pt.y - r.top) / r.height.coerceAtLeast(1f)).coerceIn(-0.04f, 1.04f),
                        )
                    }

                    strokePoints.add(screenToLocalNorm(down.position))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            multiTouch = true
                            strokePoints.clear()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newZoom = (curZoom * zoomChange).coerceIn(1f, 10f)
                            val ratio = newZoom / curZoom
                            val center = Offset(size.width * 0.5f, size.height * 0.5f)
                            val rawPan = (curPan + centroid - center) * ratio - (centroid - center) + panChange
                            val maxPanX = size.width * (newZoom - 1f) * 0.65f
                            val maxPanY = size.height * (newZoom - 1f) * 0.65f
                            curZoom = newZoom
                            curPan = if (newZoom <= 1.01f) Offset.Zero else Offset(
                                rawPan.x.coerceIn(-maxPanX, maxPanX),
                                rawPan.y.coerceIn(-maxPanY, maxPanY),
                            )
                            latestOnTransformChange(curZoom, curPan)
                            event.changes.forEach { it.consume() }
                        } else if (!multiTouch) {
                            val ch = pressed.first()
                            totalPixelTravel += hypot(
                                ch.position.x - ch.previousPosition.x,
                                ch.position.y - ch.previousPosition.y,
                            )
                            val lnPt = screenToLocalNorm(ch.position)
                            val last = strokePoints.lastOrNull()
                            if (last == null || hypot(lnPt.x - last.x, lnPt.y - last.y) >= 0.002f) {
                                strokePoints.add(lnPt)
                            }
                            ch.consume()
                        }
                    }

                    if (!multiTouch && strokePoints.isNotEmpty()) {
                        // Convert local crop coordinates (0..1) -> master original coordinates (0..1)
                        val pts = FloatArray(strokePoints.size * 2)
                        for (i in strokePoints.indices) {
                            val lp = strokePoints[i]
                            pts[i * 2] = (cropAtStart.left + lp.x * cropAtStart.width).coerceIn(-0.05f, 1.05f)
                            pts[i * 2 + 1] = (cropAtStart.top + lp.y * cropAtStart.height).coerceIn(-0.05f, 1.05f)
                        }
                        val count = strokePoints.size
                        strokePoints.clear()

                        when (toolAtStart) {
                            CutoutDrawTool.KEEP_LOOP, CutoutDrawTool.CUT_HOLE_LOOP -> {
                                if (count >= 3 && totalPixelTravel >= 14.dp.toPx()) {
                                    val mode = if (toolAtStart == CutoutDrawTool.KEEP_LOOP) LassoMode.KEEP else LassoMode.ERASE
                                    latestOnOpCommitted(MaskOp.Lasso(mode, pts))
                                } else {
                                    latestOnTooShort()
                                }
                            }

                            CutoutDrawTool.ERASER_BRUSH, CutoutDrawTool.RESTORE_BRUSH -> {
                                val r = computeCroppedRect(
                                    Size(size.width.toFloat(), size.height.toFloat()),
                                    latestCroppedW,
                                    latestCroppedH,
                                    curZoom,
                                    curPan,
                                )
                                val masterRadiusPx = latestBrushPx * (latestCroppedW.toFloat() / r.width.coerceAtLeast(1f))
                                val maxMasterSide = max(latestOrigW, latestOrigH).coerceAtLeast(1).toFloat()
                                val radiusNorm = (masterRadiusPx / maxMasterSide).coerceIn(0.002f, 0.25f)
                                val isErase = toolAtStart == CutoutDrawTool.ERASER_BRUSH
                                latestOnOpCommitted(MaskOp.Brush(erase = isErase, radiusNorm = radiusNorm, points = pts))
                            }
                        }
                    } else {
                        strokePoints.clear()
                    }
                }
            }
    ) {
        val imgRect = computeCroppedRect(size, croppedBitmap.width, croppedBitmap.height, zoom, pan)
        drawImage(
            image = croppedBitmap,
            dstOffset = IntOffset(imgRect.left.roundToInt(), imgRect.top.roundToInt()),
            dstSize = IntSize(imgRect.width.roundToInt().coerceAtLeast(1), imgRect.height.roundToInt().coerceAtLeast(1)),
        )

        // Live stroke feedback
        if (strokePoints.isNotEmpty()) {
            val isBrush = drawTool == CutoutDrawTool.ERASER_BRUSH || drawTool == CutoutDrawTool.RESTORE_BRUSH
            val strokeColor = when (drawTool) {
                CutoutDrawTool.KEEP_LOOP -> neon
                CutoutDrawTool.CUT_HOLE_LOOP, CutoutDrawTool.ERASER_BRUSH -> DreamPalette.DangerNeon
                CutoutDrawTool.RESTORE_BRUSH -> DreamPalette.Neons[2].primary
            }

            val path = Path()
            val first = Offset(
                imgRect.left + strokePoints[0].x * imgRect.width,
                imgRect.top + strokePoints[0].y * imgRect.height,
            )
            path.moveTo(first.x, first.y)
            for (i in 1 until strokePoints.size) {
                path.lineTo(
                    imgRect.left + strokePoints[i].x * imgRect.width,
                    imgRect.top + strokePoints[i].y * imgRect.height,
                )
            }
            val last = Offset(
                imgRect.left + strokePoints.last().x * imgRect.width,
                imgRect.top + strokePoints.last().y * imgRect.height,
            )

            if (isBrush) {
                if (strokePoints.size == 1) {
                    drawCircle(strokeColor.copy(alpha = 0.45f), radius = brushRadiusScreenPx, center = first)
                } else {
                    drawPath(
                        path = path,
                        color = strokeColor.copy(alpha = 0.48f),
                        style = Stroke(width = brushRadiusScreenPx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                drawCircle(DreamPalette.PureWhite, radius = brushRadiusScreenPx, center = last, style = Stroke(width = 1.6.dp.toPx()))
            } else if (strokePoints.size >= 2) {
                val closedPreview = Path().apply {
                    addPath(path)
                    close()
                }
                drawPath(closedPreview, strokeColor.copy(alpha = 0.18f))
                drawPath(
                    path = path,
                    color = strokeColor.copy(alpha = 0.50f),
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = path,
                    color = DreamPalette.PureWhite,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawLine(
                    color = strokeColor.copy(alpha = 0.88f),
                    start = last,
                    end = first,
                    strokeWidth = 1.6.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                )
            }
        }
    }
}

// ============================================================================
// STEP 3 CANVAS: Per-Picture Sweet Spot Shield Masking (for Dense / Max Mode)
// ============================================================================

private fun computeAutoSweetPreviewBits(opaqueMask: Long): Long {
    if (opaqueMask == 0L) return 0L
    var minX = 8
    var maxX = -1
    var minY = 8
    var maxY = -1
    for (gy in 0 until 8) {
        for (gx in 0 until 8) {
            if ((opaqueMask and (1L shl (gy * 8 + gx))) != 0L) {
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

@Composable
private fun Step3SweetSpotCanvas(
    croppedBitmap: ImageBitmap,
    sweetOverlayBitmap: ImageBitmap?,
    sweetGridBits: Long,
    opaqueGridBits: Long,
    sweetConfigured: Boolean,
    origW: Int,
    origH: Int,
    activeCrop: NRect,
    tool: SweetSpotTool,
    brushRadiusScreenPx: Float,
    accentGold: Color,
    zoom: Float,
    pan: Offset,
    onTransformChange: (Float, Offset) -> Unit,
    onSweetOpCommitted: (MaskOp) -> Unit,
) {
    val strokePoints = remember { mutableStateListOf<Offset>() }

    val latestTool by rememberUpdatedState(tool)
    val latestBrushPx by rememberUpdatedState(brushRadiusScreenPx)
    val latestActiveCrop by rememberUpdatedState(activeCrop)
    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(pan)
    val latestOrigW by rememberUpdatedState(origW)
    val latestOrigH by rememberUpdatedState(origH)
    val latestCroppedW by rememberUpdatedState(croppedBitmap.width)
    val latestCroppedH by rememberUpdatedState(croppedBitmap.height)
    val latestOnTransformChange by rememberUpdatedState(onTransformChange)
    val latestOnSweetOpCommitted by rememberUpdatedState(onSweetOpCommitted)

    fun computeCroppedRect(canvasSize: Size, bmpW: Int, bmpH: Int, z: Float, p: Offset): Rect {
        val pad = 16f
        val availW = (canvasSize.width - pad * 2f).coerceAtLeast(1f)
        val availH = (canvasSize.height - pad * 2f).coerceAtLeast(1f)
        val fitScale = min(availW / bmpW.toFloat().coerceAtLeast(1f), availH / bmpH.toFloat().coerceAtLeast(1f))
        val drawW = bmpW * fitScale * z
        val drawH = bmpH * fitScale * z
        val left = (canvasSize.width - drawW) * 0.5f + p.x
        val top = (canvasSize.height - drawH) * 0.5f + p.y
        return Rect(left, top, left + drawW, top + drawH)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    strokePoints.clear()
                    var curZoom = latestZoom
                    var curPan = latestPan
                    val toolAtStart = latestTool
                    val cropAtStart = latestActiveCrop
                    var multiTouch = false
                    var totalPixelTravel = 0f

                    fun screenToLocalNorm(pt: Offset): Offset {
                        val r = computeCroppedRect(
                            Size(size.width.toFloat(), size.height.toFloat()),
                            latestCroppedW,
                            latestCroppedH,
                            curZoom,
                            curPan,
                        )
                        return Offset(
                            ((pt.x - r.left) / r.width.coerceAtLeast(1f)).coerceIn(-0.04f, 1.04f),
                            ((pt.y - r.top) / r.height.coerceAtLeast(1f)).coerceIn(-0.04f, 1.04f),
                        )
                    }

                    strokePoints.add(screenToLocalNorm(down.position))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            multiTouch = true
                            strokePoints.clear()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newZoom = (curZoom * zoomChange).coerceIn(1f, 10f)
                            val ratio = newZoom / curZoom
                            val center = Offset(size.width * 0.5f, size.height * 0.5f)
                            val rawPan = (curPan + centroid - center) * ratio - (centroid - center) + panChange
                            val nextRect = computeCroppedRect(
                                Size(size.width.toFloat(), size.height.toFloat()),
                                latestCroppedW,
                                latestCroppedH,
                                newZoom,
                                Offset.Zero,
                            )
                            val maxPanX = max(size.width * 0.45f, nextRect.width * 0.5f)
                            val maxPanY = max(size.height * 0.45f, nextRect.height * 0.5f)
                            curZoom = newZoom
                            curPan = if (newZoom <= 1.01f) Offset.Zero else Offset(
                                rawPan.x.coerceIn(-maxPanX, maxPanX),
                                rawPan.y.coerceIn(-maxPanY, maxPanY),
                            )
                            latestOnTransformChange(curZoom, curPan)
                            event.changes.forEach { it.consume() }
                        } else if (!multiTouch) {
                            val ch = pressed.first()
                            totalPixelTravel += hypot(
                                ch.position.x - ch.previousPosition.x,
                                ch.position.y - ch.previousPosition.y,
                            )
                            val lnPt = screenToLocalNorm(ch.position)
                            val last = strokePoints.lastOrNull()
                            if (last == null || hypot(lnPt.x - last.x, lnPt.y - last.y) >= 0.002f) {
                                strokePoints.add(lnPt)
                            }
                            ch.consume()
                        }
                    }

                    if (!multiTouch && strokePoints.isNotEmpty()) {
                        val pts = FloatArray(strokePoints.size * 2)
                        for (i in strokePoints.indices) {
                            val lp = strokePoints[i]
                            pts[i * 2] = (cropAtStart.left + lp.x * cropAtStart.width).coerceIn(-0.05f, 1.05f)
                            pts[i * 2 + 1] = (cropAtStart.top + lp.y * cropAtStart.height).coerceIn(-0.05f, 1.05f)
                        }
                        val count = strokePoints.size
                        strokePoints.clear()

                        when (toolAtStart) {
                            SweetSpotTool.LOOP_SPOT -> {
                                if (count >= 3 && totalPixelTravel >= 10.dp.toPx()) {
                                    latestOnSweetOpCommitted(MaskOp.Lasso(LassoMode.KEEP, pts))
                                }
                            }
                            SweetSpotTool.PAINT_BRUSH, SweetSpotTool.ERASE_BRUSH -> {
                                val r = computeCroppedRect(
                                    Size(size.width.toFloat(), size.height.toFloat()),
                                    latestCroppedW,
                                    latestCroppedH,
                                    curZoom,
                                    curPan,
                                )
                                val masterRadiusPx = latestBrushPx * (latestCroppedW.toFloat() / r.width.coerceAtLeast(1f))
                                val maxMasterSide = max(latestOrigW, latestOrigH).coerceAtLeast(1).toFloat()
                                val radiusNorm = (masterRadiusPx / maxMasterSide).coerceIn(0.003f, 0.30f)
                                val isErase = toolAtStart == SweetSpotTool.ERASE_BRUSH
                                latestOnSweetOpCommitted(MaskOp.Brush(erase = isErase, radiusNorm = radiusNorm, points = pts))
                            }
                        }
                    } else {
                        strokePoints.clear()
                    }
                }
            }
    ) {
        val imgRect = computeCroppedRect(size, croppedBitmap.width, croppedBitmap.height, zoom, pan)
        drawImage(
            image = croppedBitmap,
            dstOffset = IntOffset(imgRect.left.roundToInt(), imgRect.top.roundToInt()),
            dstSize = IntSize(imgRect.width.roundToInt().coerceAtLeast(1), imgRect.height.roundToInt().coerceAtLeast(1)),
        )

        // Draw active custom Sweet Spot shield mask OR default auto-focal preview grid
        if (sweetOverlayBitmap != null && sweetGridBits != 0L) {
            drawImage(
                image = sweetOverlayBitmap,
                dstOffset = IntOffset(imgRect.left.roundToInt(), imgRect.top.roundToInt()),
                dstSize = IntSize(imgRect.width.roundToInt().coerceAtLeast(1), imgRect.height.roundToInt().coerceAtLeast(1)),
                colorFilter = ColorFilter.tint(accentGold.copy(alpha = 0.38f), BlendMode.SrcIn),
            )
            val cellW = imgRect.width / 8f
            val cellH = imgRect.height / 8f
            for (gy in 0 until 8) {
                for (gx in 0 until 8) {
                    if ((sweetGridBits and (1L shl (gy * 8 + gx))) != 0L) {
                        drawRect(
                            color = accentGold.copy(alpha = 0.65f),
                            topLeft = Offset(imgRect.left + gx * cellW, imgRect.top + gy * cellH),
                            size = Size(cellW, cellH),
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                    }
                }
            }
        } else if (!sweetConfigured) {
            val autoBits = computeAutoSweetPreviewBits(opaqueGridBits)
            if (autoBits != 0L) {
                val cellW = imgRect.width / 8f
                val cellH = imgRect.height / 8f
                val dash = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), 0f)
                for (gy in 0 until 8) {
                    for (gx in 0 until 8) {
                        if ((autoBits and (1L shl (gy * 8 + gx))) != 0L) {
                            drawRect(
                                color = accentGold.copy(alpha = 0.14f),
                                topLeft = Offset(imgRect.left + gx * cellW, imgRect.top + gy * cellH),
                                size = Size(cellW, cellH),
                            )
                            drawRect(
                                color = accentGold.copy(alpha = 0.52f),
                                topLeft = Offset(imgRect.left + gx * cellW, imgRect.top + gy * cellH),
                                size = Size(cellW, cellH),
                                style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                            )
                        }
                    }
                }
            }
        }

        // Live stroke feedback
        if (strokePoints.isNotEmpty()) {
            val isBrush = tool == SweetSpotTool.PAINT_BRUSH || tool == SweetSpotTool.ERASE_BRUSH
            val strokeColor = if (tool == SweetSpotTool.ERASE_BRUSH) DreamPalette.DangerNeon else accentGold

            val path = Path()
            val first = Offset(
                imgRect.left + strokePoints[0].x * imgRect.width,
                imgRect.top + strokePoints[0].y * imgRect.height,
            )
            path.moveTo(first.x, first.y)
            for (i in 1 until strokePoints.size) {
                path.lineTo(
                    imgRect.left + strokePoints[i].x * imgRect.width,
                    imgRect.top + strokePoints[i].y * imgRect.height,
                )
            }
            val last = Offset(
                imgRect.left + strokePoints.last().x * imgRect.width,
                imgRect.top + strokePoints.last().y * imgRect.height,
            )

            if (isBrush) {
                if (strokePoints.size == 1) {
                    drawCircle(strokeColor.copy(alpha = 0.45f), radius = brushRadiusScreenPx, center = first)
                } else {
                    drawPath(
                        path = path,
                        color = strokeColor.copy(alpha = 0.48f),
                        style = Stroke(width = brushRadiusScreenPx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                drawCircle(DreamPalette.PureWhite, radius = brushRadiusScreenPx, center = last, style = Stroke(width = 1.6.dp.toPx()))
            } else if (strokePoints.size >= 2) {
                val closedPreview = Path().apply {
                    addPath(path)
                    close()
                }
                drawPath(closedPreview, strokeColor.copy(alpha = 0.22f))
                drawPath(
                    path = path,
                    color = strokeColor.copy(alpha = 0.55f),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = path,
                    color = DreamPalette.PureWhite,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawLine(
                    color = strokeColor.copy(alpha = 0.90f),
                    start = last,
                    end = first,
                    strokeWidth = 1.6.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                )
            }
        }
    }
}

package com.dreamos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dreamos.app.vault.BoardSession
import com.dreamos.app.vault.ImageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SourceImageGroup(
    val sourceId: String,
    val variants: List<ImageEntry>,
)

@Composable
fun GalleryScreen(
    session: BoardSession,
    onBackToWheel: () -> Unit,
    onAddImages: () -> Unit,
    onEditImage: (String) -> Unit,
    onAddVariation: (fromImageId: String) -> Unit,
    onOpenFullscreen: (String) -> Unit,
    onDeleteImage: (String) -> Unit,
    onChangePin: (String?, () -> Unit) -> Unit,
) {
    val images = session.meta.images
    val neon = DreamPalette.neonColor(session.info.color)
    var showSettings by remember { mutableStateOf(false) }
    var deletingEntry by remember { mutableStateOf<Pair<ImageEntry, Int>?>(null) } // entry to totalVariantsInGroup

    // Group entries sharing the same master sourceId so variations appear cleanly below each image box
    val groups = remember(images) {
        val map = LinkedHashMap<String, MutableList<ImageEntry>>()
        for (img in images) {
            map.getOrPut(img.sourceId) { mutableListOf() }.add(img)
        }
        map.map { (sid, list) -> SourceImageGroup(sid, list) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamPalette.VoidBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NeonHudIconButton(neon = neon, onClick = onBackToWheel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DreamPalette.PureWhite, modifier = Modifier.size(18.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${session.info.name} • MEDIA",
                    color = DreamPalette.PureWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${groups.size} MASTER • ${images.size} IN ROTATION • + FOR VARIATIONS",
                    color = neon.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            NeonHudIconButton(neon = neon, onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DreamPalette.PureWhite, modifier = Modifier.size(18.dp))
            }

            NeonButton(
                text = "+ ADD",
                neon = neon,
                filled = true,
                onClick = onAddImages,
                leading = { Icon(Icons.Default.Add, null, tint = neon, modifier = Modifier.size(15.dp)) },
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 152.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(groups, key = { it.sourceId }) { group ->
                GalleryGroupCard(
                    session = session,
                    group = group,
                    neon = neon,
                    onEdit = onEditImage,
                    onAddVariation = onAddVariation,
                    onPreview = onOpenFullscreen,
                    onDeleteVariant = { entry -> deletingEntry = entry to group.variants.size },
                )
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

    deletingEntry?.let { (entry, siblingCount) ->
        Dialog(onDismissRequest = { deletingEntry = null }) {
            NeonCardBox(neon = DreamPalette.DangerNeon, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (siblingCount > 1) "DELETE THIS VARIATION?" else "SHRED THIS IMAGE?",
                        color = DreamPalette.DangerNeon,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = if (siblingCount > 1) {
                            "Removes this variation from the rotation while keeping your other ${siblingCount - 1} variation${if (siblingCount - 1 == 1) "" else "s"} intact."
                        } else {
                            "Overwrites and deletes the encrypted master, cutout masks, and thumbnails from storage."
                        },
                        color = DreamPalette.SilverText,
                        fontSize = 12.5.sp,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        NeonButton("CANCEL", DreamPalette.SilverText, onClick = { deletingEntry = null })
                        NeonButton(
                            text = if (siblingCount > 1) "DELETE VAR" else "SHRED",
                            neon = DreamPalette.DangerNeon,
                            filled = true,
                            onClick = {
                                onDeleteImage(entry.id)
                                deletingEntry = null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryGroupCard(
    session: BoardSession,
    group: SourceImageGroup,
    neon: Color,
    onEdit: (String) -> Unit,
    onAddVariation: (String) -> Unit,
    onPreview: (String) -> Unit,
    onDeleteVariant: (ImageEntry) -> Unit,
) {
    var selectedIdx by remember(group.sourceId) { mutableIntStateOf(0) }
    val safeIdx = selectedIdx.coerceIn(0, group.variants.lastIndex)
    val activeEntry = group.variants[safeIdx]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(DreamPalette.AbyssGray)
            .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(13.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Main Image Box for the active variation
        GalleryVariantBox(
            session = session,
            entry = activeEntry,
            variantNumber = safeIdx + 1,
            totalVariants = group.variants.size,
            neon = neon,
            onEdit = { onEdit(activeEntry.id) },
            onPreview = { onPreview(activeEntry.id) },
            onDelete = { onDeleteVariant(activeEntry) },
        )

        // Variations Strip directly below the image box + [+] Add Variation button!
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            group.variants.forEachIndexed { idx, variant ->
                val isSelected = idx == safeIdx
                VariationMiniThumb(
                    session = session,
                    entry = variant,
                    indexLabel = "V${idx + 1}",
                    selected = isSelected,
                    neon = neon,
                    onClick = {
                        if (isSelected) {
                            onEdit(variant.id)
                        } else {
                            selectedIdx = idx
                        }
                    },
                )
            }

            // [+] Add Variation button right below the image box
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(neon.copy(alpha = 0.16f))
                    .border(1.dp, neon.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                    .clickable { onAddVariation(activeEntry.id) }
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Variation",
                    tint = neon,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "VAR",
                    color = neon,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun GalleryVariantBox(
    session: BoardSession,
    entry: ImageEntry,
    variantNumber: Int,
    totalVariants: Int,
    neon: Color,
    onEdit: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    var bmp by remember(entry.cacheKey) { mutableStateOf<ImageBitmap?>(session.cachedThumb(entry)) }
    LaunchedEffect(entry.cacheKey) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) { session.loadThumb(entry) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .clip(RoundedCornerShape(10.dp))
            .background(DreamPalette.SlateDark)
            .border(
                width = if (!entry.edits.isEmpty) 1.3.dp else 1.dp,
                color = if (!entry.edits.isEmpty) neon.copy(alpha = 0.7f) else DreamPalette.GraphiteLine,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onEdit),
    ) {
        if (entry.hasAlpha) {
            TransparencyCheckerboard(modifier = Modifier.fillMaxSize())
        }

        bmp?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = if (entry.hasAlpha) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (entry.hasAlpha) 6.dp else 0.dp),
            )
        }

        // Top badges
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (totalVariants > 1) {
                MiniBadge("V$variantNumber/$totalVariants", neon)
            }
            if (entry.hasAlpha) {
                MiniBadge("PNG", neon)
            }
            if (entry.edits.crop != null) {
                MiniBadge("ZOOM", DreamPalette.SilverText)
            }
            if (entry.hasCustomSweetSpot) {
                MiniBadge("★ SWEET", Color(0xFFFFD54F))
            }
        }

        // Delete button top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(DreamPalette.VoidBlack.copy(alpha = 0.74f))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DreamPalette.SilverText, modifier = Modifier.size(13.dp))
        }

        // Bottom bar: Edit + Fullscreen Preview
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(DreamPalette.VoidBlack.copy(alpha = 0.74f))
                .padding(horizontal = 7.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LassoGlyph(color = neon, modifier = Modifier.size(12.dp))
                Text(
                    text = "EDIT",
                    color = DreamPalette.PureWhite,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(neon.copy(alpha = 0.20f))
                    .clickable(onClick = onPreview),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "View", tint = neon, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
fun VariationMiniThumb(
    session: BoardSession,
    entry: ImageEntry,
    indexLabel: String,
    selected: Boolean,
    neon: Color,
    onClick: () -> Unit,
) {
    var bmp by remember(entry.cacheKey) { mutableStateOf<ImageBitmap?>(session.cachedThumb(entry)) }
    LaunchedEffect(entry.cacheKey) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) { session.loadThumb(entry) }
        }
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(DreamPalette.SlateDark)
            .border(
                width = if (selected) 1.8.dp else 1.dp,
                color = if (selected) neon else DreamPalette.GraphiteLine,
                shape = RoundedCornerShape(7.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { image ->
            Image(
                bitmap = image,
                contentDescription = indexLabel,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(DreamPalette.VoidBlack.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = indexLabel,
                color = if (selected) neon else DreamPalette.SilverText,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MiniBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(DreamPalette.VoidBlack.copy(alpha = 0.82f))
            .border(0.8.dp, color.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun TransparencyCheckerboard(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cell = 12.dp.toPx()
        val c1 = Color(0xFF131620)
        val c2 = Color(0xFF1E2231)
        drawRect(c1)
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0..rows) {
            for (c in 0..cols) {
                if ((r + c) % 2 == 1) {
                    drawRect(
                        color = c2,
                        topLeft = Offset(c * cell, r * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

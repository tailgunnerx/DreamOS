package com.dreamos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dreamos.app.vault.BoardSession
import com.dreamos.app.vault.DisplayMode
import java.util.Locale
import kotlin.math.roundToInt

/** Special sentinel in PhraseItem.colorArgb meaning pick a random vivid color each cycle. */
const val PHRASE_COLOR_RANDOM = 1

fun resolvePhraseBaseColor(colorArgb: Int, shrineNeon: Color, randomSeed: Int = 0): Color {
    return when (colorArgb) {
        0 -> shrineNeon
        PHRASE_COLOR_RANDOM -> {
            val hue = ((randomSeed * 137 + 53) % 360 + 360) % 360
            Color.hsv(hue.toFloat(), 0.82f, 1.0f)
        }
        else -> Color(colorArgb or (0xFF shl 24))
    }
}

@Composable
fun VaultSettingsDialog(
    session: BoardSession,
    onDismiss: () -> Unit,
    onChangePin: (String?, () -> Unit) -> Unit,
) {
    val meta = session.meta
    val neon = DreamPalette.neonColor(session.info.color)
    var phraseDraft by remember { mutableStateOf("") }
    var draftColorArgb by remember { mutableIntStateOf(0) }
    var showChangePin by remember { mutableStateOf(false) }

    fun flushPendingDraft() {
        val raw = phraseDraft.trim()
        if (raw.isNotEmpty()) {
            raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                session.addPhrase(line.take(160), draftColorArgb)
            }
            phraseDraft = ""
        }
    }

    Dialog(
        onDismissRequest = {
            flushPendingDraft()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NeonCardBox(
            neon = neon,
            glowAlpha = 0.28f,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .padding(vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "SHRINE SETTINGS",
                            color = neon,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.2.sp,
                        )
                        Text(
                            text = "Saved encrypted per-board",
                            color = DreamPalette.SteelMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = DreamPalette.SilverText,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable {
                                flushPendingDraft()
                                onDismiss()
                            },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // --- SECTION: APPEARANCE & WALL ---
                    SettingsSectionHeader("SUBJECTS & WALL", neon)

                    SettingsToggleRow(
                        title = "Maximum Mode (Sweet-Spot Safe)",
                        subtitle = "Packs as many ref images on screen as possible without covering up Sweet Spots",
                        checked = meta.maximumMode,
                        neon = neon,
                        onCheckedChange = { v -> session.setMaximumMode(v) },
                    )

                    SettingsToggleRow(
                        title = "Image Card Borders",
                        subtitle = "Off = clean borderless images",
                        checked = meta.showBorders,
                        neon = neon,
                        onCheckedChange = { v -> session.updateSettings { it.copy(showBorders = v) } },
                    )

                    SettingsToggleRow(
                        title = "Cutout Sticker Outline",
                        subtitle = "Off = pure transparent PNGs showing only the subject",
                        checked = meta.showCutoutOutline,
                        neon = neon,
                        onCheckedChange = { v -> session.updateSettings { it.copy(showCutoutOutline = v) } },
                    )

                    SettingsToggleRow(
                        title = "Neon Wall Background",
                        subtitle = "Off = 100% pure pitch-black background wall",
                        checked = meta.showWallBackground,
                        neon = neon,
                        onCheckedChange = { v -> session.updateSettings { it.copy(showWallBackground = v) } },
                    )

                    SettingsToggleRow(
                        title = "Start with All UI Hidden",
                        subtitle = "Enter vault in pure distraction-free mode (tap empty space or Back to show UI)",
                        checked = meta.startUiHidden,
                        neon = neon,
                        onCheckedChange = { v -> session.updateSettings { it.copy(startUiHidden = v) } },
                    )

                    // --- SECTION: MOTION & SPEED ---
                    SettingsSectionHeader("MOTION & GRANULAR SPEED", neon)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        NeonChip(
                            label = "3D WHEEL",
                            selected = meta.mode == DisplayMode.WHEEL,
                            neon = neon,
                            onClick = { session.setMode(DisplayMode.WHEEL) },
                            modifier = Modifier.weight(1f),
                        )
                        NeonChip(
                            label = "WATERFALL",
                            selected = meta.mode == DisplayMode.WATERFALL,
                            neon = neon,
                            onClick = { session.setMode(DisplayMode.WATERFALL) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    GranularSliderRow(
                        title = "Rotation / Scroll Speed (up to 12x)",
                        valueLabel = String.format(Locale.US, "%.2fx", meta.speedMultiplier),
                        value = meta.speedMultiplier,
                        range = 0.05f..12.00f,
                        stepDelta = if (meta.speedMultiplier >= 3.0f) 0.25f else 0.05f,
                        neon = neon,
                        onValueChange = { v ->
                            val snapped = (v * 20f).roundToInt() / 20f
                            session.setSpeedMultiplier(snapped)
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        for (preset in listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 8.0f, 12.0f)) {
                            val label = if (preset == 0.5f) "0.5x" else "${preset.toInt()}x"
                            NeonChip(
                                label = label,
                                selected = kotlin.math.abs(meta.speedMultiplier - preset) < 0.04f,
                                neon = neon,
                                onClick = { session.setSpeedMultiplier(preset) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // --- SECTION: SIZE VARIATION & OVERLAP ---
                    SettingsSectionHeader("SIZE VARIATION & SPACING", neon)

                    GranularSliderRow(
                        title = "Smallest Subject Size",
                        valueLabel = "${(meta.minSize * 100).roundToInt()}%",
                        value = meta.minSize,
                        range = 0.25f..1.20f,
                        stepDelta = 0.05f,
                        neon = neon,
                        onValueChange = { v ->
                            val clamped = v.coerceAtMost(meta.maxSize - 0.10f)
                            session.updateSettings { it.copy(minSize = clamped) }
                        },
                    )

                    GranularSliderRow(
                        title = "Largest Spotlight Size",
                        valueLabel = "${(meta.maxSize * 100).roundToInt()}%",
                        value = meta.maxSize,
                        range = 0.60f..2.10f,
                        stepDelta = 0.05f,
                        neon = neon,
                        onValueChange = { v ->
                            val clamped = v.coerceAtLeast(meta.minSize + 0.10f)
                            session.updateSettings { it.copy(maxSize = clamped) }
                        },
                    )

                    GranularSliderRow(
                        title = "Subject Spacing (Anti-Overlap)",
                        valueLabel = when {
                            meta.spacing < 0.92f -> "DENSE (${(meta.spacing * 100).roundToInt()}%)"
                            meta.spacing < 1.42f -> "LOW OVERLAP (${(meta.spacing * 100).roundToInt()}%)"
                            else -> "AIRY (${(meta.spacing * 100).roundToInt()}%)"
                        },
                        value = meta.spacing,
                        range = 0.70f..1.85f,
                        stepDelta = 0.05f,
                        neon = neon,
                        onValueChange = { v -> session.updateSettings { it.copy(spacing = v) } },
                    )

                    GranularSliderRow(
                        title = "Poster Tilt Angle",
                        valueLabel = "${meta.tiltDegrees.roundToInt()}°",
                        value = meta.tiltDegrees,
                        range = 0f..16f,
                        stepDelta = 1f,
                        neon = neon,
                        onValueChange = { v -> session.updateSettings { it.copy(tiltDegrees = v) } },
                    )

                    // --- SECTION: BACKGROUND PHRASES ---
                    SettingsSectionHeader("BACKGROUND PHRASES (${meta.phrases.size})", neon)

                    BackgroundPhrasesSection(
                        session = session,
                        shrineNeon = neon,
                        phraseDraft = phraseDraft,
                        onPhraseDraftChange = { if (it.length <= 160) phraseDraft = it },
                        selectedColorArgb = draftColorArgb,
                        onSelectedColorChange = { draftColorArgb = it },
                        onAddDraft = { flushPendingDraft() },
                    )

                    // --- SECTION: TIMEOUT & SECURITY ---
                    SettingsSectionHeader("SESSION TIMEOUT & SECURITY", neon)

                    SessionTimeoutCard(
                        timeoutSec = meta.sessionTimeoutSec,
                        neon = neon,
                        onTimeoutChange = { sec ->
                            session.updateSettings { it.copy(sessionTimeoutSec = sec) }
                        },
                    )

                    if (session.info.hasPin) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NeonButton(
                                text = "CHANGE PIN",
                                neon = neon,
                                onClick = { showChangePin = true },
                                modifier = Modifier.weight(1f),
                            )
                            NeonButton(
                                text = "REMOVE PIN",
                                neon = DreamPalette.DangerNeon,
                                onClick = { onChangePin(null) {} },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        NeonButton(
                            text = "SET SHRINE PIN CODE",
                            neon = neon,
                            onClick = { showChangePin = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                NeonButton(
                    text = "DONE",
                    neon = neon,
                    filled = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        flushPendingDraft()
                        onDismiss()
                    },
                )
            }
        }
    }

    if (showChangePin) {
        ChangePinDialog(
            neon = neon,
            title = if (session.info.hasPin) "CHANGE SHRINE PIN" else "SET SHRINE PIN",
            onDismiss = { showChangePin = false },
            onConfirmNewPin = { newPin ->
                onChangePin(newPin) { showChangePin = false }
            },
        )
    }
}

@Composable
private fun BackgroundPhrasesSection(
    session: BoardSession,
    shrineNeon: Color,
    phraseDraft: String,
    onPhraseDraftChange: (String) -> Unit,
    selectedColorArgb: Int,
    onSelectedColorChange: (Int) -> Unit,
    onAddDraft: () -> Unit,
) {
    val meta = session.meta
    var customHue by remember { mutableFloatStateOf(190f) }
    var customSat by remember { mutableFloatStateOf(0.88f) }
    var showCustomSpectrum by remember { mutableStateOf(false) }
    var editingPhraseId by remember { mutableStateOf<String?>(null) }

    val activePreviewColor = resolvePhraseBaseColor(selectedColorArgb, shrineNeon, randomSeed = 3)

    fun applyColorChoice(argb: Int) {
        onSelectedColorChange(argb)
        val targetId = editingPhraseId
        if (targetId != null) {
            session.updatePhraseColor(targetId, argb)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DreamPalette.AbyssGray)
            .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Add as many phrases as you want — rendered subtly behind the waterfall/wheel so they never clash with your images.",
            color = DreamPalette.SteelMuted,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
        )

        // Input row + Add button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = phraseDraft,
                onValueChange = onPhraseDraftChange,
                placeholder = {
                    Text(
                        "Type a phrase & tap +",
                        color = DreamPalette.SteelMuted,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAddDraft() }),
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = activePreviewColor,
                    unfocusedBorderColor = DreamPalette.GraphiteLine,
                    focusedTextColor = DreamPalette.PureWhite,
                    unfocusedTextColor = DreamPalette.PureWhite,
                    cursorColor = activePreviewColor,
                ),
            )

            Box(
                modifier = Modifier
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (phraseDraft.isNotBlank()) activePreviewColor.copy(alpha = 0.22f)
                        else DreamPalette.SlateElevated
                    )
                    .border(
                        1.dp,
                        if (phraseDraft.isNotBlank()) activePreviewColor else DreamPalette.GraphiteLine,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(enabled = phraseDraft.isNotBlank()) {
                        editingPhraseId = null
                        onAddDraft()
                    }
                    .padding(horizontal = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add Phrase", tint = DreamPalette.PureWhite, modifier = Modifier.size(16.dp))
                    Text("ADD", color = DreamPalette.PureWhite, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Color selector row (Swatches + Custom Rainbow Spectrum + Random Color)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (editingPhraseId != null) "COLOR FOR SELECTED PHRASE:" else "PHRASE COLOR:",
                    color = DreamPalette.SilverText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (meta.phrases.size > 1) {
                        Text(
                            text = "APPLY TO ALL",
                            color = activePreviewColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, activePreviewColor.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                .clickable {
                                    session.updateSettings { m ->
                                        m.copy(phrases = m.phrases.map { it.copy(colorArgb = selectedColorArgb) })
                                    }
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        text = if (showCustomSpectrum) "HIDE SPECTRUM" else "CUSTOM RGB",
                        color = shrineNeon,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, shrineNeon.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .clickable { showCustomSpectrum = !showCustomSpectrum }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            val swatches = remember(shrineNeon) {
                listOf(
                    0 to shrineNeon,
                    Color(0xFFF4F6FC).toArgb() to Color(0xFFF4F6FC),
                    Color(0xFF00F0FF).toArgb() to Color(0xFF00F0FF),
                    Color(0xFFFF2BD6).toArgb() to Color(0xFFFF2BD6),
                    Color(0xFFB6FF3B).toArgb() to Color(0xFFB6FF3B),
                    Color(0xFFFFE14D).toArgb() to Color(0xFFFFE14D),
                    Color(0xFFB366FF).toArgb() to Color(0xFFB366FF),
                    Color(0xFFFF5E3A).toArgb() to Color(0xFFFF5E3A),
                    Color(0xFFFF1744).toArgb() to Color(0xFFFF1744),
                    Color(0xFF64FFDA).toArgb() to Color(0xFF64FFDA),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Random color chip
                val isRand = selectedColorArgb == PHRASE_COLOR_RANDOM
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFFF2BD6), Color(0xFF00F0FF), Color(0xFFFFE14D))
                            )
                        )
                        .border(
                            width = if (isRand) 2.5.dp else 1.dp,
                            color = if (isRand) DreamPalette.PureWhite else DreamPalette.GraphiteLine,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable { applyColorChoice(PHRASE_COLOR_RANDOM) }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "RAND",
                        color = DreamPalette.VoidBlack,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                for ((argb, swatchColor) in swatches) {
                    val selected = selectedColorArgb == argb
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(
                                width = if (selected) 2.8.dp else 1.dp,
                                color = if (selected) DreamPalette.PureWhite else DreamPalette.VoidBlack.copy(alpha = 0.6f),
                                shape = CircleShape,
                            )
                            .clickable { applyColorChoice(argb) },
                    )
                }
            }

            if (showCustomSpectrum) {
                val rainbowBrush = remember {
                    Brush.horizontalGradient(
                        listOf(
                            Color.hsv(0f, 1f, 1f),
                            Color.hsv(60f, 1f, 1f),
                            Color.hsv(120f, 1f, 1f),
                            Color.hsv(180f, 1f, 1f),
                            Color.hsv(240f, 1f, 1f),
                            Color.hsv(300f, 1f, 1f),
                            Color.hsv(360f, 1f, 1f),
                        )
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DreamPalette.SlateDark)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("HUE SPECTRUM", color = DreamPalette.SilverText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(rainbowBrush)
                    )
                    Slider(
                        value = customHue,
                        onValueChange = { h ->
                            customHue = h
                            applyColorChoice(Color.hsv(h, customSat, 1f).toArgb())
                        },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.hsv(customHue, customSat, 1f),
                            activeTrackColor = Color.hsv(customHue, customSat, 1f),
                            inactiveTrackColor = DreamPalette.GraphiteLine,
                        ),
                    )
                    Text("PASTEL ↔ VIVID SATURATION", color = DreamPalette.SilverText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Slider(
                        value = customSat,
                        onValueChange = { s ->
                            customSat = s
                            applyColorChoice(Color.hsv(customHue, s, 1f).toArgb())
                        },
                        valueRange = 0.0f..1.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.hsv(customHue, customSat, 1f),
                            activeTrackColor = Color.hsv(customHue, customSat, 1f),
                            inactiveTrackColor = DreamPalette.GraphiteLine,
                        ),
                    )
                }
            }
        }

        // Added Phrases List
        if (meta.phrases.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((idx, item) in meta.phrases.withIndex()) {
                    val isEditingThis = editingPhraseId == item.id
                    val itemColor = resolvePhraseBaseColor(item.colorArgb, shrineNeon, randomSeed = idx + 1)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isEditingThis) DreamPalette.SlateElevated else DreamPalette.VoidBlack.copy(alpha = 0.65f))
                            .border(
                                1.dp,
                                if (isEditingThis) itemColor else DreamPalette.GraphiteLine,
                                RoundedCornerShape(9.dp),
                            )
                            .clickable {
                                if (isEditingThis) {
                                    editingPhraseId = null
                                } else {
                                    editingPhraseId = item.id
                                    onSelectedColorChange(item.colorArgb)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(
                                    if (item.colorArgb == PHRASE_COLOR_RANDOM) {
                                        Brush.linearGradient(listOf(Color(0xFFFF2BD6), Color(0xFF00F0FF)))
                                    } else {
                                        Brush.linearGradient(listOf(itemColor, itemColor))
                                    }
                                )
                                .border(1.dp, DreamPalette.PureWhite.copy(alpha = 0.7f), CircleShape)
                        )
                        Text(
                            text = "#${idx + 1}",
                            color = DreamPalette.SteelMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = item.text,
                            color = itemColor.copy(alpha = 0.92f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove phrase",
                            tint = DreamPalette.DangerNeon.copy(alpha = 0.85f),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    if (editingPhraseId == item.id) editingPhraseId = null
                                    session.removePhrase(item.id)
                                },
                        )
                    }
                }
            }
        }

        // Timing Controls (Fixed Time Limit vs Random Time Range + Order)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NeonChip(
                label = "FIXED TIMER",
                selected = !meta.phraseRandomTiming,
                neon = shrineNeon,
                onClick = { session.updateSettings { it.copy(phraseRandomTiming = false) } },
                modifier = Modifier.weight(1f),
            )
            NeonChip(
                label = "RANDOM TIMER",
                selected = meta.phraseRandomTiming,
                neon = shrineNeon,
                onClick = { session.updateSettings { it.copy(phraseRandomTiming = true) } },
                modifier = Modifier.weight(1f),
            )
            NeonChip(
                label = if (meta.phraseShuffleOrder) "SHUFFLE" else "IN ORDER",
                selected = meta.phraseShuffleOrder,
                neon = shrineNeon,
                onClick = { session.updateSettings { it.copy(phraseShuffleOrder = !meta.phraseShuffleOrder) } },
                modifier = Modifier.weight(0.9f),
            )
        }

        if (!meta.phraseRandomTiming) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Change Phrase Every", color = DreamPalette.PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = formatTimeoutLabel(meta.phraseIntervalSec),
                        color = shrineNeon,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = meta.phraseIntervalSec.toFloat().coerceIn(2f, 120f),
                    onValueChange = { v ->
                        session.updateSettings { it.copy(phraseIntervalSec = v.roundToInt().coerceIn(2, 300)) }
                    },
                    valueRange = 2f..120f,
                    colors = SliderDefaults.colors(
                        thumbColor = shrineNeon,
                        activeTrackColor = shrineNeon,
                        inactiveTrackColor = DreamPalette.GraphiteLine,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    for (sec in listOf(3, 5, 10, 20, 30, 60)) {
                        NeonChip(
                            label = if (sec == 60) "1m" else "${sec}s",
                            selected = meta.phraseIntervalSec == sec,
                            neon = shrineNeon,
                            onClick = { session.updateSettings { it.copy(phraseIntervalSec = sec) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Random Change Window", color = DreamPalette.PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${meta.phraseRandomMinSec}s – ${meta.phraseRandomMaxSec}s",
                        color = shrineNeon,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text("Min Seconds (${meta.phraseRandomMinSec}s)", color = DreamPalette.SilverText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Slider(
                    value = meta.phraseRandomMinSec.toFloat().coerceIn(2f, 90f),
                    onValueChange = { v ->
                        val newMin = v.roundToInt().coerceIn(2, 90)
                        val newMax = meta.phraseRandomMaxSec.coerceAtLeast(newMin + 1)
                        session.updateSettings { it.copy(phraseRandomMinSec = newMin, phraseRandomMaxSec = newMax) }
                    },
                    valueRange = 2f..90f,
                    colors = SliderDefaults.colors(
                        thumbColor = shrineNeon,
                        activeTrackColor = shrineNeon,
                        inactiveTrackColor = DreamPalette.GraphiteLine,
                    ),
                )
                Text("Max Seconds (${meta.phraseRandomMaxSec}s)", color = DreamPalette.SilverText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Slider(
                    value = meta.phraseRandomMaxSec.toFloat().coerceIn(3f, 180f),
                    onValueChange = { v ->
                        val newMax = v.roundToInt().coerceIn(meta.phraseRandomMinSec + 1, 180)
                        session.updateSettings { it.copy(phraseRandomMaxSec = newMax) }
                    },
                    valueRange = 3f..180f,
                    colors = SliderDefaults.colors(
                        thumbColor = shrineNeon,
                        activeTrackColor = shrineNeon,
                        inactiveTrackColor = DreamPalette.GraphiteLine,
                    ),
                )
            }
        }

        // Subtle Opacity Slider + Live Wall Preview
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Subtle Background Opacity", color = DreamPalette.PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${(meta.phraseOpacity * 100).roundToInt()}%",
                    color = shrineNeon,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Slider(
                value = meta.phraseOpacity.coerceIn(0.05f, 0.45f),
                onValueChange = { v ->
                    session.updateSettings { it.copy(phraseOpacity = v.coerceIn(0.05f, 0.45f)) }
                },
                valueRange = 0.05f..0.45f,
                colors = SliderDefaults.colors(
                    thumbColor = shrineNeon,
                    activeTrackColor = shrineNeon,
                    inactiveTrackColor = DreamPalette.GraphiteLine,
                ),
            )

            val sampleText = when {
                phraseDraft.isNotBlank() -> phraseDraft.trim()
                meta.phrases.isNotEmpty() -> meta.phrases.first().text
                else -> "PREVIEW PHRASE"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(8.dp))
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = sampleText.uppercase(),
                    color = activePreviewColor.copy(alpha = meta.phraseOpacity),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String, neon: Color) {
    Text(
        text = text,
        color = neon.copy(alpha = 0.9f),
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Black,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.1.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    neon: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DreamPalette.AbyssGray)
            .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = DreamPalette.PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = DreamPalette.SteelMuted, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DreamPalette.VoidBlack,
                checkedTrackColor = neon,
                uncheckedThumbColor = DreamPalette.SilverText,
                uncheckedTrackColor = DreamPalette.SlateElevated,
                uncheckedBorderColor = DreamPalette.GraphiteLine,
            ),
        )
    }
}

@Composable
private fun GranularSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    stepDelta: Float,
    neon: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DreamPalette.AbyssGray)
            .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = DreamPalette.PureWhite, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepMiniBtn("−", neon) {
                    onValueChange((value - stepDelta).coerceIn(range.start, range.endInclusive))
                }
                Text(
                    text = valueLabel,
                    color = neon,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                StepMiniBtn("+", neon) {
                    onValueChange((value + stepDelta).coerceIn(range.start, range.endInclusive))
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = neon,
                activeTrackColor = neon,
                inactiveTrackColor = DreamPalette.GraphiteLine,
            ),
        )
    }
}

@Composable
private fun StepMiniBtn(label: String, neon: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DreamPalette.SlateElevated)
            .border(1.dp, neon.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = DreamPalette.PureWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

private fun formatTimeoutLabel(sec: Int): String = when {
    sec <= 0 -> "NEVER"
    sec < 60 -> "${sec}s"
    sec % 60 == 0 -> "${sec / 60}m"
    else -> "${sec / 60}m ${sec % 60}s"
}

@Composable
private fun SessionTimeoutCard(
    timeoutSec: Int,
    neon: Color,
    onTimeoutChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DreamPalette.AbyssGray)
            .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Screen-On Session Timeout", color = DreamPalette.PureWhite, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (timeoutSec <= 0) "Screen stays awake indefinitely until manually locked"
                    else "Keeps screen awake for ${formatTimeoutLabel(timeoutSec)} idle, then locks vault",
                    color = DreamPalette.SteelMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepMiniBtn("−", neon) {
                    val next = when {
                        timeoutSec <= 0 -> 3600
                        timeoutSec <= 60 -> 30
                        timeoutSec <= 600 -> timeoutSec - 60
                        else -> timeoutSec - 300
                    }
                    onTimeoutChange(next.coerceIn(30, 7200))
                }
                Text(
                    text = formatTimeoutLabel(timeoutSec),
                    color = neon,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                StepMiniBtn("+", neon) {
                    val next = when {
                        timeoutSec <= 0 -> 60
                        timeoutSec < 60 -> 60
                        timeoutSec < 600 -> timeoutSec + 60
                        timeoutSec < 7200 -> timeoutSec + 300
                        else -> 0
                    }
                    onTimeoutChange(next)
                }
            }
        }

        val presets = listOf(
            60 to "1m",
            180 to "3m",
            300 to "5m",
            600 to "10m",
            1800 to "30m",
            0 to "NEVER",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((sec, label) in presets) {
                NeonChip(
                    label = label,
                    selected = timeoutSec == sec,
                    neon = neon,
                    onClick = { onTimeoutChange(sec) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

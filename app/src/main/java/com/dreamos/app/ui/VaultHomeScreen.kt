package com.dreamos.app.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dreamos.app.vault.BoardInfo
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultHomeScreen(
    boards: List<BoardInfo>,
    onCreateBoard: (name: String, color: Int, pin: String?, onDone: () -> Unit) -> Unit,
    onOpenUnpinnedBoard: (board: BoardInfo) -> Unit,
    onUnlockBoard: (
        board: BoardInfo,
        pin: String,
        onWrongPin: (Int, Long) -> Unit,
        onLockedOut: (Long) -> Unit,
        onUnlocked: () -> Unit,
    ) -> Unit,
    onSetPinFromHome: (boardId: String, newPin: String, onDone: () -> Unit) -> Unit,
    onRenameBoard: (id: String, name: String, color: Int) -> Unit,
    onShredBoard: (id: String) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var unlockingBoard by remember { mutableStateOf<BoardInfo?>(null) }
    var addingPinBoard by remember { mutableStateOf<BoardInfo?>(null) }
    var editingBoard by remember { mutableStateOf<BoardInfo?>(null) }
    var shreddingBoard by remember { mutableStateOf<BoardInfo?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamPalette.VoidBlack)
    ) {
        // Subtle dark-scale architectural grid background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 36.dp.toPx()
            val gridColor = DreamPalette.GraphiteLine.copy(alpha = 0.22f)
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 158.dp),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HomeHeader(boardCount = boards.size)
            }

            items(boards, key = { it.id }) { board ->
                ObsessionVaultBox(
                    board = board,
                    onClick = {
                        if (board.hasPin) unlockingBoard = board
                        else onOpenUnpinnedBoard(board)
                    },
                    onSetPin = if (!board.hasPin) ({ addingPinBoard = board }) else null,
                    onEdit = { editingBoard = board },
                    onShred = { shreddingBoard = board },
                )
            }

            item(key = "create_new_box") {
                NewObsessionBox(
                    suggestedNeon = DreamPalette.neonColor(boards.size),
                    onClick = { showCreate = true },
                )
            }
        }
    }

    if (showCreate) {
        CreateBoardDialog(
            initialColor = boards.size % DreamPalette.Neons.size,
            onDismiss = { showCreate = false },
            onCreate = { name, color, pin ->
                onCreateBoard(name, color, pin) { showCreate = false }
            },
        )
    }

    unlockingBoard?.let { target ->
        UnlockPinDialog(
            board = target,
            onDismiss = { unlockingBoard = null },
            onSubmit = { pin, onErr, onLock ->
                onUnlockBoard(target, pin, onErr, onLock) { unlockingBoard = null }
            },
        )
    }

    addingPinBoard?.let { target ->
        ChangePinDialog(
            neon = DreamPalette.neonColor(target.color),
            title = "SET SHRINE PIN",
            onDismiss = { addingPinBoard = null },
            onConfirmNewPin = { pin ->
                onSetPinFromHome(target.id, pin) { addingPinBoard = null }
            },
        )
    }

    editingBoard?.let { target ->
        RenameBoardDialog(
            board = target,
            onDismiss = { editingBoard = null },
            onSave = { name, color ->
                onRenameBoard(target.id, name, color)
                editingBoard = null
            },
        )
    }

    shreddingBoard?.let { target ->
        ShredConfirmDialog(
            board = target,
            onDismiss = { shreddingBoard = null },
            onConfirm = {
                onShredBoard(target.id)
                shreddingBoard = null
            },
        )
    }
}

@Composable
private fun HomeHeader(boardCount: Int) {
    val cyan = DreamPalette.Neons[0].primary
    val pink = DreamPalette.Neons[1].primary
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.verticalGradient(listOf(cyan, pink)))
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DREAM",
                            color = DreamPalette.PureWhite,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            text = "//OS",
                            color = cyan,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                        )
                    }
                    Text(
                        text = "ENCRYPTED OBSESSION SHRINES",
                        color = DreamPalette.SteelMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.4.sp,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DreamPalette.SlateDark)
                    .border(1.dp, cyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "$boardCount VAULT${if (boardCount == 1) "" else "S"}",
                    color = cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(DreamPalette.AbyssGray)
                .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(9.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = DreamPalette.Neons[2].primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "AES-256-GCM • KEYSTORE PEPPERED • ZERO-KNOWLEDGE PIN",
                color = DreamPalette.SilverText,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun ObsessionVaultBox(
    board: BoardInfo,
    onClick: () -> Unit,
    onSetPin: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onShred: () -> Unit,
) {
    val swatch = DreamPalette.swatch(board.color)
    val neon = swatch.primary
    var menuOpen by remember { mutableStateOf(false) }
    val dateLabel = remember(board.createdAt) {
        SimpleDateFormat("MMM dd", Locale.US).format(Date(board.createdAt)).uppercase(Locale.US)
    }

    NeonCardBox(
        neon = neon,
        glowAlpha = 0.24f,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.95f),
    ) {
        // Subtle corner bracket & top accent bar
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(neon.copy(alpha = 0.85f), swatch.secondary.copy(alpha = 0.15f), Color.Transparent)),
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()),
            )
            // Cyber corner ticks
            val tick = 12.dp.toPx()
            val pad = 8.dp.toPx()
            val stroke = 1.5.dp.toPx()
            val c = neon.copy(alpha = 0.45f)
            drawLine(c, Offset(size.width - pad - tick, size.height - pad), Offset(size.width - pad, size.height - pad), stroke)
            drawLine(c, Offset(size.width - pad, size.height - pad - tick), Offset(size.width - pad, size.height - pad), stroke)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (board.hasPin) neon.copy(alpha = 0.14f) else DreamPalette.SlateElevated.copy(alpha = 0.65f))
                        .border(1.dp, if (board.hasPin) neon.copy(alpha = 0.5f) else DreamPalette.GraphiteLine, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (board.hasPin) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "PIN Protected",
                            tint = neon,
                            modifier = Modifier.size(11.dp),
                        )
                        Text(
                            text = "PIN LOCK",
                            color = neon,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        Text(
                            text = "OPEN",
                            color = DreamPalette.SilverText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Box {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = DreamPalette.SilverText,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { menuOpen = true }
                            .padding(2.dp),
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = DreamPalette.SlateElevated,
                    ) {
                        if (onSetPin != null) {
                            DropdownMenuItem(
                                text = { Text("Set PIN Lock", color = DreamPalette.PureWhite, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Lock, null, tint = neon, modifier = Modifier.size(16.dp)) },
                                onClick = { menuOpen = false; onSetPin() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Rename / Neon", color = DreamPalette.PureWhite, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Edit, null, tint = neon, modifier = Modifier.size(16.dp)) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Shred Vault", color = DreamPalette.DangerNeon, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = DreamPalette.DangerNeon, modifier = Modifier.size(16.dp)) },
                            onClick = { menuOpen = false; onShred() },
                        )
                    }
                }
            }

            Column {
                Text(
                    text = board.name,
                    color = DreamPalette.PureWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${board.imageCount} IMG${if (board.imageCount == 1) "" else "S"}",
                        color = neon.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = dateLabel,
                        color = DreamPalette.SteelMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewObsessionBox(suggestedNeon: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.95f)
            .clip(RoundedCornerShape(14.dp))
            .background(DreamPalette.AbyssGray.copy(alpha = 0.85f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = 14.dp.toPx()
            drawRoundRect(
                color = suggestedNeon.copy(alpha = 0.65f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f),
                ),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(suggestedNeon.copy(alpha = 0.16f))
                    .border(1.2.dp, suggestedNeon, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create", tint = suggestedNeon, modifier = Modifier.size(24.dp))
            }
            Text(
                text = "NEW SHRINE",
                color = DreamPalette.PureWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            )
            Text(
                text = "Encrypted poster shrine",
                color = DreamPalette.SteelMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ------------------------------------------------------------------ Dialogs & PIN Pad

@Composable
fun CreateBoardDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onCreate: (name: String, color: Int, pin: String?) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) } // 0 = details, 1 = set PIN, 2 = confirm PIN
    var name by remember { mutableStateOf("") }
    var colorIdx by remember { mutableIntStateOf(initialColor) }
    var enablePin by remember { mutableStateOf(false) } // OFF by default!
    var firstPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val neon = DreamPalette.neonColor(colorIdx)

    val config = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp || config.screenHeightDp < 480

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NeonCardBox(
            neon = neon,
            glowAlpha = 0.30f,
            modifier = Modifier
                .widthIn(max = if (isLandscape) 620.dp else 440.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = (config.screenHeightDp - 16).coerceAtLeast(240).dp)
                .padding(6.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = if (isLandscape) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 10.dp else 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (step) {
                            0 -> "NEW OBSESSION SHRINE"
                            1 -> "SET SHRINE PIN"
                            else -> "CONFIRM SHRINE PIN"
                        },
                        color = neon,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp,
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = DreamPalette.SilverText,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(onClick = onDismiss),
                    )
                }

                if (step == 0) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 36) name = it },
                        label = { Text("Subject / Goal Label", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        placeholder = { Text("e.g. Project 99 / Crush / Dream Car", color = DreamPalette.SteelMuted, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = neon,
                            unfocusedBorderColor = DreamPalette.GraphiteLine,
                            focusedLabelColor = neon,
                            unfocusedLabelColor = DreamPalette.SilverText,
                            focusedTextColor = DreamPalette.PureWhite,
                            unfocusedTextColor = DreamPalette.PureWhite,
                            cursorColor = neon,
                        ),
                    )

                    Text(
                        text = "NEON ACCENT",
                        color = DreamPalette.SilverText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    NeonColorPickerRow(selected = colorIdx, onSelect = { colorIdx = it })

                    // Optional PIN Lock toggle (OFF by default)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DreamPalette.AbyssGray)
                            .border(1.dp, if (enablePin) neon.copy(alpha = 0.6f) else DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
                            .clickable { enablePin = !enablePin }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("Set PIN Code Lock", color = DreamPalette.PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (enablePin) "Requires a 4–8 digit PIN every time you open this shrine"
                                else "Off — opens directly (still encrypted on disk; can add PIN later)",
                                color = DreamPalette.SteelMuted,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                            )
                        }
                        Switch(
                            checked = enablePin,
                            onCheckedChange = { enablePin = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DreamPalette.VoidBlack,
                                checkedTrackColor = neon,
                                uncheckedThumbColor = DreamPalette.SilverText,
                                uncheckedTrackColor = DreamPalette.SlateElevated,
                                uncheckedBorderColor = DreamPalette.GraphiteLine,
                            ),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        NeonButton(
                            text = if (enablePin) "NEXT: SET PIN →" else "CREATE SHRINE",
                            neon = neon,
                            filled = true,
                            enabled = name.isNotBlank(),
                            onClick = {
                                if (enablePin) step = 1
                                else onCreate(name.trim(), colorIdx, null)
                            },
                        )
                    }
                } else {
                    PinKeypadContent(
                        subtitle = if (step == 1) "Choose a 4–8 digit PIN for \"${name.trim()}\""
                        else "Re-enter PIN to seal vault",
                        neon = neon,
                        errorMessage = errorMsg,
                        confirmLabel = if (step == 1) "NEXT" else "SEAL VAULT",
                        onSubmit = { entered ->
                            if (step == 1) {
                                firstPin = entered
                                errorMsg = null
                                step = 2
                            } else {
                                if (entered == firstPin) {
                                    onCreate(name.trim(), colorIdx, entered)
                                } else {
                                    errorMsg = "PINs didn't match — try again"
                                    firstPin = ""
                                    step = 1
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun UnlockPinDialog(
    board: BoardInfo,
    onDismiss: () -> Unit,
    onSubmit: (
        pin: String,
        onWrongPin: (Int, Long) -> Unit,
        onLockedOut: (Long) -> Unit,
    ) -> Unit,
) {
    val neon = DreamPalette.neonColor(board.color)
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var lockoutUntil by remember { mutableLongStateOf(board.lockoutUntil) }
    var remainingSec by remember { mutableLongStateOf(0L) }

    LaunchedEffect(lockoutUntil) {
        while (true) {
            val rem = ((lockoutUntil - System.currentTimeMillis()) + 999L) / 1000L
            remainingSec = rem.coerceAtLeast(0L)
            if (rem <= 0L) break
            delay(500L)
        }
    }

    val config = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp || config.screenHeightDp < 480

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NeonCardBox(
            neon = neon,
            glowAlpha = 0.32f,
            modifier = Modifier
                .widthIn(max = if (isLandscape) 620.dp else 430.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = (config.screenHeightDp - 16).coerceAtLeast(240).dp)
                .padding(6.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = if (isLandscape) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "UNLOCK SHRINE",
                            color = neon,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.2.sp,
                        )
                        Text(
                            text = board.name,
                            color = DreamPalette.PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = DreamPalette.SilverText,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(onClick = onDismiss),
                    )
                }

                if (remainingSec > 0L) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DreamPalette.DangerNeon.copy(alpha = 0.14f))
                            .border(1.dp, DreamPalette.DangerNeon, RoundedCornerShape(10.dp))
                            .padding(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "VAULT COOLDOWN: ${remainingSec}s",
                            color = DreamPalette.DangerNeon,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    PinKeypadContent(
                        subtitle = "Enter PIN to derive decryption key",
                        neon = neon,
                        errorMessage = errorMsg,
                        confirmLabel = "DECRYPT",
                        onSubmit = { pin ->
                            errorMsg = null
                            onSubmit(
                                pin,
                                { fails, until ->
                                    lockoutUntil = until
                                    errorMsg = "Wrong PIN ($fails failed attempt${if (fails == 1) "" else "s"})"
                                },
                                { until ->
                                    lockoutUntil = until
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun ChangePinDialog(
    neon: Color,
    title: String = "NEW VAULT PIN",
    onDismiss: () -> Unit,
    onConfirmNewPin: (String) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var firstPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val config = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp || config.screenHeightDp < 480

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NeonCardBox(
            neon = neon,
            glowAlpha = 0.30f,
            modifier = Modifier
                .widthIn(max = if (isLandscape) 620.dp else 430.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = (config.screenHeightDp - 16).coerceAtLeast(240).dp)
                .padding(6.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = if (isLandscape) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (step == 0) title else "CONFIRM PIN",
                        color = neon,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                    Icon(Icons.Default.Close, "Close", tint = DreamPalette.SilverText, modifier = Modifier.size(22.dp).clickable(onClick = onDismiss))
                }
                PinKeypadContent(
                    subtitle = if (step == 0) "Enter new 4–8 digit PIN" else "Repeat new PIN",
                    neon = neon,
                    errorMessage = errorMsg,
                    confirmLabel = if (step == 0) "NEXT" else "RE-ENCRYPT KEY",
                    onSubmit = { pin ->
                        if (step == 0) {
                            firstPin = pin
                            errorMsg = null
                            step = 1
                        } else if (pin == firstPin) {
                            onConfirmNewPin(pin)
                        } else {
                            errorMsg = "PINs didn't match"
                            firstPin = ""
                            step = 0
                        }
                    },
                )
            }
        }
    }
}

private fun triggerKeypadHaptic(view: View, heavy: Boolean = false, errorBuzz: Boolean = false) {
    val context = view.context
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator != null && vibrator.hasVibrator()) {
        val effect = when {
            errorBuzz -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            } else {
                VibrationEffect.createWaveform(longArrayOf(0L, 28L, 45L, 35L), -1)
            }
            heavy -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            } else {
                VibrationEffect.createOneShot(18L, 180)
            }
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                VibrationEffect.createOneShot(11L, 120)
            }
        }
        runCatching { vibrator.vibrate(effect) }
    } else {
        view.performHapticFeedback(
            if (heavy) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.KEYBOARD_TAP
        )
    }
}

@Composable
fun PinKeypadContent(
    subtitle: String,
    neon: Color,
    errorMessage: String?,
    confirmLabel: String,
    onSubmit: (String) -> Unit,
) {
    var pin by remember(subtitle) { mutableStateOf("") }
    val view = LocalView.current
    val config = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp || config.screenHeightDp < 480

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            triggerKeypadHaptic(view, errorBuzz = true)
        }
    }

    val rows = remember {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("CLEAR", "0", "⌫"),
        )
    }

    @Composable
    fun PinDotsDisplay() {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DreamPalette.VoidBlack)
                .border(1.dp, DreamPalette.GraphiteLine, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp),
        ) {
            val slots = maxOf(4, pin.length.coerceAtMost(8))
            for (i in 0 until slots) {
                val filled = i < pin.length
                Box(
                    modifier = Modifier
                        .size(if (filled) 11.5.dp else 8.5.dp)
                        .clip(CircleShape)
                        .background(if (filled) neon else DreamPalette.GraphiteLine)
                )
            }
        }
    }

    @Composable
    fun PinConfirmButton(modifier: Modifier = Modifier) {
        val enabled = pin.length >= 4
        val shape = RoundedCornerShape(11.dp)
        Row(
            modifier = modifier
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        if (enabled) {
                            listOf(neon.copy(alpha = 0.42f), neon.copy(alpha = 0.26f), neon.copy(alpha = 0.42f))
                        } else {
                            listOf(neon.copy(alpha = 0.16f), DreamPalette.SlateElevated, neon.copy(alpha = 0.16f))
                        }
                    )
                )
                .border(
                    width = if (enabled) 2.dp else 1.4.dp,
                    color = if (enabled) neon else neon.copy(alpha = 0.55f),
                    shape = shape,
                )
                .clickable(enabled = enabled) {
                    triggerKeypadHaptic(view, heavy = true)
                    val submitted = pin
                    pin = ""
                    onSubmit(submitted)
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (enabled) neon else neon.copy(alpha = 0.65f))
            )
            Text(
                text = confirmLabel,
                color = if (enabled) DreamPalette.PureWhite else DreamPalette.PureWhite.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.6.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = DreamPalette.VoidBlack,
                        offset = Offset(0f, 2f),
                        blurRadius = 6f,
                    )
                ),
            )
        }
    }

    @Composable
    fun KeypadGrid(keyHeightDp: Int, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 8.dp),
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 8.dp),
                ) {
                    for (key in row) {
                        val isClear = key == "CLEAR"
                        val isBackspace = key == "⌫"
                        val isAction = isClear || isBackspace
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(keyHeightDp.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when {
                                        isBackspace -> DreamPalette.SlateElevated
                                        isClear -> DreamPalette.SlateElevated.copy(alpha = 0.92f)
                                        else -> DreamPalette.SlateElevated
                                    }
                                )
                                .border(
                                    width = if (isAction) 1.4.dp else 1.dp,
                                    color = when {
                                        isBackspace -> neon.copy(alpha = 0.72f)
                                        isClear -> neon.copy(alpha = 0.52f)
                                        else -> neon.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    triggerKeypadHaptic(view, heavy = isAction)
                                    when (key) {
                                        "CLEAR" -> pin = ""
                                        "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                        else -> if (pin.length < 8) pin += key
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                isBackspace -> {
                                    // High-visibility custom vector Backspace glyph
                                    Canvas(
                                        modifier = Modifier.size(
                                            width = if (isLandscape) 26.dp else 30.dp,
                                            height = if (isLandscape) 18.dp else 21.dp,
                                        )
                                    ) {
                                        val w = size.width
                                        val h = size.height
                                        val tagPath = Path().apply {
                                            moveTo(w * 0.30f, h * 0.08f)
                                            lineTo(w * 0.94f, h * 0.08f)
                                            lineTo(w * 0.94f, h * 0.92f)
                                            lineTo(w * 0.30f, h * 0.92f)
                                            lineTo(w * 0.06f, h * 0.50f)
                                            close()
                                        }
                                        drawPath(
                                            path = tagPath,
                                            color = neon.copy(alpha = 0.24f),
                                        )
                                        drawPath(
                                            path = tagPath,
                                            color = neon,
                                            style = Stroke(
                                                width = 2.dp.toPx(),
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round,
                                            ),
                                        )
                                        // Crisp white 'X' inside backspace tag
                                        val cx = w * 0.61f
                                        val cy = h * 0.50f
                                        val arm = h * 0.22f
                                        drawLine(
                                            color = DreamPalette.PureWhite,
                                            start = Offset(cx - arm, cy - arm),
                                            end = Offset(cx + arm, cy + arm),
                                            strokeWidth = 2.2.dp.toPx(),
                                            cap = StrokeCap.Round,
                                        )
                                        drawLine(
                                            color = DreamPalette.PureWhite,
                                            start = Offset(cx + arm, cy - arm),
                                            end = Offset(cx - arm, cy + arm),
                                            strokeWidth = 2.2.dp.toPx(),
                                            cap = StrokeCap.Round,
                                        )
                                    }
                                }
                                isClear -> {
                                    Text(
                                        text = "CLEAR",
                                        color = DreamPalette.PureWhite,
                                        fontSize = if (isLandscape) 12.5.sp else 13.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 0.8.sp,
                                    )
                                }
                                else -> {
                                    Text(
                                        text = key,
                                        color = DreamPalette.PureWhite,
                                        fontSize = if (isLandscape) 16.sp else 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (isLandscape) {
        // Side-by-side landscape layout so all 4 keypad rows + confirm button fit 100% on screen
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(0.95f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = subtitle,
                    color = DreamPalette.SilverText,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                )
                PinDotsDisplay()
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = DreamPalette.DangerNeon,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                PinConfirmButton(modifier = Modifier.fillMaxWidth())
            }

            KeypadGrid(
                keyHeightDp = 38,
                modifier = Modifier.weight(1.25f),
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = subtitle,
                color = DreamPalette.SilverText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

            PinDotsDisplay()

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = DreamPalette.DangerNeon,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }

            KeypadGrid(
                keyHeightDp = 48,
                modifier = Modifier.fillMaxWidth(),
            )

            PinConfirmButton(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun NeonColorPickerRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DreamPalette.Neons.forEachIndexed { idx, swatch ->
            val isSel = idx == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(swatch.primary.copy(alpha = if (isSel) 1f else 0.38f))
                    .border(
                        BorderStroke(if (isSel) 2.5.dp else 1.dp, if (isSel) DreamPalette.PureWhite else swatch.primary),
                        CircleShape,
                    )
                    .clickable { onSelect(idx) }
            )
        }
    }
}

@Composable
private fun RenameBoardDialog(
    board: BoardInfo,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    var name by remember { mutableStateOf(board.name) }
    var colorIdx by remember { mutableIntStateOf(board.color) }
    val neon = DreamPalette.neonColor(colorIdx)

    Dialog(onDismissRequest = onDismiss) {
        NeonCardBox(neon = neon, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("EDIT BOX STYLE", color = neon, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 36) name = it },
                    label = { Text("Box Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = neon,
                        unfocusedBorderColor = DreamPalette.GraphiteLine,
                        focusedTextColor = DreamPalette.PureWhite,
                        unfocusedTextColor = DreamPalette.PureWhite,
                        cursorColor = neon,
                    ),
                )
                NeonColorPickerRow(selected = colorIdx, onSelect = { colorIdx = it })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    NeonButton("CANCEL", DreamPalette.SilverText, onClick = onDismiss)
                    NeonButton("SAVE", neon, filled = true, enabled = name.isNotBlank(), onClick = { onSave(name, colorIdx) })
                }
            }
        }
    }
}

@Composable
private fun ShredConfirmDialog(
    board: BoardInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        NeonCardBox(neon = DreamPalette.DangerNeon, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "CRYPTO-SHRED VAULT?",
                    color = DreamPalette.DangerNeon,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                )
                Text(
                    text = "Destroys the encryption key and overwrites all ${board.imageCount} encrypted images in \"${board.name}\". This cannot be undone.",
                    color = DreamPalette.SilverText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    NeonButton("KEEP", DreamPalette.SilverText, onClick = onDismiss)
                    NeonButton("SHRED FOREVER", DreamPalette.DangerNeon, filled = true, onClick = onConfirm)
                }
            }
        }
    }
}

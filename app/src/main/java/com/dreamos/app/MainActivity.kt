package com.dreamos.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.app.ui.DreamOSTheme
import com.dreamos.app.ui.DreamPalette
import com.dreamos.app.ui.DreamViewModel
import com.dreamos.app.ui.FullscreenViewerScreen
import com.dreamos.app.ui.GalleryScreen
import com.dreamos.app.ui.ImageEditorScreen
import com.dreamos.app.ui.NeonCardBox
import com.dreamos.app.ui.RevolvingBoardScreen
import com.dreamos.app.ui.Screen
import com.dreamos.app.ui.VaultHomeScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: DreamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Block OS recents thumbnails & screenshots from leaking decrypted images to disk
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            DreamOSTheme {
                DreamOSRoot(vm = vm)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        vm.recordUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            vm.onAppBackgrounded()
        }
    }
}

/**
 * Uses ACTION_OPEN_DOCUMENT rather than the restricted Android Photo Picker (ACTION_PICK_IMAGES)
 * so Android opens the full system DocumentsUI directly with the "Recent" folder, full folder
 * navigation, Downloads, and Samsung Gallery / My Files access.
 */
private class OpenRecentImagesContract : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val uris = LinkedHashSet<Uri>()
        intent.data?.let { uris.add(it) }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        }
        return uris.toList()
    }
}

@Composable
private fun DreamOSRoot(vm: DreamViewModel) {
    val picker = rememberLauncherForActivityResult(
        contract = remember { OpenRecentImagesContract() },
    ) { uris ->
        vm.isPickingMedia = false
        vm.recordUserInteraction()
        if (uris.isNotEmpty()) {
            vm.importImages(uris)
        }
    }

    fun launchImagePicker() {
        vm.isPickingMedia = true
        picker.launch(Unit)
    }

    BackHandler(enabled = vm.screen != Screen.Home) {
        vm.handleBack()
    }

    val banner = vm.statusBanner
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(2800L)
            vm.showBanner(null)
        }
    }

    val activeSession = vm.activeSession
    val activeNeon: Color = activeSession?.let { DreamPalette.neonColor(it.info.color) } ?: DreamPalette.Neons[0].primary
    val view = LocalView.current

    // Keep screen awake while an obsession vault is open; allow normal display sleep once locked
    DisposableEffect(activeSession != null) {
        val window = (view.context as? Activity)?.window
        if (activeSession != null) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Idle session timeout: kick out of session after configured screen-on idle time
    val timeoutSec = activeSession?.meta?.sessionTimeoutSec ?: 0
    LaunchedEffect(activeSession, timeoutSec) {
        if (activeSession == null || timeoutSec <= 0) return@LaunchedEffect
        vm.recordUserInteraction()
        while (true) {
            delay(1000L)
            if (!vm.isPickingMedia) {
                val idleMs = SystemClock.elapsedRealtime() - vm.lastInteractionElapsed
                if (idleMs >= timeoutSec * 1000L) {
                    vm.lockActiveBoard()
                    vm.showBanner("Session timed out — shrine locked.")
                    break
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(DreamPalette.VoidBlack)) {
        when (val current = vm.screen) {
            Screen.Home -> {
                VaultHomeScreen(
                    boards = vm.boards,
                    onCreateBoard = { name, color, pin, onDone -> vm.createBoard(name, color, pin, onDone) },
                    onOpenUnpinnedBoard = { board -> vm.openUnpinnedBoard(board.id) },
                    onUnlockBoard = { board, pin, onWrong, onLockout, onUnlocked ->
                        vm.unlockBoard(board.id, pin, onWrong, onLockout, onUnlocked)
                    },
                    onSetPinFromHome = { boardId, pin, onDone -> vm.setPinFromHome(boardId, pin, onDone) },
                    onRenameBoard = { id, name, color -> vm.renameBoard(id, name, color) },
                    onShredBoard = { id -> vm.shredBoard(id) },
                )
            }

            Screen.Wheel, is Screen.Fullscreen -> {
                if (activeSession == null) {
                    LaunchedEffect(Unit) { vm.navigate(Screen.Home) }
                } else {
                    val isFullscreen = current is Screen.Fullscreen
                    RevolvingBoardScreen(
                        session = activeSession,
                        isOverlayOpen = isFullscreen,
                        onLock = { vm.lockActiveBoard() },
                        onOpenGallery = { vm.navigate(Screen.Gallery) },
                        onAddImages = { launchImagePicker() },
                        onImageSelected = { entry -> vm.navigate(Screen.Fullscreen(entry.id)) },
                        onChangePin = { newPin, onDone -> vm.changeActivePin(newPin, onDone) },
                    )
                    if (current is Screen.Fullscreen) {
                        FullscreenViewerScreen(
                            session = activeSession,
                            startImageId = current.startImageId,
                            onContinueWheel = { vm.navigate(Screen.Wheel) },
                            onEditImage = { imgId -> vm.navigate(Screen.Editor(imgId, returnToFullscreen = true)) },
                            onDeleteImage = { imgId -> vm.deleteImage(imgId) },
                        )
                    }
                }
            }

            Screen.Gallery -> {
                if (activeSession == null) {
                    LaunchedEffect(Unit) { vm.navigate(Screen.Home) }
                } else {
                    GalleryScreen(
                        session = activeSession,
                        onBackToWheel = { vm.navigate(Screen.Wheel) },
                        onAddImages = { launchImagePicker() },
                        onEditImage = { imgId -> vm.navigate(Screen.Editor(imgId, returnToFullscreen = false)) },
                        onAddVariation = { fromId -> vm.addVariationAndEdit(fromId, copyCutoutMasks = false) },
                        onOpenFullscreen = { imgId -> vm.navigate(Screen.Fullscreen(imgId)) },
                        onDeleteImage = { imgId -> vm.deleteImage(imgId) },
                        onChangePin = { newPin, onDone -> vm.changeActivePin(newPin, onDone) },
                    )
                }
            }

            is Screen.Editor -> {
                if (activeSession == null) {
                    LaunchedEffect(Unit) { vm.navigate(Screen.Home) }
                } else {
                    ImageEditorScreen(
                        session = activeSession,
                        imageId = current.imageId,
                        onCancel = {
                            vm.navigate(
                                if (current.returnToFullscreen) Screen.Fullscreen(current.imageId)
                                else Screen.Gallery
                            )
                        },
                        onSave = { edits, newMasks ->
                            vm.saveImageEdits(current.imageId, edits, newMasks, current.returnToFullscreen)
                        },
                        onSwitchVariation = { targetId, edits, newMasks, isDirty ->
                            if (isDirty) {
                                vm.saveImageEdits(
                                    imageId = current.imageId,
                                    edits = edits,
                                    newMasks = newMasks,
                                    returnToFullscreen = current.returnToFullscreen,
                                    nextEditorImageId = targetId,
                                )
                            } else {
                                vm.navigate(Screen.Editor(targetId, current.returnToFullscreen))
                            }
                        },
                        onCreateVariation = { copyCutout, edits, newMasks, isDirty ->
                            val pending = if (isDirty) Triple(current.imageId, edits, newMasks) else null
                            vm.addVariationAndEdit(
                                fromImageId = current.imageId,
                                copyCutoutMasks = copyCutout,
                                pendingSave = pending,
                                returnToFullscreen = current.returnToFullscreen,
                            )
                        },
                        onDeleteVariation = {
                            vm.deleteImage(
                                imageId = current.imageId,
                                fallbackToEditorSibling = true,
                                returnToFullscreen = current.returnToFullscreen,
                            )
                        },
                    )
                }
            }
        }

        // Transient status banner
        AnimatedVisibility(
            visible = banner != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 80.dp, start = 20.dp, end = 20.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(DreamPalette.SlateElevated.copy(alpha = 0.95f))
                    .border(1.dp, activeNeon, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = banner.orEmpty(),
                    color = DreamPalette.PureWhite,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Blocking cryptographic progress overlay
        val busy = vm.busyMessage
        if (busy != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DreamPalette.VoidBlack.copy(alpha = 0.78f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                NeonCardBox(neon = activeNeon, glowAlpha = 0.32f) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = activeNeon)
                        Text(
                            text = busy.uppercase(),
                            color = DreamPalette.PureWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

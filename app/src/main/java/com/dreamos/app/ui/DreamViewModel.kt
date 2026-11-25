package com.dreamos.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dreamos.app.vault.BoardInfo
import com.dreamos.app.vault.BoardSession
import com.dreamos.app.vault.Edits
import com.dreamos.app.vault.UnlockResult
import com.dreamos.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Wheel : Screen
    data object Gallery : Screen
    data class Fullscreen(val startImageId: String) : Screen
    data class Editor(val imageId: String, val returnToFullscreen: Boolean = false) : Screen
}

class DreamViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = Vault(app.applicationContext)

    var boards by mutableStateOf<List<BoardInfo>>(emptyList())
        private set

    var activeSession by mutableStateOf<BoardSession?>(null)
        private set

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var busyMessage by mutableStateOf<String?>(null)
        private set

    var statusBanner by mutableStateOf<String?>(null)
        private set

    /** True while the system photo picker is open so backgrounding doesn't lock mid-import. */
    var isPickingMedia: Boolean = false

    @Volatile
    var lastInteractionElapsed: Long = SystemClock.elapsedRealtime()

    fun recordUserInteraction() {
        lastInteractionElapsed = SystemClock.elapsedRealtime()
    }

    init {
        refreshBoards()
    }

    fun refreshBoards() {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = vault.loadIndex()
            withContext(Dispatchers.Main) {
                boards = loaded
            }
        }
    }

    fun showBanner(msg: String?) {
        statusBanner = msg
    }

    fun createBoard(name: String, color: Int, pin: String?, onDone: () -> Unit) {
        busyMessage = if (pin.isNullOrBlank()) "Creating encrypted shrine..." else "Deriving hardware-peppered key..."
        viewModelScope.launch(Dispatchers.IO) {
            val (_, session) = vault.createBoard(name.trim(), color, pin)
            val updated = vault.loadIndex()
            withContext(Dispatchers.Main) {
                activeSession?.close()
                activeSession = session
                lastInteractionElapsed = SystemClock.elapsedRealtime()
                boards = updated
                busyMessage = null
                screen = Screen.Wheel
                onDone()
            }
        }
    }

    fun openUnpinnedBoard(boardId: String) {
        busyMessage = "Opening shrine..."
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { vault.unlock(boardId, null) }.getOrNull()
            val updated = vault.loadIndex()
            withContext(Dispatchers.Main) {
                boards = updated
                busyMessage = null
                when (res) {
                    is UnlockResult.Ok -> {
                        activeSession?.close()
                        activeSession = res.session
                        lastInteractionElapsed = SystemClock.elapsedRealtime()
                        screen = Screen.Wheel
                    }
                    else -> statusBanner = "Could not open shrine."
                }
            }
        }
    }

    fun unlockBoard(
        boardId: String,
        pin: String,
        onWrongPin: (failures: Int, lockoutUntil: Long) -> Unit,
        onLockedOut: (until: Long) -> Unit,
        onUnlocked: () -> Unit,
    ) {
        busyMessage = "Decrypting shrine..."
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { vault.unlock(boardId, pin) }.getOrNull()
            val updated = vault.loadIndex()
            withContext(Dispatchers.Main) {
                boards = updated
                busyMessage = null
                when (res) {
                    is UnlockResult.Ok -> {
                        activeSession?.close()
                        activeSession = res.session
                        lastInteractionElapsed = SystemClock.elapsedRealtime()
                        screen = Screen.Wheel
                        onUnlocked()
                    }
                    is UnlockResult.WrongPin -> onWrongPin(res.failures, res.lockoutUntil)
                    is UnlockResult.LockedOut -> onLockedOut(res.until)
                    null -> statusBanner = "Could not open board."
                }
            }
        }
    }

    fun lockActiveBoard() {
        val s = activeSession
        activeSession = null
        screen = Screen.Home
        s?.close()
        refreshBoards()
    }

    fun onAppBackgrounded() {
        if (!isPickingMedia && activeSession != null) {
            lockActiveBoard()
        }
    }

    fun renameBoard(id: String, name: String, color: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = vault.rename(id, name.trim().ifBlank { "Untitled" }, color)
            withContext(Dispatchers.Main) {
                boards = updated
                activeSession?.let { s ->
                    if (s.info.id == id) {
                        s.info = s.info.copy(name = name.trim().ifBlank { "Untitled" }, color = color)
                    }
                }
            }
        }
    }

    fun shredBoard(id: String) {
        if (activeSession?.info?.id == id) {
            activeSession?.close()
            activeSession = null
            screen = Screen.Home
        }
        busyMessage = "Crypto-shredding vault..."
        viewModelScope.launch(Dispatchers.IO) {
            vault.deleteBoard(id)
            val updated = vault.loadIndex()
            withContext(Dispatchers.Main) {
                boards = updated
                busyMessage = null
                statusBanner = "Shrine permanently shredded."
            }
        }
    }

    fun changeActivePin(newPin: String?, onDone: () -> Unit) {
        val session = activeSession ?: return
        busyMessage = if (newPin.isNullOrBlank()) "Removing PIN lock..." else "Deriving PIN encryption key..."
        viewModelScope.launch(Dispatchers.IO) {
            val updated = vault.changePin(session, newPin)
            withContext(Dispatchers.Main) {
                boards = updated
                busyMessage = null
                statusBanner = if (newPin.isNullOrBlank()) "PIN removed — shrine opens directly." else "Shrine PIN enabled."
                onDone()
            }
        }
    }

    fun setPinFromHome(boardId: String, newPin: String, onDone: () -> Unit) {
        busyMessage = "Sealing shrine with PIN..."
        viewModelScope.launch(Dispatchers.IO) {
            val updated = vault.setPinOnUnpinnedBoard(boardId, newPin)
            withContext(Dispatchers.Main) {
                if (updated != null) {
                    boards = updated
                    statusBanner = "PIN lock added to shrine."
                } else {
                    statusBanner = "Could not update PIN."
                }
                busyMessage = null
                onDone()
            }
        }
    }

    fun importImages(uris: List<Uri>) {
        val session = activeSession ?: return
        if (uris.isEmpty()) return
        busyMessage = "Encrypting 1 / ${uris.size}..."
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            var imported = 0
            uris.forEachIndexed { idx, uri ->
                withContext(Dispatchers.Main) {
                    busyMessage = "Encrypting ${idx + 1} / ${uris.size}..."
                }
                if (session.importImage(ctx, uri)) imported++
            }
            val updated = vault.loadIndex()
            withContext(Dispatchers.Main) {
                boards = updated
                busyMessage = null
                statusBanner = if (imported > 0) "Encrypted $imported image${if (imported == 1) "" else "s"} into vault." else "Could not decode image."
            }
        }
    }

    fun saveImageEdits(
        imageId: String,
        edits: Edits,
        newMasks: Map<String, Bitmap>,
        returnToFullscreen: Boolean,
        nextEditorImageId: String? = null,
    ) {
        val session = activeSession ?: return
        busyMessage = "Rendering & encrypting cutout..."
        viewModelScope.launch(Dispatchers.IO) {
            session.saveEdits(imageId, edits, newMasks)
            withContext(Dispatchers.Main) {
                busyMessage = null
                screen = when {
                    nextEditorImageId != null -> Screen.Editor(nextEditorImageId, returnToFullscreen)
                    returnToFullscreen -> Screen.Fullscreen(imageId)
                    else -> Screen.Gallery
                }
            }
        }
    }

    fun addVariationAndEdit(
        fromImageId: String,
        copyCutoutMasks: Boolean = false,
        pendingSave: Triple<String, Edits, Map<String, Bitmap>>? = null,
        returnToFullscreen: Boolean = false,
    ) {
        val session = activeSession ?: return
        busyMessage = "Creating encrypted variation..."
        viewModelScope.launch(Dispatchers.IO) {
            if (pendingSave != null) {
                session.saveEdits(pendingSave.first, pendingSave.second, pendingSave.third)
            }
            val created = session.createVariation(fromImageId, copyCutoutMasks)
            val updated = vault.loadIndex()
            withContext(Dispatchers.Main) {
                boards = updated
                busyMessage = null
                if (created != null) {
                    screen = Screen.Editor(created.id, returnToFullscreen)
                }
            }
        }
    }

    fun deleteImage(imageId: String, fallbackToEditorSibling: Boolean = false, returnToFullscreen: Boolean = false) {
        val session = activeSession ?: return
        val entry = session.meta.images.firstOrNull { it.id == imageId }
        val nextSibling = entry?.let { e ->
            session.meta.images.firstOrNull { it.sourceId == e.sourceId && it.id != imageId }
        }
        viewModelScope.launch(Dispatchers.IO) {
            session.deleteImage(imageId)
            val updated = vault.loadIndex()
            withContext(Dispatchers.Main) {
                boards = updated
                if (fallbackToEditorSibling) {
                    screen = if (nextSibling != null) {
                        Screen.Editor(nextSibling.id, returnToFullscreen)
                    } else {
                        Screen.Gallery
                    }
                }
            }
        }
    }

    fun navigate(target: Screen) {
        screen = target
    }

    /** Returns true if consumed. */
    fun handleBack(): Boolean {
        return when (val s = screen) {
            Screen.Home -> false
            Screen.Wheel -> {
                lockActiveBoard()
                true
            }
            Screen.Gallery -> {
                screen = Screen.Wheel
                true
            }
            is Screen.Fullscreen -> {
                screen = Screen.Wheel
                true
            }
            is Screen.Editor -> {
                screen = if (s.returnToFullscreen) Screen.Fullscreen(s.imageId) else Screen.Gallery
                true
            }
        }
    }
}

package com.dreamos.app.vault

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed interface UnlockResult {
    data class Ok(val session: BoardSession) : UnlockResult
    data class WrongPin(val failures: Int, val lockoutUntil: Long) : UnlockResult
    data class LockedOut(val until: Long) : UnlockResult
}

/**
 * On-disk layout (all inside the app's private internal storage):
 *
 *   files/dreamos/index.bin            Keystore-encrypted list of boards
 *   files/dreamos/b/<boardId>/key.bin  PIN + Keystore wrapped board key
 *   files/dreamos/b/<boardId>/meta.bin board metadata (DEK-encrypted)
 *   files/dreamos/b/<boardId>/<random>.o|.r|.t|.m   original / rendered / thumb / mask (DEK-encrypted)
 */
class Vault(context: Context) {
    private val root = File(context.filesDir, "dreamos").apply { mkdirs() }
    private val indexFile = File(root, "index.bin")

    fun boardDir(id: String) = File(root, "b/$id")
    private fun keyFile(id: String) = File(boardDir(id), "key.bin")

    @Synchronized
    fun loadIndex(): List<BoardInfo> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val json = JSONArray(String(Crypto.decryptIndex(indexFile.readBytes())))
            List(json.length()) { BoardInfo.fromJson(json.getJSONObject(it)) }
                .filter { keyFile(it.id).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveIndex(boards: List<BoardInfo>) {
        val arr = JSONArray().also { a -> boards.forEach { a.put(it.toJson()) } }
        Storage.atomicWrite(indexFile, Crypto.encryptIndex(arr.toString().toByteArray()))
    }

    private fun update(id: String, f: (BoardInfo) -> BoardInfo): List<BoardInfo> {
        val list = loadIndex().map { if (it.id == id) f(it) else it }
        saveIndex(list)
        return list
    }

    fun createBoard(name: String, color: Int, pin: String?): Pair<BoardInfo, BoardSession> {
        val id = UUID.randomUUID().toString().replace("-", "").take(20)
        val dir = boardDir(id).apply { mkdirs() }
        val hasPin = !pin.isNullOrBlank()
        val dek = Crypto.createBoardKey(pin, keyFile(id))
        val info = BoardInfo(id, name.ifBlank { "Untitled" }, color, hasPin = hasPin)
        saveIndex(loadIndex() + info)
        val session = BoardSession(info, dek, dir) { count -> update(id) { it.copy(imageCount = count) } }
        session.saveMeta(BoardMeta())
        return info to session
    }

    fun unlock(id: String, pin: String?): UnlockResult {
        val info = loadIndex().firstOrNull { it.id == id } ?: throw IllegalStateException("Board missing")
        val now = System.currentTimeMillis()
        if (info.hasPin && info.lockoutUntil > now) return UnlockResult.LockedOut(info.lockoutUntil)

        val dek = Crypto.unwrapBoardKey(pin, keyFile(id))
        if (dek == null) {
            val fails = info.failedAttempts + 1
            // 5 free tries, then 30s, 60s, 120s ... capped at 1h.
            val lockout = if (fails >= 5) now + (30_000L shl (fails - 5).coerceAtMost(7)).coerceAtMost(3_600_000L) else 0L
            update(id) { it.copy(failedAttempts = fails, lockoutUntil = lockout) }
            return UnlockResult.WrongPin(fails, lockout)
        }
        if (info.failedAttempts != 0) update(id) { it.copy(failedAttempts = 0, lockoutUntil = 0) }
        val session = BoardSession(info, dek, boardDir(id)) { count -> update(id) { it.copy(imageCount = count) } }
        session.load()
        return UnlockResult.Ok(session)
    }

    fun changePin(session: BoardSession, newPin: String?): List<BoardInfo> {
        val hasPin = !newPin.isNullOrBlank()
        Crypto.writeWrapped(session.dekForRewrap(), newPin, keyFile(session.info.id))
        val updated = update(session.info.id) {
            it.copy(hasPin = hasPin, failedAttempts = 0, lockoutUntil = 0L)
        }
        session.info = session.info.copy(hasPin = hasPin, failedAttempts = 0, lockoutUntil = 0L)
        return updated
    }

    /** Sets a PIN directly on an currently-unpinned board from the Home screen. */
    fun setPinOnUnpinnedBoard(id: String, newPin: String): List<BoardInfo>? {
        val dek = Crypto.unwrapBoardKey(null, keyFile(id)) ?: return null
        Crypto.writeWrapped(dek, newPin, keyFile(id))
        return update(id) { it.copy(hasPin = true, failedAttempts = 0, lockoutUntil = 0L) }
    }

    fun rename(id: String, name: String, color: Int) = update(id) { it.copy(name = name, color = color) }

    /** Crypto-shred: destroy the wrapped key first, then overwrite + delete the blobs. */
    fun deleteBoard(id: String) {
        Storage.shred(keyFile(id))
        Storage.shred(boardDir(id))
        saveIndex(loadIndex().filter { it.id != id })
    }
}

internal fun JSONObject.bytes() = toString().toByteArray()

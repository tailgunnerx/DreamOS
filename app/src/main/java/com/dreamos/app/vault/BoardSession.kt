package com.dreamos.app.vault

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dreamos.app.image.ImageOps
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey

private data class RenderedResult(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val opaqueMask: Long,
    val sweetMask: Long,
)

/**
 * An unlocked board. Holds the board key in memory only while unlocked; [close] drops it along
 * with every decrypted bitmap so nothing lingers after the app is backgrounded.
 */
class BoardSession(
    initialInfo: BoardInfo,
    private val dek: SecretKey,
    private val dir: File,
    private val onCountChanged: (Int) -> Unit,
) {
    var info by mutableStateOf(initialInfo)
    var meta by mutableStateOf(BoardMeta())
        private set

    // Persistent wheel playback state across Fullscreen / Gallery / Editor navigation
    var wheelPhase by mutableStateOf(0f)
    var wheelSeed by mutableStateOf((System.nanoTime() and 0x7FFFFFFF).toInt())
    var wheelAutoRotating by mutableStateOf(true)
    var wheelHudVisible by mutableStateOf<Boolean?>(null)

    private val lock = Any()
    @Volatile var closed = false
        private set

    private val thumbs = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 4 / 1024).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }
    private val displays = LruCache<String, ImageBitmap>(6)

    internal fun dekForRewrap() = dek

    // ------------------------------------------------------------------ blobs

    private fun file(name: String) = File(dir, name)
    private fun writeBlob(name: String, bytes: ByteArray) = Storage.atomicWrite(file(name), Crypto.encrypt(dek, bytes, aad = name))
    private fun readBlob(name: String): ByteArray = Crypto.decrypt(dek, file(name).readBytes(), aad = name)

    private fun newId() = UUID.randomUUID().toString().replace("-", "")

    // ------------------------------------------------------------------ meta

    fun load() {
        val f = file("meta.bin")
        val loaded = if (f.exists()) BoardMeta.fromJson(JSONObject(String(Crypto.decrypt(dek, f.readBytes(), aad = "meta")))) else BoardMeta()
        meta = loaded

        // Auto-repair any pre-update images whose `.o` master had native PNG alpha, lacked 8x8 silhouette masks, or needed tight cluster trimming
        if (loaded.images.any { !it.alphaChecked || (!it.opaqueChecked && it.hasAlpha) || (it.hasAlpha && !it.tightTrimmed) }) {
            var changed = false
            val upgraded = loaded.images.map { entry ->
                if (!entry.alphaChecked || (entry.hasAlpha && !entry.tightTrimmed)) {
                    val src = loadOriginal(entry, ImageOps.RENDER_SOURCE_MAX)
                    if (src != null) {
                        val nativeAlpha = ImageOps.hasTransparentPixels(src)
                        if (nativeAlpha || entry.edits.hasTransparency || entry.hasAlpha) {
                            val res = writeRendered(entry.id, src, entry.edits, forceAlpha = nativeAlpha || entry.hasAlpha) { loadMask(it) }
                            changed = true
                            entry.copy(
                                renderW = res.width,
                                renderH = res.height,
                                hasAlpha = res.hasAlpha,
                                alphaChecked = true,
                                opaqueMask = res.opaqueMask,
                                opaqueChecked = true,
                                sweetMask = res.sweetMask,
                                tightTrimmed = true,
                                version = entry.version + 1,
                            )
                        } else {
                            changed = true
                            entry.copy(hasAlpha = false, alphaChecked = true, opaqueMask = -1L, opaqueChecked = true, tightTrimmed = true)
                        }
                    } else {
                        entry.copy(alphaChecked = true, opaqueChecked = true, tightTrimmed = true)
                    }
                } else if (!entry.opaqueChecked && entry.hasAlpha) {
                    val thumbBmp = runCatching { ImageOps.decode(readBlob("${entry.id}.t"), ImageOps.THUMB_MAX) }.getOrNull()
                    val om = if (thumbBmp != null) ImageOps.computeGridBitmask8x8(thumbBmp, 32, 0.10f) else -1L
                    changed = true
                    entry.copy(opaqueMask = om, opaqueChecked = true, tightTrimmed = true)
                } else {
                    entry
                }
            }
            if (changed) saveMeta(loaded.copy(images = upgraded))
        }
    }

    fun saveMeta(m: BoardMeta) = synchronized(lock) {
        val countChanged = m.images.size != meta.images.size || !file("meta.bin").exists()
        Storage.atomicWrite(file("meta.bin"), Crypto.encrypt(dek, m.toJson().bytes(), aad = "meta"))
        meta = m
        if (countChanged) onCountChanged(m.images.size)
    }

    fun updateSettings(f: (BoardMeta) -> BoardMeta) = synchronized(lock) { saveMeta(f(meta)) }

    private fun mutate(f: (BoardMeta) -> BoardMeta) = synchronized(lock) { saveMeta(f(meta)) }

    fun setMode(mode: DisplayMode) = mutate { it.copy(mode = mode) }
    fun setMaximumMode(enabled: Boolean) = mutate { it.copy(maximumMode = enabled) }
    fun setSpeedMultiplier(mult: Float) = mutate { it.copy(speedMultiplier = mult.coerceIn(0.05f, 12.0f)) }
    fun setMantra(mantra: String) = mutate { it.copy(mantra = mantra.trim()) }

    fun addPhrase(text: String, colorArgb: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val item = PhraseItem(id = newId().take(12), text = trimmed, colorArgb = colorArgb)
        mutate { it.copy(phrases = it.phrases + item, mantra = "") }
        return true
    }

    fun updatePhraseColor(id: String, colorArgb: Int) = mutate { m ->
        m.copy(phrases = m.phrases.map { if (it.id == id) it.copy(colorArgb = colorArgb) else it })
    }

    fun removePhrase(id: String) = mutate { m ->
        m.copy(phrases = m.phrases.filterNot { it.id == id })
    }

    fun bumpHeat(id: String) = mutate { m -> m.copy(images = m.images.map { if (it.id == id) it.copy(heat = it.heat + 1) else it }) }

    // ------------------------------------------------------------------ images & variations

    /** Imports one picked image. Preserves native PNG/WebP transparency automatically! */
    fun importImage(context: Context, uri: Uri): Boolean {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val norm = ImageOps.normalizeImport(raw) ?: return false
        raw.fill(0)
        val id = newId()
        writeBlob("$id.o", norm.bytes)
        val src = ImageOps.scaleDown(norm.bitmap, ImageOps.RENDER_SOURCE_MAX)
        val res = writeRendered(id, src, Edits(), forceAlpha = norm.hasAlpha) { null }
        val entry = ImageEntry(
            id = id,
            sourceId = id,
            origW = norm.bitmap.width,
            origH = norm.bitmap.height,
            renderW = res.width,
            renderH = res.height,
            hasAlpha = res.hasAlpha,
            alphaChecked = true,
            opaqueMask = res.opaqueMask,
            opaqueChecked = true,
            sweetMask = res.sweetMask,
            tightTrimmed = true,
        )
        mutate { it.copy(images = it.images + entry) }
        return true
    }

    /**
     * Spawns a new variation sharing [fromImageId]'s encrypted master `.o` file without
     * duplicating it on disk. Each variation gets its own independent crop, lasso/AI masks,
     * sweet-spot mask, and slot in the revolving wheel/waterfall.
     */
    fun createVariation(fromImageId: String, copyCutoutMasks: Boolean = false): ImageEntry? {
        val base = meta.images.firstOrNull { it.id == fromImageId } ?: return null
        val sid = base.sourceId
        val varId = newId()
        val initialEdits = if (copyCutoutMasks) {
            Edits(
                crop = null,
                ops = base.edits.ops,
                sweetOps = base.edits.sweetOps,
                sweetConfigured = base.edits.sweetConfigured,
            )
        } else Edits()
        val src = loadOriginal(base, ImageOps.RENDER_SOURCE_MAX) ?: return null
        val res = writeRendered(varId, src, initialEdits) { maskId -> loadMask(maskId) }
        val created = ImageEntry(
            id = varId,
            sourceId = sid,
            origW = base.origW,
            origH = base.origH,
            renderW = res.width,
            renderH = res.height,
            edits = initialEdits,
            hasAlpha = res.hasAlpha,
            alphaChecked = true,
            opaqueMask = res.opaqueMask,
            opaqueChecked = true,
            sweetMask = res.sweetMask,
            tightTrimmed = true,
        )
        mutate { m ->
            val lastSiblingIdx = m.images.indexOfLast { it.sourceId == sid }
            val updated = if (lastSiblingIdx >= 0) {
                m.images.subList(0, lastSiblingIdx + 1) + created + m.images.subList(lastSiblingIdx + 1, m.images.size)
            } else {
                m.images + created
            }
            m.copy(images = updated)
        }
        return created
    }

    private fun writeRendered(
        id: String,
        src: Bitmap,
        edits: Edits,
        forceAlpha: Boolean = false,
        loadMask: (String) -> Bitmap?,
    ): RenderedResult {
        val rawRendered = ImageOps.render(src, edits, loadMask)
        val transparent = forceAlpha || edits.hasTransparency || ImageOps.hasTransparentPixels(rawRendered)
        val trimRect = if (transparent) ImageOps.findOpaqueBounds(rawRendered) else null
        val fitted = if (trimRect != null) {
            Bitmap.createBitmap(rawRendered, trimRect.left, trimRect.top, trimRect.width(), trimRect.height()).apply {
                setHasAlpha(true)
            }
        } else rawRendered

        val display = ImageOps.scaleDown(fitted, ImageOps.DISPLAY_MAX)
        writeBlob("$id.r", ImageOps.encode(display, transparent = transparent))
        val thumb = ImageOps.scaleDown(display, ImageOps.THUMB_MAX)
        writeBlob("$id.t", ImageOps.encode(thumb, transparent = transparent, quality = 88))

        val opaqueMask = if (transparent) {
            ImageOps.computeGridBitmask8x8(thumb, alphaThreshold = 32, minCoverageRatio = 0.10f)
        } else {
            -1L
        }

        val sweetMask = if (edits.sweetOps.isNotEmpty()) {
            val fullSweet = ImageOps.buildSweetMask(src.width, src.height, edits.sweetOps)
            if (fullSweet != null) {
                val croppedSweet = ImageOps.crop(fullSweet, edits.crop)
                val trimmedSweet = if (trimRect != null &&
                    trimRect.left + trimRect.width() <= croppedSweet.width &&
                    trimRect.top + trimRect.height() <= croppedSweet.height
                ) {
                    Bitmap.createBitmap(croppedSweet, trimRect.left, trimRect.top, trimRect.width(), trimRect.height())
                } else croppedSweet
                ImageOps.computeGridBitmask8x8(trimmedSweet, alphaThreshold = 64, minCoverageRatio = 0.06f)
            } else 0L
        } else 0L

        return RenderedResult(display.width, display.height, transparent, opaqueMask, sweetMask)
    }

    fun loadOriginal(entry: ImageEntry, maxDim: Int): Bitmap? {
        val masterName = if (file("${entry.sourceId}.o").exists()) "${entry.sourceId}.o" else "${entry.id}.o"
        return runCatching { ImageOps.decode(readBlob(masterName), maxDim) }.getOrNull()
    }

    fun loadMask(maskId: String): Bitmap? = runCatching { ImageOps.decode(readBlob("$maskId.m"), ImageOps.ORIGINAL_MAX) }.getOrNull()

    /**
     * Re-renders an image (or variation) from its untouched master original with the given edits.
     */
    fun saveEdits(imageId: String, edits: Edits, newMasks: Map<String, Bitmap>) {
        val entry = meta.images.firstOrNull { it.id == imageId } ?: return
        val referenced = edits.ops.filterIsInstance<MaskOp.AiCutout>().map { it.maskId }.toSet()
        newMasks.filterKeys { it in referenced }.forEach { (k, v) -> writeBlob("$k.m", ImageOps.encode(v, transparent = true)) }

        val tightCrop = edits.crop?.let { it.width < 0.55f || it.height < 0.55f } == true
        val decodeMax = if (tightCrop) ImageOps.ORIGINAL_MAX else ImageOps.RENDER_SOURCE_MAX
        val src = loadOriginal(entry, decodeMax) ?: return
        val res = writeRendered(imageId, src, edits) { id -> newMasks[id] ?: loadMask(id) }

        val otherMasksInUse = meta.images
            .filter { it.id != imageId }
            .flatMap { it.edits.ops.filterIsInstance<MaskOp.AiCutout>().map { m -> m.maskId } }
            .toSet()

        entry.edits.ops.filterIsInstance<MaskOp.AiCutout>().map { it.maskId }
            .filter { it !in referenced && it !in otherMasksInUse }
            .forEach { Storage.shred(file("$it.m")) }

        mutate { m ->
            m.copy(images = m.images.map {
                if (it.id == imageId) {
                    it.copy(
                        edits = edits,
                        renderW = res.width,
                        renderH = res.height,
                        hasAlpha = res.hasAlpha,
                        alphaChecked = true,
                        opaqueMask = res.opaqueMask,
                        opaqueChecked = true,
                        sweetMask = res.sweetMask,
                        tightTrimmed = true,
                        version = it.version + 1,
                    )
                } else it
            })
        }
    }

    fun deleteImage(imageId: String) {
        val entry = meta.images.firstOrNull { it.id == imageId } ?: return
        val remaining = meta.images.filter { it.id != imageId }
        mutate { m -> m.copy(images = remaining) }

        listOf("r", "t").forEach { Storage.shred(file("$imageId.$it")) }

        if (remaining.none { it.sourceId == entry.sourceId }) {
            Storage.shred(file("${entry.sourceId}.o"))
        }

        val stillUsedMasks = remaining
            .flatMap { it.edits.ops.filterIsInstance<MaskOp.AiCutout>().map { m -> m.maskId } }
            .toSet()
        entry.edits.ops.filterIsInstance<MaskOp.AiCutout>()
            .map { it.maskId }
            .filter { it !in stillUsedMasks }
            .forEach { Storage.shred(file("$it.m")) }
    }

    // ------------------------------------------------------------------ decrypted caches

    fun cachedThumb(entry: ImageEntry): ImageBitmap? = thumbs.get(entry.cacheKey)

    fun loadThumb(entry: ImageEntry): ImageBitmap? {
        if (closed) return null
        thumbs.get(entry.cacheKey)?.let { return it }
        return runCatching {
            ImageOps.decode(readBlob("${entry.id}.t"), ImageOps.THUMB_MAX)?.asImageBitmap()
        }.getOrNull()?.also { if (!closed) thumbs.put(entry.cacheKey, it) }
    }

    fun cachedDisplay(entry: ImageEntry): ImageBitmap? = displays.get(entry.cacheKey)

    fun loadDisplay(entry: ImageEntry): ImageBitmap? {
        if (closed) return null
        displays.get(entry.cacheKey)?.let { return it }
        return runCatching {
            ImageOps.decode(readBlob("${entry.id}.r"), ImageOps.DISPLAY_MAX)?.asImageBitmap()
        }.getOrNull()?.also { if (!closed) displays.put(entry.cacheKey, it) }
    }

    /**
     * Decodes a higher-resolution (up to 3200px) crisp view from the encrypted master `.o`
     * when the user zooms into an image in FullscreenViewerScreen.
     */
    fun loadHighResDisplay(entry: ImageEntry): ImageBitmap? {
        if (closed) return null
        return runCatching {
            val src = loadOriginal(entry, ImageOps.ORIGINAL_MAX) ?: return@runCatching null
            val raw = if (entry.edits == Edits()) src else {
                ImageOps.render(src, entry.edits) { maskId -> loadMask(maskId) }
            }
            val transparent = entry.hasAlpha || entry.edits.hasTransparency
            val trimmed = if (transparent) ImageOps.trimTransparentBounds(raw) else raw
            ImageOps.scaleDown(trimmed, 3200).asImageBitmap()
        }.getOrNull()
    }

    fun close() {
        closed = true
        thumbs.evictAll()
        displays.evictAll()
        meta = BoardMeta()
    }
}

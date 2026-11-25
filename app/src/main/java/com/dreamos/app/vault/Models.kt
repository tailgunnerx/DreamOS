package com.dreamos.app.vault

import org.json.JSONArray
import org.json.JSONObject

/** Entry in the (Keystore-encrypted) home index. Never contains images. */
data class BoardInfo(
    val id: String,
    val name: String,
    val color: Int,
    val imageCount: Int = 0,
    val hasPin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val failedAttempts: Int = 0,
    val lockoutUntil: Long = 0L,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("color", color); put("count", imageCount)
        put("hasPin", hasPin)
        put("created", createdAt); put("fails", failedAttempts); put("lockout", lockoutUntil)
    }

    companion object {
        fun fromJson(o: JSONObject) = BoardInfo(
            id = o.getString("id"),
            name = o.getString("name"),
            color = o.optInt("color", 0),
            imageCount = o.optInt("count", 0),
            // Legacy boards created before optional-PIN defaulted to having a PIN
            hasPin = o.optBoolean("hasPin", true),
            createdAt = o.optLong("created"),
            failedAttempts = o.optInt("fails", 0),
            lockoutUntil = o.optLong("lockout", 0L),
        )
    }
}

/** Normalised (0..1) rectangle in ORIGINAL image coordinates. */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun toJson() = JSONArray().apply { put(left.toDouble()); put(top.toDouble()); put(right.toDouble()); put(bottom.toDouble()) }

    companion object {
        val FULL = NRect(0f, 0f, 1f, 1f)
        fun fromJson(a: JSONArray) = NRect(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
    }
}

enum class LassoMode { KEEP, ERASE, RESTORE }

/**
 * Non-destructive edit operations. All coordinates are normalised to the ORIGINAL image, so
 * the original is never modified and every edit can be undone / re-applied at any resolution.
 */
sealed interface MaskOp {
    /** Closed polygon loop: points = x0,y0,x1,y1... normalised 0..1 */
    data class Lasso(val mode: LassoMode, val points: FloatArray) : MaskOp

    /** Finger brush stroke: erase = true clears to transparent, false restores original pixels. */
    data class Brush(val erase: Boolean, val radiusNorm: Float, val points: FloatArray) : MaskOp

    /** Alpha mask produced by on-device subject segmentation, stored as an encrypted blob. */
    data class AiCutout(val maskId: String) : MaskOp

    fun toJson(): JSONObject = when (this) {
        is Lasso -> JSONObject().apply {
            put("t", "lasso"); put("mode", mode.name)
            put("p", JSONArray().also { arr -> points.forEach { arr.put(it.toDouble()) } })
        }
        is Brush -> JSONObject().apply {
            put("t", "brush"); put("e", erase); put("r", radiusNorm.toDouble())
            put("p", JSONArray().also { arr -> points.forEach { arr.put(it.toDouble()) } })
        }
        is AiCutout -> JSONObject().apply { put("t", "ai"); put("mask", maskId) }
    }

    companion object {
        fun fromJson(o: JSONObject): MaskOp = when (o.getString("t")) {
            "lasso" -> {
                val a = o.getJSONArray("p")
                Lasso(LassoMode.valueOf(o.getString("mode")), FloatArray(a.length()) { a.getDouble(it).toFloat() })
            }
            "brush" -> {
                val a = o.getJSONArray("p")
                Brush(
                    erase = o.optBoolean("e", true),
                    radiusNorm = o.optDouble("r", 0.03).toFloat(),
                    points = FloatArray(a.length()) { a.getDouble(it).toFloat() },
                )
            }
            else -> AiCutout(o.getString("mask"))
        }
    }
}

data class Edits(
    val crop: NRect? = null,
    val ops: List<MaskOp> = emptyList(),
    /** Optional per-image "Sweet Spot" mask ops (normalised to ORIGINAL image) that must stay uncovered in dense/maximum mode. */
    val sweetOps: List<MaskOp> = emptyList(),
    /** True if user explicitly configured sweet spots (distinguishes intentionally cleared = filler vs untouched = auto-focal). */
    val sweetConfigured: Boolean = false,
) {
    val isEmpty get() = crop == null && ops.isEmpty() && sweetOps.isEmpty() && !sweetConfigured
    val hasTransparency get() = ops.isNotEmpty()

    fun toJson() = JSONObject().apply {
        crop?.let { put("crop", it.toJson()) }
        put("ops", JSONArray().also { arr -> ops.forEach { arr.put(it.toJson()) } })
        if (sweetOps.isNotEmpty()) {
            put("sweet", JSONArray().also { arr -> sweetOps.forEach { arr.put(it.toJson()) } })
        }
        if (sweetConfigured) put("scfg", true)
    }

    companion object {
        fun fromJson(o: JSONObject?): Edits {
            if (o == null) return Edits()
            val crop = o.optJSONArray("crop")?.let { NRect.fromJson(it) }
            val opsArr = o.optJSONArray("ops") ?: JSONArray()
            val sweetArr = o.optJSONArray("sweet") ?: JSONArray()
            val sweetList = List(sweetArr.length()) { MaskOp.fromJson(sweetArr.getJSONObject(it)) }
            return Edits(
                crop = crop,
                ops = List(opsArr.length()) { MaskOp.fromJson(opsArr.getJSONObject(it)) },
                sweetOps = sweetList,
                sweetConfigured = o.optBoolean("scfg", sweetList.isNotEmpty()),
            )
        }
    }
}

data class ImageEntry(
    val id: String,
    /** Master encrypted `.o` blob ID. Variations share the same [sourceId] without duplicating the master. */
    val sourceId: String = id,
    val origW: Int,
    val origH: Int,
    /** Size of the rendered (edited) display image — drives aspect ratio in the wheel. */
    val renderW: Int,
    val renderH: Int,
    val edits: Edits = Edits(),
    /** True if the rendered image has transparent pixels (either native PNG alpha or cutout edits). */
    val hasAlpha: Boolean = edits.hasTransparency,
    /** Migration marker so pre-update transparent PNGs get re-encoded from `.o` as true PNGs. */
    val alphaChecked: Boolean = false,
    /** 8x8 bitmask over rendered image: bit (gy*8 + gx) = 1 where subject pixels are opaque. */
    val opaqueMask: Long = -1L,
    /** True once [opaqueMask] has been computed from the actual rendered bitmap. */
    val opaqueChecked: Boolean = false,
    /** 8x8 bitmask over rendered image: bit (gy*8 + gx) = 1 where user marked a "Sweet Spot" that shouldn't be covered. */
    val sweetMask: Long = 0L,
    /** True once transparent cutouts have been tightly auto-trimmed with cluster-filtered bounds. */
    val tightTrimmed: Boolean = false,
    /** How often the user opened this image full screen. Hot images get highlighted more. */
    val heat: Int = 0,
    /** Bumped on every edit so caches invalidate. */
    val version: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val aspect: Float get() = if (renderH == 0) 1f else renderW.toFloat() / renderH
    val cacheKey get() = "$id#$version"
    val isVariation: Boolean get() = sourceId != id
    val hasCustomSweetSpot: Boolean get() = sweetMask != 0L

    fun toJson() = JSONObject().apply {
        put("id", id); put("sid", sourceId)
        put("ow", origW); put("oh", origH); put("rw", renderW); put("rh", renderH)
        put("edits", edits.toJson())
        put("alpha", hasAlpha); put("ac", alphaChecked)
        put("om", opaqueMask); put("oc", opaqueChecked)
        put("sm", sweetMask); put("tt", tightTrimmed)
        put("heat", heat); put("v", version); put("added", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ImageEntry {
            val id = o.getString("id")
            val edits = Edits.fromJson(o.optJSONObject("edits"))
            return ImageEntry(
                id = id,
                sourceId = o.optString("sid", id).ifBlank { id },
                origW = o.getInt("ow"), origH = o.getInt("oh"),
                renderW = o.getInt("rw"), renderH = o.getInt("rh"),
                edits = edits,
                hasAlpha = o.optBoolean("alpha", edits.hasTransparency),
                alphaChecked = o.optBoolean("ac", false),
                opaqueMask = o.optLong("om", -1L),
                opaqueChecked = o.optBoolean("oc", false),
                sweetMask = o.optLong("sm", 0L),
                tightTrimmed = o.optBoolean("tt", false),
                heat = o.optInt("heat", 0),
                version = o.optInt("v", 0),
                addedAt = o.optLong("added"),
            )
        }
    }
}

enum class DisplayMode { WHEEL, WATERFALL }

/**
 * Encrypted background phrase item. [colorArgb] == 0 means use the shrine's neon swatch;
 * otherwise full 0xFFRRGGBB color chosen by the user.
 */
data class PhraseItem(
    val id: String,
    val text: String,
    val colorArgb: Int = 0,
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("color", colorArgb)
    }

    companion object {
        fun fromJson(o: JSONObject) = PhraseItem(
            id = o.optString("id", "").ifBlank { java.util.UUID.randomUUID().toString().replace("-", "").take(12) },
            text = o.optString("text", ""),
            colorArgb = o.optInt("color", 0),
        )
    }
}

data class BoardMeta(
    val images: List<ImageEntry> = emptyList(),
    val mode: DisplayMode = DisplayMode.WHEEL,
    /** Maximum Mode: packs the wall with as many ref images as possible while keeping Sweet Spots uncovered. */
    val maximumMode: Boolean = false,
    /** Granular speed multiplier: 0.05x .. 12.00x */
    val speedMultiplier: Float = 1.0f,
    /** Whether rectangular images draw a card frame + neon border. Default false (borderless). */
    val showBorders: Boolean = false,
    /** Whether transparent cutouts draw a sticker outline/glow. Default false (clean transparent PNGs). */
    val showCutoutOutline: Boolean = false,
    /** Whether the wall renders the neon radial background aura (false = pure pitch black #000000). */
    val showWallBackground: Boolean = true,
    /** Dynamic size variation bounds on the wheel/waterfall. */
    val minSize: Float = 0.48f,
    val maxSize: Float = 1.48f,
    /** Spacing multiplier (0.70 = dense/overlapping, 1.28 = minimal overlap, 1.80 = airy/zero overlap). */
    val spacing: Float = 1.28f,
    /** Max random poster tilt in degrees (0 = upright). */
    val tiltDegrees: Float = 5.0f,
    /** Automatically start in pure zero-UI fullscreen when unlocking the vault. */
    val startUiHidden: Boolean = false,
    /** How long screen stays on without touch before locking out of the session (0 = Never, default 300s = 5m). */
    val sessionTimeoutSec: Int = 300,
    /** Legacy single mantra (auto-migrated into [phrases]). */
    val mantra: String = "",
    /** Unlimited encrypted background phrases, each with its own optional custom color. */
    val phrases: List<PhraseItem> = emptyList(),
    /** False = fixed [phraseIntervalSec] timer; True = random timer between [phraseRandomMinSec]..[phraseRandomMaxSec]. */
    val phraseRandomTiming: Boolean = false,
    /** Seconds per phrase when using fixed time limit (2..300). */
    val phraseIntervalSec: Int = 8,
    /** Minimum seconds before changing phrase in random timing mode (2..300). */
    val phraseRandomMinSec: Int = 4,
    /** Maximum seconds before changing phrase in random timing mode (2..300). */
    val phraseRandomMaxSec: Int = 15,
    /** True = pick random next phrase instead of sequential order. */
    val phraseShuffleOrder: Boolean = false,
    /** Subtle background opacity (0.04..0.55, default 0.14 = 14%) so phrases never mesh with foreground images. */
    val phraseOpacity: Float = 0.14f,
) {
    fun toJson() = JSONObject().apply {
        put("images", JSONArray().also { arr -> images.forEach { arr.put(it.toJson()) } })
        put("mode", mode.name)
        put("maxMode", maximumMode)
        put("spdF", speedMultiplier.toDouble())
        put("borders", showBorders)
        put("cutOutline", showCutoutOutline)
        put("wallBg", showWallBackground)
        put("minSz", minSize.toDouble())
        put("maxSz", maxSize.toDouble())
        put("spacing", spacing.toDouble())
        put("tilt", tiltDegrees.toDouble())
        put("hideUiStart", startUiHidden)
        put("timeoutSec", sessionTimeoutSec)
        put("mantra", mantra)
        put("phrases", JSONArray().also { arr -> phrases.forEach { arr.put(it.toJson()) } })
        put("phRand", phraseRandomTiming)
        put("phInt", phraseIntervalSec)
        put("phMin", phraseRandomMinSec)
        put("phMax", phraseRandomMaxSec)
        put("phShuf", phraseShuffleOrder)
        put("phAlpha", phraseOpacity.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): BoardMeta {
            val arr = o.optJSONArray("images") ?: JSONArray()
            val legacySpeed = when (o.optInt("speed", 1)) {
                0 -> 0.55f
                2 -> 1.65f
                else -> 1.0f
            }
            val spd = if (o.has("spdF")) o.optDouble("spdF", 1.0).toFloat() else legacySpeed
            val minS = o.optDouble("minSz", 0.48).toFloat().coerceIn(0.20f, 1.50f)
            val maxS = o.optDouble("maxSz", 1.48).toFloat().coerceIn(minS + 0.05f, 2.30f)
            val legacyMantra = o.optString("mantra", "")
            val parsedPhrases = if (o.has("phrases")) {
                val pArr = o.optJSONArray("phrases") ?: JSONArray()
                List(pArr.length()) { PhraseItem.fromJson(pArr.getJSONObject(it)) }
                    .filter { it.text.isNotBlank() }
            } else if (legacyMantra.isNotBlank()) {
                listOf(PhraseItem(id = "legacy0", text = legacyMantra.trim(), colorArgb = 0))
            } else {
                emptyList()
            }
            val pMin = o.optInt("phMin", 4).coerceIn(2, 300)
            val pMax = o.optInt("phMax", 15).coerceIn(pMin, 300)
            return BoardMeta(
                images = List(arr.length()) { ImageEntry.fromJson(arr.getJSONObject(it)) },
                mode = runCatching { DisplayMode.valueOf(o.optString("mode")) }.getOrDefault(DisplayMode.WHEEL),
                maximumMode = o.optBoolean("maxMode", false),
                speedMultiplier = spd.coerceIn(0.05f, 12.0f),
                showBorders = o.optBoolean("borders", false),
                showCutoutOutline = o.optBoolean("cutOutline", false),
                showWallBackground = o.optBoolean("wallBg", true),
                minSize = minS,
                maxSize = maxS,
                spacing = o.optDouble("spacing", 1.28).toFloat().coerceIn(0.65f, 1.90f),
                tiltDegrees = o.optDouble("tilt", 5.0).toFloat().coerceIn(0f, 18f),
                startUiHidden = o.optBoolean("hideUiStart", false),
                sessionTimeoutSec = o.optInt("timeoutSec", 300).coerceIn(0, 7200),
                mantra = if (o.has("phrases")) "" else legacyMantra,
                phrases = parsedPhrases,
                phraseRandomTiming = o.optBoolean("phRand", false),
                phraseIntervalSec = o.optInt("phInt", 8).coerceIn(2, 300),
                phraseRandomMinSec = pMin,
                phraseRandomMaxSec = pMax,
                phraseShuffleOrder = o.optBoolean("phShuf", false),
                phraseOpacity = o.optDouble("phAlpha", 0.14).toFloat().coerceIn(0.04f, 0.55f),
            )
        }
    }
}

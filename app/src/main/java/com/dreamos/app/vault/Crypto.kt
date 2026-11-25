package com.dreamos.app.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * DreamOS key hierarchy
 * ---------------------
 *  * App index key  (Android Keystore, AES-256-GCM, non-exportable)
 *      -> encrypts the list of boards (names, colours, counters).
 *  * Pepper key     (Android Keystore, HMAC-SHA256, non-exportable, StrongBox if available)
 *      -> every PIN guess must pass through this device's secure hardware, so a forensic copy
 *         of storage can't be brute-forced offline even though PINs are short.
 *  * PIN key        = PBKDF2-SHA256( HMAC_pepper(salt || pin), salt, 250k iterations )
 *  * Board DEK      (random 256-bit AES key, one per board)
 *      -> wrapped by the PIN key (AES-GCM; a bad tag == wrong PIN), then wrapped again by the
 *         Keystore wrap key. Every image / mask / metadata blob is AES-256-GCM under the DEK,
 *         with the blob's name bound in as associated data so files can't be swapped around.
 *
 * Deleting a board deletes its wrapped DEK first ("crypto-shredding"): even if flash storage
 * keeps stale copies of image blocks, they are undecryptable noise.
 */
object Crypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val INDEX_KEY = "dreamos_index_v1"
    private const val WRAP_KEY = "dreamos_wrap_v1"
    private const val PEPPER_KEY = "dreamos_pepper_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    const val PBKDF2_ITERATIONS = 250_000

    private val random = SecureRandom()
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ---------------------------------------------------------------- Keystore keys

    @Synchronized
    private fun keystoreAes(alias: String): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    private fun pepperKey(): SecretKey {
        (keyStore.getKey(PEPPER_KEY, null) as? SecretKey)?.let { return it }
        fun build(strongBox: Boolean): SecretKey {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(PEPPER_KEY, KeyProperties.PURPOSE_SIGN)
            if (strongBox && Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
            gen.init(spec.build())
            return gen.generateKey()
        }
        return try {
            build(strongBox = true)
        } catch (e: Exception) {
            runCatching { keyStore.deleteEntry(PEPPER_KEY) }
            build(strongBox = false)
        }
    }

    private fun keystoreEncrypt(alias: String, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreAes(alias))
        val iv = c.iv
        return iv + c.doFinal(plain)
    }

    private fun keystoreDecrypt(alias: String, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreAes(alias), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_LEN))
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    fun encryptIndex(plain: ByteArray) = keystoreEncrypt(INDEX_KEY, plain)
    fun decryptIndex(blob: ByteArray) = keystoreDecrypt(INDEX_KEY, blob)

    // ---------------------------------------------------------------- software AES-GCM (DEK)

    fun encrypt(key: SecretKey, plain: ByteArray, aad: String? = null): ByteArray {
        val iv = randomBytes(IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let { c.updateAAD(it.toByteArray()) }
        return iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, blob: ByteArray, aad: String? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_LEN))
        aad?.let { c.updateAAD(it.toByteArray()) }
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    // ---------------------------------------------------------------- PIN wrapping

    private fun derivePinKey(pin: String, salt: ByteArray, iterations: Int): SecretKey {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(pepperKey())
        val peppered = mac.doFinal(salt + pin.toByteArray(Charsets.UTF_8))
        val password = Base64.encodeToString(peppered, Base64.NO_WRAP).toCharArray()
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
            peppered.fill(0)
        }
    }

    /** Creates a fresh DEK for a board and writes its wrapped form to [keyFile]. */
    fun createBoardKey(pin: String?, keyFile: File): SecretKey {
        val dekRaw = randomBytes(32)
        val dek = SecretKeySpec(dekRaw, "AES")
        writeWrapped(dek, pin, keyFile)
        dekRaw.fill(0)
        return dek
    }

    fun writeWrapped(dek: SecretKey, pin: String?, keyFile: File) {
        if (pin.isNullOrBlank()) {
            val outer = keystoreEncrypt(WRAP_KEY, dek.encoded)
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { out ->
                out.writeInt(MAGIC_NOPIN)
                out.writeInt(outer.size)
                out.write(outer)
            }
            Storage.atomicWrite(keyFile, bos.toByteArray())
            return
        }
        val salt = randomBytes(16)
        val pinKey = derivePinKey(pin, salt, PBKDF2_ITERATIONS)
        val inner = encrypt(pinKey, dek.encoded, aad = "dek")
        val outer = keystoreEncrypt(WRAP_KEY, inner)
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(PBKDF2_ITERATIONS)
            out.writeInt(salt.size); out.write(salt)
            out.writeInt(outer.size); out.write(outer)
        }
        Storage.atomicWrite(keyFile, bos.toByteArray())
    }

    /** Returns the board DEK, or null if the PIN is wrong. */
    fun unwrapBoardKey(pin: String?, keyFile: File): SecretKey? {
        val input = DataInputStream(ByteArrayInputStream(keyFile.readBytes()))
        val magic = input.readInt()
        if (magic == MAGIC_NOPIN) {
            val outer = ByteArray(input.readInt()).also { input.readFully(it) }
            val raw = keystoreDecrypt(WRAP_KEY, outer)
            return SecretKeySpec(raw, "AES").also { raw.fill(0) }
        }
        require(magic == MAGIC) { "Corrupt key file" }
        if (pin.isNullOrEmpty()) return null
        val iterations = input.readInt()
        val salt = ByteArray(input.readInt()).also { input.readFully(it) }
        val outer = ByteArray(input.readInt()).also { input.readFully(it) }
        val inner = keystoreDecrypt(WRAP_KEY, outer)
        val pinKey = derivePinKey(pin, salt, iterations)
        return try {
            val raw = decrypt(pinKey, inner, aad = "dek")
            SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } catch (e: javax.crypto.AEADBadTagException) {
            null
        }
    }

    private const val MAGIC_NOPIN = 0x444F5330 // "DOS0" (Keystore-encrypted, no PIN prompt)
    private const val MAGIC = 0x444F5331 // "DOS1" (PIN + Keystore hardware-peppered)
}

object Storage {
    /** Write-then-rename so a crash never leaves a half-written (corrupt) blob behind. */
    fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Best-effort overwrite before delete. (Flash wear-levelling means this is not a
     *  guarantee — the real protection is that everything on disk is already ciphertext.) */
    fun shred(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { shred(it) }
            file.delete()
            return
        }
        runCatching {
            val len = file.length().toInt()
            if (len in 1..(8 * 1024 * 1024)) file.outputStream().use { it.write(Crypto.randomBytes(len)); it.fd.sync() }
        }
        file.delete()
    }
}

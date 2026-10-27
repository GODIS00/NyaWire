package com.gdhapp.client.data.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class BackupProblem { PASSWORD, DAMAGED, UNSUPPORTED, TOO_LARGE, VPN_ACTIVE }
class BackupException(val problem: BackupProblem) : Exception(problem.name)

/** Portable password-encrypted format. The whole header is authenticated with the payload. */
object BackupCipher {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val magic = byteArrayOf(78, 89, 65, 66, 65, 67, 75, 85, 80) // NYABACKUP
    private const val VERSION = 1
    private const val ITERATIONS = 600_000
    private const val HEADER_SIZE = 9 + 1 + 4 + 16 + 12

    fun isEncrypted(bytes: ByteArray): Boolean = bytes.size >= magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.size in 8..1024)
        if (plain.size > MAX_BYTES - HEADER_SIZE - 16) throw BackupException(BackupProblem.TOO_LARGE)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).put(VERSION.toByte()).putInt(ITERATIONS).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        if (bytes.size > MAX_BYTES) throw BackupException(BackupProblem.TOO_LARGE)
        if (bytes.size < HEADER_SIZE + 16 || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) throw BackupException(BackupProblem.DAMAGED)
        val input = ByteBuffer.wrap(bytes)
        input.position(magic.size)
        if (input.get().toInt() != VERSION) throw BackupException(BackupProblem.UNSUPPORTED)
        // A fixed work factor also prevents an imported file from requesting an unbounded KDF.
        if (input.int != ITERATIONS) throw BackupException(BackupProblem.DAMAGED)
        val salt = ByteArray(16).also(input::get)
        val iv = ByteArray(12).also(input::get)
        if (password.isEmpty() || password.size > 1024) throw BackupException(BackupProblem.PASSWORD)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(bytes, 0, HEADER_SIZE)
        return try { cipher.doFinal(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE) }
        catch (_: AEADBadTagException) { throw BackupException(BackupProblem.PASSWORD) }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val encoded = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try { SecretKeySpec(encoded, "AES") } finally { encoded.fill(0) }
    }
}

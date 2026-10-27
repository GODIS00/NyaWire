package com.gdhapp.client.data

import com.gdhapp.client.data.backup.*
import org.junit.Assert.*
import org.junit.Test

class BackupCipherTest {
    @Test fun portableUnicodeRoundTripHasFreshSaltAndIv() {
        val plain = "Подписки и настройки: secret-config".toByteArray()
        val password = "Пароль 🔑 123".toCharArray()
        val first = BackupCipher.encrypt(plain, password)
        val second = BackupCipher.encrypt(plain, password)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("secret-config"))
        assertArrayEquals(plain, BackupCipher.decrypt(first, password))
    }
    @Test fun wrongPasswordAndModifiedPayloadNeverReturnPlaintext() {
        val bytes = BackupCipher.encrypt("private".toByteArray(), "password1".toCharArray())
        expect(BackupProblem.PASSWORD) { BackupCipher.decrypt(bytes, "password2".toCharArray()) }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        expect(BackupProblem.PASSWORD) { BackupCipher.decrypt(bytes, "password1".toCharArray()) }
    }
    @Test fun rejectsUnsupportedHeaderAndUnboundedWorkFactors() {
        val bytes = BackupCipher.encrypt(byteArrayOf(1, 2), "password1".toCharArray())
        expect(BackupProblem.UNSUPPORTED) { BackupCipher.decrypt(bytes.clone().also { it[9] = 99 }, "password1".toCharArray()) }
        expect(BackupProblem.DAMAGED) { BackupCipher.decrypt(bytes.clone().also { it[10] = 127 }, "password1".toCharArray()) }
        expect(BackupProblem.DAMAGED) { BackupCipher.decrypt(bytes.copyOf(15), "password1".toCharArray()) }
        expect(BackupProblem.TOO_LARGE) { BackupCipher.decrypt(ByteArray(BackupCipher.MAX_BYTES + 1), charArrayOf('x')) }
    }
    private fun expect(problem: BackupProblem, block: () -> Unit) {
        try { block(); fail("Invalid backup was accepted") } catch (error: BackupException) { assertEquals(problem, error.problem) }
    }
}

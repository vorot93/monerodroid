package com.sevendeuce.monerodroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BinaryVerifierTest {

    // Replace with the exact filename printed by Step 1 if the version differs.
    private val ARMV8_FILE = "monero-android-armv8-v0.18.5.0.tar.bz2"

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private val cert get() = resource("binaryfate.asc")
    private val hashes get() = resource("hashes.txt")
    private val hashesTampered get() = resource("hashes-tampered.txt")

    private val verifier = BinaryVerifier()

    @Test
    fun `valid clearsigned hashes verify and return plaintext`() {
        val text = verifier.verifyHashesSignature(hashes, cert)
        assertTrue(text.contains(ARMV8_FILE))
    }

    @Test
    fun `tampered hashes fail signature verification`() {
        assertThrows(SecurityException::class.java) {
            verifier.verifyHashesSignature(hashesTampered, cert)
        }
    }

    @Test
    fun `expectedSha256 returns the 64-hex hash for a known filename`() {
        val text = verifier.verifyHashesSignature(hashes, cert)
        val hash = verifier.expectedSha256(text, ARMV8_FILE)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `expectedSha256 throws when filename absent`() {
        val text = verifier.verifyHashesSignature(hashes, cert)
        assertThrows(SecurityException::class.java) {
            verifier.expectedSha256(text, "monero-android-armv8-v9.9.9.9.tar.bz2")
        }
    }

    @Test
    fun `sha256 computes lowercase hex of file contents`() {
        val tmp = File.createTempFile("vt_", ".bin")
        tmp.writeBytes(byteArrayOf(1, 2, 3))
        val expected = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, verifier.sha256(tmp))
        tmp.delete()
    }

    @Test
    fun `verifyArchive returns false on hash mismatch`() {
        val text = verifier.verifyHashesSignature(hashes, cert)
        val realHash = verifier.expectedSha256(text, ARMV8_FILE)
        val tmp = File.createTempFile("vt_", ".tar.bz2")
        tmp.writeBytes("not the real archive".toByteArray())
        assertTrue(verifier.sha256(tmp) != realHash)
        assertEquals(false, verifier.verifyArchive(tmp, ARMV8_FILE, hashes, cert))
        tmp.delete()
    }
}

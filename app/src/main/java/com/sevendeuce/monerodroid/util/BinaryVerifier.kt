package com.sevendeuce.monerodroid.util

import org.pgpainless.sop.SOPImpl
import sop.SOP
import java.io.File
import java.security.MessageDigest

/**
 * Verifies a downloaded monerod archive against binaryFate's signed hashes.txt.
 * Pure JVM (no Android Context) so it is unit-testable.
 *
 * Trust chain: the pinned binaryFate public key -> the clearsigned hashes.txt ->
 * the SHA-256 of the archive. Nothing unverified is ever executed.
 */
class BinaryVerifier(private val sop: SOP = SOPImpl()) {

    /**
     * Verify the clearsigned [clearsignedHashes] against [certAscii] (binaryFate's key).
     * Returns the verified plaintext on success; throws [SecurityException] otherwise.
     * The SOP library handles cleartext-signature canonicalization internally.
     */
    fun verifyHashesSignature(clearsignedHashes: ByteArray, certAscii: ByteArray): String {
        val result = try {
            sop.inlineVerify()
                .cert(certAscii)
                .data(clearsignedHashes)
                .toByteArrayAndResult()
        } catch (e: Exception) {
            throw SecurityException("hashes.txt signature verification failed", e)
        }
        if (result.result.isEmpty()) {
            throw SecurityException("hashes.txt was not signed by the pinned key")
        }
        return String(result.bytes, Charsets.UTF_8)
    }

    /**
     * Extract the expected lowercase SHA-256 for [filename] from verified hashes text.
     * Each line is `<64-hex-sha256>  <filename>`. Throws [SecurityException] if absent/malformed.
     */
    fun expectedSha256(verifiedHashesText: String, filename: String): String {
        val line = verifiedHashesText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith(filename) }
            ?: throw SecurityException("No hash entry for $filename")
        val hash = line.substringBefore(' ').lowercase()
        if (hash.length != 64 || !hash.all { it.isDigit() || it in 'a'..'f' }) {
            throw SecurityException("Malformed SHA-256 for $filename")
        }
        return hash
    }

    /** Streaming SHA-256 of [file] as lowercase hex. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Full check: signature of [clearsignedHashes] valid AND archive SHA-256 matches the
     * entry for [filename]. Returns false on hash mismatch; throws on signature failure or
     * missing entry (caller treats a throw as rejection too).
     */
    fun verifyArchive(
        archive: File,
        filename: String,
        clearsignedHashes: ByteArray,
        certAscii: ByteArray
    ): Boolean {
        val verifiedText = verifyHashesSignature(clearsignedHashes, certAscii)
        val expected = expectedSha256(verifiedText, filename)
        return sha256(archive).equals(expected, ignoreCase = true)
    }
}

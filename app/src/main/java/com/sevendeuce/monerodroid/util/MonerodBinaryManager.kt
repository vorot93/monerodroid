package com.sevendeuce.monerodroid.util

import android.content.Context
import android.util.Log
import com.sevendeuce.monerodroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit

sealed class BinaryStatus {
    object NotInstalled : BinaryStatus()
    object Installed : BinaryStatus()
    object Downloading : BinaryStatus()
    object Verifying : BinaryStatus()
    object Extracting : BinaryStatus()
    object Updating : BinaryStatus()
    data class DownloadProgress(val progress: Int, val downloadedMb: Float, val totalMb: Float) : BinaryStatus()
    data class Error(val message: String) : BinaryStatus()
}

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    object Downloading : UpdateStatus()
    object Verifying : UpdateStatus()
    object Extracting : UpdateStatus()
    data class Progress(val progress: Int, val downloadedMb: Float, val totalMb: Float) : UpdateStatus()
    data class Available(val currentVersion: String, val latestVersion: String) : UpdateStatus()
    object UpToDate : UpdateStatus()
    object Success : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

class MonerodBinaryManager(private val context: Context) {

    companion object {
        private const val TAG = "MonerodBinaryManager"
        private const val HASHES_URL = "https://www.getmonero.org/downloads/hashes.txt"
    }

    private val verifier = BinaryVerifier()

    private fun parseVersionFromFilename(filename: String): String? =
        Regex("""v(\d+\.\d+\.\d+\.\d+)""").find(filename)?.groupValues?.get(1)

    private fun loadPinnedCert(): ByteArray =
        context.resources.openRawResource(R.raw.binaryfate).use { it.readBytes() }

    private fun fetchHashes(): ByteArray {
        val request = Request.Builder().url(HASHES_URL).build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("hashes.txt HTTP ${resp.code}")
            return resp.body!!.bytes()
        }
    }

    /**
     * Verify [archive] (downloaded from [finalUrl]) against binaryFate's signed hashes.
     * Returns the resolved filename on success. Throws SecurityException on any verification failure.
     */
    private fun verifyArchiveOrThrow(archive: File, finalUrl: okhttp3.HttpUrl): String {
        val filename = finalUrl.pathSegments.last()
        val hashes = fetchHashes()
        val cert = loadPinnedCert()
        if (!verifier.verifyArchive(archive, filename, hashes, cert)) {
            throw SecurityException("Downloaded file failed integrity check")
        }
        return filename
    }

    private val storageManager = StorageManager(context)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun savedVersionFile(): File = File(storageManager.getBinaryDir(), "version.txt")

    /**
     * Save the installed binary version to disk.
     * Used as fallback when the binary can't be executed (e.g. noexec mount on Android 10+).
     */
    fun saveInstalledVersion(version: String) {
        try {
            savedVersionFile().writeText(version)
            Log.d(TAG, "Saved installed version: $version")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save installed version", e)
        }
    }

    private fun readSavedVersion(): String? {
        return try {
            val file = savedVersionFile()
            if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read saved version", e)
            null
        }
    }

    fun isBinaryInstalled(): Boolean {
        val binaryFile = storageManager.getWritableBinaryPath()
        return binaryFile.exists() && binaryFile.canExecute()
    }

    /** The installed version, read from version.txt (written at install time). */
    fun getBinaryVersion(): String? {
        if (!isBinaryInstalled()) return null
        return readSavedVersion()
    }

    /**
     * Get the path to the monerod binary (native lib or copied)
     */
    fun getBinaryPath(): String {
        return storageManager.getMonerodBinaryPath().absolutePath
    }

    fun installBinary(): Flow<BinaryStatus> = flow {
        if (isBinaryInstalled()) {
            emit(BinaryStatus.Installed)
            return@flow
        }
        downloadAndInstallBinary().collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * Make a file executable using multiple methods for Android compatibility
     */
    private fun makeExecutable(file: File): Boolean {
        // Method 1: Java File API
        var success = file.setExecutable(true, false)
        Log.d(TAG, "setExecutable result: $success")

        if (!file.canExecute()) {
            // Method 2: chmod via Runtime
            try {
                val process = Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath))
                process.waitFor(5, TimeUnit.SECONDS)
                val exitCode = process.exitValue()
                Log.d(TAG, "chmod 755 exit code: $exitCode")
                success = exitCode == 0
            } catch (e: Exception) {
                Log.e(TAG, "chmod failed", e)
            }
        }

        if (!file.canExecute()) {
            // Method 3: chmod via ProcessBuilder
            try {
                val process = ProcessBuilder("chmod", "755", file.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(5, TimeUnit.SECONDS)
                Log.d(TAG, "ProcessBuilder chmod result: ${process.exitValue()}")
            } catch (e: Exception) {
                Log.e(TAG, "ProcessBuilder chmod failed", e)
            }
        }

        if (!file.canExecute()) {
            // Method 4: Set all permission bits
            file.setReadable(true, false)
            file.setWritable(true, false)
            file.setExecutable(true, false)
        }

        return file.canExecute()
    }

    /**
     * Download and install binary from getmonero.org
     */
    fun downloadAndInstallBinary(): Flow<BinaryStatus> = flow {
        emit(BinaryStatus.Downloading)

        val downloadUrl = ArchitectureDetector.getMoneroDownloadUrl()
        if (downloadUrl == null) {
            emit(BinaryStatus.Error("Unsupported CPU architecture: ${ArchitectureDetector.getArchitectureName()}"))
            return@flow
        }

        Log.d(TAG, "Downloading from: $downloadUrl")

        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(BinaryStatus.Error("Download failed: HTTP ${response.code}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(BinaryStatus.Error("Empty response from server"))
                return@flow
            }

            val contentLength = body.contentLength()
            val totalMb = if (contentLength > 0) contentLength / (1024f * 1024f) else 100f

            // Download to temp file
            val tempArchive = File(context.cacheDir, "monero.tar.bz2")

            body.byteStream().use { input ->
                FileOutputStream(tempArchive).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgress = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val progress = if (contentLength > 0) {
                            (totalBytesRead * 100 / contentLength).toInt()
                        } else {
                            (totalBytesRead / (100 * 1024 * 1024) * 100).toInt().coerceAtMost(99)
                        }

                        if (progress > lastProgress) {
                            lastProgress = progress
                            val downloadedMb = totalBytesRead / (1024f * 1024f)
                            emit(BinaryStatus.DownloadProgress(progress, downloadedMb, totalMb))
                        }
                    }
                }
            }

            val finalUrl = response.request.url

            // Verify signature + SHA-256 BEFORE touching/executing anything.
            emit(BinaryStatus.Verifying)
            val filename = try {
                verifyArchiveOrThrow(tempArchive, finalUrl)
            } catch (e: SecurityException) {
                tempArchive.delete()
                emit(BinaryStatus.Error(e.message ?: "Verification failed"))
                return@flow
            }

            Log.d(TAG, "Verified $filename, extracting...")
            emit(BinaryStatus.Extracting)

            val extracted = extractBinaryWithShell(tempArchive)
            tempArchive.delete()
            File(context.cacheDir, "monero.tar").delete()

            if (extracted && isBinaryInstalled()) {
                parseVersionFromFilename(filename)?.let { saveInstalledVersion(it) }
                emit(BinaryStatus.Installed)
            } else {
                emit(BinaryStatus.Error("Failed to extract monerod binary"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download/install error", e)
            emit(BinaryStatus.Error("Error: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun extractBinaryWithShell(archiveFile: File): Boolean {
        val binaryDir = storageManager.getBinaryDir()
        binaryDir.mkdirs()

        val extractDir = File(context.cacheDir, "monero_extract")
        extractDir.mkdirs()

        try {
            // Step 1: Decompress bzip2
            val tarFile = File(context.cacheDir, "monero.tar")
            val bunzipProcess = ProcessBuilder(
                "bzip2", "-dk", archiveFile.absolutePath
            ).redirectErrorStream(true).start()

            val bunzipOutput = bunzipProcess.inputStream.bufferedReader().readText()
            val bunzipResult = bunzipProcess.waitFor(120, TimeUnit.SECONDS)
            Log.d(TAG, "bunzip2 result: $bunzipResult, output: $bunzipOutput")

            // The decompressed file might be named differently
            val possibleTarFiles = listOf(
                File(context.cacheDir, "monero.tar"),
                File(archiveFile.parent, archiveFile.nameWithoutExtension)
            )

            val actualTarFile = possibleTarFiles.find { it.exists() }

            if (actualTarFile == null || !actualTarFile.exists()) {
                Log.e(TAG, "Tar file not found after decompression")
                return fallbackExtract(archiveFile, binaryDir)
            }

            // Step 2: Extract tar
            val tarProcess = ProcessBuilder(
                "tar", "-xf", actualTarFile.absolutePath, "-C", extractDir.absolutePath
            ).redirectErrorStream(true).start()

            val tarOutput = tarProcess.inputStream.bufferedReader().readText()
            val tarResult = tarProcess.waitFor(120, TimeUnit.SECONDS)
            Log.d(TAG, "tar result: $tarResult, output: $tarOutput")

            actualTarFile.delete()

            // Step 3: Find and copy monerod binary
            val monerodFile = findMonerodBinary(extractDir)
            if (monerodFile != null) {
                val destFile = storageManager.getWritableBinaryPath()
                if (destFile.exists()) destFile.delete()
                monerodFile.copyTo(destFile, overwrite = true)
                makeExecutable(destFile)

                // Clean up extract directory
                extractDir.deleteRecursively()

                return destFile.exists() && destFile.canExecute()
            }

            extractDir.deleteRecursively()
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Shell extraction failed", e)
            return fallbackExtract(archiveFile, binaryDir)
        }
    }

    private fun findMonerodBinary(dir: File): File? {
        dir.walkTopDown().forEach { file ->
            if (file.name == "monerod" && file.isFile) {
                return file
            }
        }
        return null
    }

    private fun fallbackExtract(archiveFile: File, binaryDir: File): Boolean {
        // Try using a single command pipeline
        try {
            val extractDir = File(context.cacheDir, "monero_extract2")
            extractDir.mkdirs()

            val process = ProcessBuilder(
                "sh", "-c",
                "cd ${context.cacheDir.absolutePath} && " +
                        "bzcat ${archiveFile.absolutePath} | tar -xf - -C ${extractDir.absolutePath}"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(180, TimeUnit.SECONDS)
            Log.d(TAG, "Fallback extract output: $output")

            val monerodFile = findMonerodBinary(extractDir)
            if (monerodFile != null) {
                val destFile = storageManager.getWritableBinaryPath()
                if (destFile.exists()) destFile.delete()
                monerodFile.copyTo(destFile, overwrite = true)
                makeExecutable(destFile)
                extractDir.deleteRecursively()
                return destFile.exists() && destFile.canExecute()
            }

            extractDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Fallback extraction also failed", e)
        }

        return false
    }

    fun deleteBinary() {
        storageManager.getMonerodBinaryPath().delete()
        storageManager.getBinaryDir().deleteRecursively()
    }

    /**
     * Check for updates and optionally get latest version info
     * Uses the Monero downloads page to check latest version
     */
    suspend fun checkForUpdate(): UpdateStatus = withContext(Dispatchers.IO) {
        val currentVersion = getBinaryVersion() // May be null — that's OK

        try {
            // Fetch the latest version from getmonero.org downloads page
            val request = Request.Builder()
                .url("https://downloads.getmonero.org/cli/linux64")
                .head()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val location = response.header("Location") ?: response.request.url.toString()

            // Parse version from URL like: monero-linux-x64-v0.18.3.4.tar.bz2
            val versionRegex = """v(\d+\.\d+\.\d+\.\d+)""".toRegex()
            val latestVersion = versionRegex.find(location)?.groupValues?.get(1)

            if (latestVersion == null) {
                UpdateStatus.Error("Could not determine latest version")
            } else if (currentVersion == null) {
                // Binary is installed but version can't be determined (e.g. noexec, x86 emulator)
                // Still offer the update — user can re-download to fix the binary
                Log.w(TAG, "Current version unknown, offering update to $latestVersion")
                UpdateStatus.Available("unknown", latestVersion)
            } else if (isNewerVersion(latestVersion, currentVersion)) {
                UpdateStatus.Available(currentVersion, latestVersion)
            } else {
                UpdateStatus.UpToDate
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            UpdateStatus.Error("Failed to check for updates: ${e.message}")
        }
    }

    /**
     * Compare version strings (e.g., "0.18.3.4" vs "0.18.3.3")
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }

            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    /**
     * Update monerod to the latest version
     * Downloads from getmonero.org and replaces the existing binary
     */
    fun updateBinary(): Flow<UpdateStatus> = flow {
        emit(UpdateStatus.Downloading)

        val downloadUrl = ArchitectureDetector.getMoneroDownloadUrl()
        if (downloadUrl == null) {
            emit(UpdateStatus.Error("Unsupported CPU architecture: ${ArchitectureDetector.getArchitectureName()}"))
            return@flow
        }

        Log.d(TAG, "Updating from: $downloadUrl")

        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(UpdateStatus.Error("Download failed: HTTP ${response.code}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(UpdateStatus.Error("Empty response from server"))
                return@flow
            }

            val contentLength = body.contentLength()
            val totalMb = if (contentLength > 0) contentLength / (1024f * 1024f) else 100f

            // Download to temp file
            val tempArchive = File(context.cacheDir, "monero_update.tar.bz2")

            body.byteStream().use { input ->
                FileOutputStream(tempArchive).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgress = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val progress = if (contentLength > 0) {
                            (totalBytesRead * 100 / contentLength).toInt()
                        } else {
                            (totalBytesRead / (100 * 1024 * 1024) * 100).toInt().coerceAtMost(99)
                        }

                        if (progress > lastProgress) {
                            lastProgress = progress
                            val downloadedMb = totalBytesRead / (1024f * 1024f)
                            emit(UpdateStatus.Progress(progress, downloadedMb, totalMb))
                        }
                    }
                }
            }

            val finalUrl = response.request.url

            emit(UpdateStatus.Verifying)
            val filename = try {
                verifyArchiveOrThrow(tempArchive, finalUrl)
            } catch (e: SecurityException) {
                tempArchive.delete()
                emit(UpdateStatus.Error(e.message ?: "Verification failed"))
                return@flow
            }

            Log.d(TAG, "Verified update $filename, extracting...")
            emit(UpdateStatus.Extracting)

            val existingBinary = storageManager.getMonerodBinaryPath()
            val binaryDir = storageManager.getBinaryDir()
            val backupBinary = File(binaryDir, "monerod.backup")
            if (existingBinary.exists()) existingBinary.copyTo(backupBinary, overwrite = true)

            val extracted = extractBinaryWithShell(tempArchive)
            tempArchive.delete()
            File(context.cacheDir, "monero_update.tar").delete()

            if (extracted && isBinaryInstalled()) {
                backupBinary.delete()
                parseVersionFromFilename(filename)?.let { saveInstalledVersion(it) }
                emit(UpdateStatus.Success)
            } else {
                if (backupBinary.exists()) {
                    val destBinary = storageManager.getWritableBinaryPath()
                    backupBinary.copyTo(destBinary, overwrite = true)
                    makeExecutable(destBinary)
                    backupBinary.delete()
                }
                emit(UpdateStatus.Error("Failed to extract updated monerod binary"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Update error", e)
            emit(UpdateStatus.Error("Update failed: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)
}

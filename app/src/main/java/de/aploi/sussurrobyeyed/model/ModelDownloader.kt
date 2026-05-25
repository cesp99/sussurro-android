package de.aploi.sussurrobyeyed.model

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Downloads the Whisper Small ggml model from HuggingFace and stores it under
 * the app's private files directory.
 *
 * Emits coarse progress updates: roughly every percent of total bytes, and
 * always a final terminal state.
 */
class ModelDownloader(private val context: Context) {

    sealed interface Progress {
        object Idle : Progress
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : Progress {
            val fraction: Float = if (totalBytes > 0L) downloadedBytes / totalBytes.toFloat() else 0f
        }
        object Verifying : Progress
        object Done : Progress
        data class Failed(val message: String) : Progress
    }

    /** Final path on disk; not guaranteed to exist yet. */
    val modelFile: File get() = File(context.filesDir, "models/${WhisperModel.fileName}")
    private val partialFile: File get() = File(context.filesDir, "models/${WhisperModel.fileName}.part")

    /** True if a verified-looking model is present on disk. */
    fun isInstalled(): Boolean {
        val f = modelFile
        // ~488 MB; we accept anything within 5% of that as a sanity check. We
        // do not re-hash on every cold start (that would be unacceptably slow);
        // hashing happens once, immediately after download.
        if (!f.isFile) return false
        val approx = WhisperModel.approximateSizeBytes
        val tolerance = approx / 20 // 5%
        return f.length() in (approx - tolerance)..(approx + tolerance * 4)
    }

    suspend fun delete(): Boolean = withContext(Dispatchers.IO) {
        val main = modelFile.delete()
        partialFile.delete()
        main
    }

    /**
     * Remove any `ggml-*.bin` files left over from a previous model variant
     * (e.g. the 488 MB `ggml-small.bin` after we switch to the Q5_1 build).
     *
     * No-op if the only file in the models directory is the current target.
     * Safe to call repeatedly — only deletes files that don't match
     * [WhisperModel.fileName] and aren't the in-progress partial.
     *
     * @return number of files removed.
     */
    suspend fun purgeStaleModels(): Int = withContext(Dispatchers.IO) {
        val dir = modelFile.parentFile ?: return@withContext 0
        if (!dir.isDirectory) return@withContext 0
        val keep = setOf(WhisperModel.fileName, "${WhisperModel.fileName}.part")
        var removed = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name
            if (name in keep) return@forEach
            if (!name.startsWith("ggml-") || !name.endsWith(".bin")) return@forEach
            val ok = runCatching { f.delete() }.getOrDefault(false)
            if (ok) {
                removed++
                Log.i(TAG, "purged stale model: $name")
            } else {
                Log.w(TAG, "failed to delete stale model: $name")
            }
        }
        removed
    }

    /**
     * Stream the Whisper model from HuggingFace into [modelFile]. Resumes
     * partial downloads when [partialFile] already exists.
     *
     * The flow runs on [Dispatchers.IO] and is cancellation-friendly: if the
     * collector cancels, the connection is closed and the partial file is
     * preserved for a future resume.
     */
    fun download(): Flow<Progress> = channelFlow {
        val dst = modelFile
        val part = partialFile
        dst.parentFile?.mkdirs()

        if (dst.isFile && isInstalled()) {
            send(Progress.Done)
            return@channelFlow
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(WhisperModel.url)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                // Resume if we have a partial download.
                val existing = if (part.isFile) part.length() else 0L
                if (existing > 0L) {
                    setRequestProperty("Range", "bytes=$existing-")
                }
            }

            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                send(Progress.Failed("HTTP $responseCode"))
                return@channelFlow
            }

            val contentLength = connection.contentLengthLong
            val resuming = responseCode == 206 && part.isFile
            val alreadyDownloaded = if (resuming) part.length() else 0L
            val totalBytes = if (contentLength > 0L) alreadyDownloaded + contentLength else WhisperModel.approximateSizeBytes

            send(Progress.Downloading(alreadyDownloaded, totalBytes))

            connection.inputStream.use { input ->
                FileOutputStream(part, /* append = */ resuming).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = alreadyDownloaded
                    var lastReported = downloaded
                    var read = input.read(buf)
                    while (read > 0) {
                        if (!isActive) {
                            output.flush()
                            return@channelFlow
                        }
                        output.write(buf, 0, read)
                        downloaded += read

                        // Throttle Progress emissions to ~1 per percent.
                        val threshold = (totalBytes / 100).coerceAtLeast(64 * 1024L)
                        if (downloaded - lastReported >= threshold) {
                            send(Progress.Downloading(downloaded, totalBytes))
                            lastReported = downloaded
                        }
                        read = input.read(buf)
                    }
                    output.flush()
                }
            }

            send(Progress.Verifying)

            // Verify before renaming so a corrupted half-download can't fool isInstalled().
            val verified = verifySha256(part, WhisperModel.sha256)
            if (!verified) {
                part.delete()
                send(Progress.Failed("checksum mismatch"))
                return@channelFlow
            }

            if (dst.exists()) dst.delete()
            if (!part.renameTo(dst)) {
                send(Progress.Failed("could not move temp file into place"))
                return@channelFlow
            }

            send(Progress.Done)
        } catch (io: IOException) {
            Log.w(TAG, "download failed", io)
            send(Progress.Failed(io.message ?: "download failed"))
        } catch (t: Throwable) {
            Log.e(TAG, "download crashed", t)
            send(Progress.Failed(t.message ?: "unknown error"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun verifySha256(file: File, expectedHex: String?): Boolean {
        if (expectedHex.isNullOrBlank()) return true
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            var n = stream.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = stream.read(buf)
            }
        }
        val actualHex = md.digest().joinToString("") { "%02x".format(it) }
        val ok = actualHex.equals(expectedHex, ignoreCase = true)
        if (!ok) Log.w(TAG, "SHA mismatch: expected=$expectedHex got=$actualHex")
        return ok
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}

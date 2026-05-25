package de.aploi.sussurrobyeyed.model

/**
 * The Whisper model Sussurro ships with.
 *
 * We use the Q5_1-quantised Whisper Small (~190 MB) instead of the full fp16
 * variant (~488 MB). Quality is virtually indistinguishable on speech, but
 * the smaller weights are noticeably faster to inference on ARM CPUs (better
 * cache locality + SIMD-friendly Q5_1 kernels in ggml) and obviously a much
 * lighter download.
 *
 * If you ever swap models out, update [sha256] too — [ModelDownloader] verifies
 * the SHA-256 of the downloaded file against this constant before declaring
 * success.
 */
object WhisperModel {
    const val name: String = "Whisper Small (Q5_1)"
    const val fileName: String = "ggml-small-q5_1.bin"
    const val url: String = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
    // Exact byte count from HuggingFace's x-linked-size header. Used as a
    // sanity check in ModelDownloader.isInstalled().
    const val approximateSizeBytes: Long = 190_085_487L

    /** Human-readable size used in setup / settings copy. */
    const val approximateSizeLabel: String = "190 MB"

    /**
     * SHA-256 of the upstream `ggml-small-q5_1.bin`, as published on HuggingFace
     * (read from the `x-linked-etag` header on the resolve URL, which HF
     * populates with the underlying LFS SHA-256). If `null`, the downloader
     * skips integrity verification (you'll still get the standard HTTP byte
     * count check).
     */
    const val sha256: String = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
}

package de.aploi.sussurrobyeyed.model

/**
 * The Whisper model Sussurro ships with.
 *
 * We intentionally support a single model variant — Whisper Small, ~488 MB,
 * served by ggerganov on HuggingFace as `ggml-small.bin`. The user said
 * "whisper small", and adding more knobs would only confuse the onboarding.
 *
 * If you ever swap models out, update [sha256] too — [ModelDownloader] verifies
 * the SHA-256 of the downloaded file against this constant before declaring
 * success.
 */
object WhisperModel {
    const val name: String = "Whisper Small"
    const val fileName: String = "ggml-small.bin"
    const val url: String = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
    const val approximateSizeBytes: Long = 488_000_000L

    /**
     * SHA-256 of the upstream `ggml-small.bin`, as published on HuggingFace.
     * If `null`, the downloader skips integrity verification (you'll still get
     * the standard HTTP byte count check).
     */
    const val sha256: String = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"
}

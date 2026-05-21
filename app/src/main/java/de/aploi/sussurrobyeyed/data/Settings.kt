package de.aploi.sussurrobyeyed.data

/**
 * User preferences for Sussurro.
 *
 * Persisted by [SettingsStore]. Defaults are deliberately conservative:
 *   - System theme (don't impose a colour mode)
 *   - Auto language detection (let Whisper figure it out)
 *   - No lowercase forcing (preserve Whisper's casing)
 *   - Trim trailing punctuation off so dictated tokens don't end with awkward
 *     full stops when injected mid-sentence
 */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    val language: String = LANGUAGE_AUTO,
    val lowercaseOutput: Boolean = false,
    val trimTrailingPunctuation: Boolean = true,
) {
    companion object {
        const val LANGUAGE_AUTO = "auto"
    }
}

enum class ThemeMode { System, Light, Dark }

/**
 * Languages Whisper recognises. The list comes straight from
 * `whisper.cpp/src/whisper.cpp` (whisper_lang_id table), with English-readable
 * names attached. The "Auto detect" entry is our own.
 */
object WhisperLanguages {
    data class Lang(val code: String, val name: String)

    val all: List<Lang> = listOf(
        Lang(Settings.LANGUAGE_AUTO, "Auto detect"),
        Lang("en", "English"),
        Lang("it", "Italian"),
        Lang("es", "Spanish"),
        Lang("fr", "French"),
        Lang("de", "German"),
        Lang("pt", "Portuguese"),
        Lang("nl", "Dutch"),
        Lang("ru", "Russian"),
        Lang("uk", "Ukrainian"),
        Lang("pl", "Polish"),
        Lang("tr", "Turkish"),
        Lang("ar", "Arabic"),
        Lang("zh", "Chinese"),
        Lang("ja", "Japanese"),
        Lang("ko", "Korean"),
        Lang("hi", "Hindi"),
        Lang("id", "Indonesian"),
        Lang("sv", "Swedish"),
        Lang("no", "Norwegian"),
        Lang("da", "Danish"),
        Lang("fi", "Finnish"),
        Lang("cs", "Czech"),
        Lang("ro", "Romanian"),
        Lang("hu", "Hungarian"),
        Lang("el", "Greek"),
        Lang("vi", "Vietnamese"),
        Lang("th", "Thai"),
        Lang("he", "Hebrew"),
        Lang("ca", "Catalan"),
    )

    fun nameFor(code: String): String =
        all.firstOrNull { it.code == code }?.name ?: code
}

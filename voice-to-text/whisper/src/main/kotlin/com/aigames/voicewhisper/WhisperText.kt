package com.aigames.voicewhisper

/** Small text helpers around the speech model. */
object WhisperText {

    /** The two-letter code Whisper wants, from a tag such as "ur-PK". */
    @JvmStatic
    fun languageCode(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()

    /**
     * Tidies what the model returns: it marks silence and noises with text
     * in brackets such as "[BLANK_AUDIO]" or "(music)", which nobody said.
     */
    @JvmStatic
    fun clean(raw: String): String = raw
        .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

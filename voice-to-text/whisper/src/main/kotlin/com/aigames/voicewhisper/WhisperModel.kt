package com.aigames.voicewhisper

/**
 * A speech model the app can download. Bigger models hear better but are
 * slower and take more space. Both understand about 100 languages.
 */
enum class WhisperModel(val label: String, val fileName: String, val sizeBytes: Long) {
    /** Quick, fine for clear speech. Urdu comes out in Urdu script only. */
    FAST("Fast (60 MB)", "ggml-base-q5_1.bin", 59_707_625L),

    /** Slower, noticeably more accurate, and good enough to write Urdu in Roman letters. */
    ACCURATE("Accurate (190 MB)", "ggml-small-q5_1.bin", 190_085_487L);

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

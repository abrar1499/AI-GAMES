package com.aigames.voicetext

/** A language to offer in a picker: a name to show and the tag to give to [VoiceToText.language]. */
data class VoiceLanguage(val name: String, val tag: String) {
    override fun toString(): String = name
}

/**
 * Languages worth offering. Which ones actually work depends on the phone's
 * speech service; an unsupported one reports [VoiceError.LANGUAGE_NOT_SUPPORTED].
 */
object VoiceLanguages {
    @JvmField
    val COMMON: List<VoiceLanguage> = listOf(
        VoiceLanguage("اردو (Urdu)", "ur-PK"),
        VoiceLanguage("English (US)", "en-US"),
        VoiceLanguage("English (UK)", "en-GB"),
        VoiceLanguage("हिन्दी (Hindi)", "hi-IN"),
        VoiceLanguage("العربية (Arabic)", "ar-SA"),
        VoiceLanguage("ਪੰਜਾਬੀ (Punjabi)", "pa-IN"),
        VoiceLanguage("বাংলা (Bengali)", "bn-BD"),
        VoiceLanguage("فارسی (Persian)", "fa-IR"),
        VoiceLanguage("Türkçe (Turkish)", "tr-TR"),
        VoiceLanguage("Bahasa Indonesia", "id-ID"),
        VoiceLanguage("Español (Spanish)", "es-ES"),
        VoiceLanguage("Français (French)", "fr-FR"),
        VoiceLanguage("Deutsch (German)", "de-DE"),
        VoiceLanguage("Português (Brazil)", "pt-BR"),
        VoiceLanguage("Русский (Russian)", "ru-RU"),
        VoiceLanguage("中文 (Chinese)", "zh-CN"),
        VoiceLanguage("日本語 (Japanese)", "ja-JP"),
        VoiceLanguage("한국어 (Korean)", "ko-KR"),
    )
}

package com.aigames.voicetext

/** Why listening stopped without giving text. [message] is safe to show to the user. */
enum class VoiceError(val message: String) {
    NO_PERMISSION("Microphone permission is needed."),
    NOT_AVAILABLE("This phone has no speech recognition. Install or update the Google app."),
    NOTHING_HEARD("Didn't catch that. Try again."),
    NETWORK("No internet connection for speech recognition."),
    LANGUAGE_NOT_SUPPORTED("This language is not available on this phone."),
    BUSY("Speech recognition is busy. Try again in a moment."),
    AUDIO("Could not record from the microphone."),
    SERVER("The speech service had a problem. Try again."),
    UNKNOWN("Something went wrong with speech recognition."),
}

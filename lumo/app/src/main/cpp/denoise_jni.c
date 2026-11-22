// Bridge between Lumo (Kotlin) and RNNoise: cleans 16-bit mono 48 kHz microphone audio in place,
// 10 ms (480 samples) at a time, before WebRTC compresses and sends it.
#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

#define FRAME 480

JNIEXPORT jlong JNICALL
Java_com_lumo_app_call_Denoiser_nativeCreate(JNIEnv *env, jclass cls) {
    return (jlong) (intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_com_lumo_app_call_Denoiser_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    if (handle) rnnoise_destroy((DenoiseState *) (intptr_t) handle);
}

/* Returns the average chance (0..1) that the audio held a voice, or -1 if it couldn't run. */
JNIEXPORT jfloat JNICALL
Java_com_lumo_app_call_Denoiser_nativeProcess(JNIEnv *env, jclass cls, jlong handle, jobject buffer, jint samples) {
    if (!handle) return -1;
    int16_t *pcm = (int16_t *) (*env)->GetDirectBufferAddress(env, buffer);
    if (!pcm || samples < FRAME) return -1;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;
    float frame[FRAME];
    float voice = 0;
    int frames = samples / FRAME;
    for (int f = 0; f < frames; f++) {
        int16_t *p = pcm + f * FRAME;
        for (int i = 0; i < FRAME; i++) frame[i] = (float) p[i];
        voice += rnnoise_process_frame(st, frame, frame);
        for (int i = 0; i < FRAME; i++) {
            float v = frame[i];
            p[i] = (int16_t) (v > 32767.f ? 32767 : (v < -32768.f ? -32768 : v));
        }
    }
    return voice / frames;
}

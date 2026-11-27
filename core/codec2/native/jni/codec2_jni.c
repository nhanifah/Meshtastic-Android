/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * JNI bridge for org.meshtastic.core.codec2.Codec2Native (a Kotlin object, so every function takes the instance as
 * its second argument). Bounds are checked on the Kotlin side; this layer only converts arrays and loops frames.
 */
#include <jni.h>
#include <stdint.h>

#include "codec2.h"

#define C2(handle) ((struct CODEC2 *)(intptr_t)(handle))

JNIEXPORT jlong JNICALL Java_org_meshtastic_core_codec2_Codec2Native_create(JNIEnv *env, jobject thiz, jint mode) {
    (void)env;
    (void)thiz;
    return (jlong)(intptr_t)codec2_create(mode);
}

JNIEXPORT void JNICALL Java_org_meshtastic_core_codec2_Codec2Native_destroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    if (handle != 0) codec2_destroy(C2(handle));
}

JNIEXPORT jint JNICALL Java_org_meshtastic_core_codec2_Codec2Native_samplesPerFrame(JNIEnv *env, jobject thiz,
                                                                                     jlong handle) {
    (void)env;
    (void)thiz;
    return codec2_samples_per_frame(C2(handle));
}

JNIEXPORT jint JNICALL Java_org_meshtastic_core_codec2_Codec2Native_bytesPerFrame(JNIEnv *env, jobject thiz,
                                                                                   jlong handle) {
    (void)env;
    (void)thiz;
    return codec2_bytes_per_frame(C2(handle));
}

/* Encodes `frames` consecutive frames of PCM from `pcm` into `out`. */
JNIEXPORT void JNICALL Java_org_meshtastic_core_codec2_Codec2Native_encodeFrames(JNIEnv *env, jobject thiz,
                                                                                  jlong handle, jshortArray pcm,
                                                                                  jbyteArray out, jint frames) {
    (void)thiz;
    struct CODEC2 *c2 = C2(handle);
    int samples = codec2_samples_per_frame(c2);
    int bytes = codec2_bytes_per_frame(c2);
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *bits = (*env)->GetByteArrayElements(env, out, NULL);
    if (in != NULL && bits != NULL) {
        for (int i = 0; i < frames; i++) {
            codec2_encode(c2, (unsigned char *)bits + (size_t)i * bytes, in + (size_t)i * samples);
        }
    }
    if (bits != NULL) (*env)->ReleaseByteArrayElements(env, out, bits, 0);
    if (in != NULL) (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
}

/* Decodes `frames` consecutive frames from `bits` into PCM in `out`. */
JNIEXPORT void JNICALL Java_org_meshtastic_core_codec2_Codec2Native_decodeFrames(JNIEnv *env, jobject thiz,
                                                                                  jlong handle, jbyteArray bits,
                                                                                  jshortArray out, jint frames) {
    (void)thiz;
    struct CODEC2 *c2 = C2(handle);
    int samples = codec2_samples_per_frame(c2);
    int bytes = codec2_bytes_per_frame(c2);
    jbyte *in = (*env)->GetByteArrayElements(env, bits, NULL);
    jshort *pcm = (*env)->GetShortArrayElements(env, out, NULL);
    if (in != NULL && pcm != NULL) {
        for (int i = 0; i < frames; i++) {
            codec2_decode(c2, pcm + (size_t)i * samples, (const unsigned char *)in + (size_t)i * bytes);
        }
    }
    if (pcm != NULL) (*env)->ReleaseShortArrayElements(env, out, pcm, 0);
    if (in != NULL) (*env)->ReleaseByteArrayElements(env, bits, in, JNI_ABORT);
}

#include <jni.h>
#include <stdint.h>
#include <lame.h>

JNIEXPORT jlong JNICALL
Java_br_app_baixavideo_Mp3Encoder_init(JNIEnv *env, jclass cls, jint sampleRate, jint channels, jint kbps) {
    lame_global_flags *gf = lame_init();
    if (!gf) return 0;
    lame_set_in_samplerate(gf, sampleRate);
    lame_set_num_channels(gf, channels);
    lame_set_VBR(gf, vbr_off);
    lame_set_brate(gf, kbps);
    lame_set_quality(gf, 2);
    lame_set_mode(gf, channels == 1 ? MONO : JOINT_STEREO);
    if (lame_init_params(gf) < 0) {
        lame_close(gf);
        return 0;
    }
    return (jlong) (intptr_t) gf;
}

JNIEXPORT jint JNICALL
Java_br_app_baixavideo_Mp3Encoder_encode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint frames,
                                         jint channels, jbyteArray out) {
    lame_global_flags *gf = (lame_global_flags *) (intptr_t) h;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *ob = (*env)->GetByteArrayElements(env, out, NULL);
    jsize outLen = (*env)->GetArrayLength(env, out);
    int n;
    if (channels == 1)
        n = lame_encode_buffer(gf, in, in, frames, (unsigned char *) ob, outLen);
    else
        n = lame_encode_buffer_interleaved(gf, in, frames, (unsigned char *) ob, outLen);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, ob, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_br_app_baixavideo_Mp3Encoder_flush(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    lame_global_flags *gf = (lame_global_flags *) (intptr_t) h;
    jbyte *ob = (*env)->GetByteArrayElements(env, out, NULL);
    int n = lame_encode_flush(gf, (unsigned char *) ob, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, ob, 0);
    return n;
}

JNIEXPORT void JNICALL
Java_br_app_baixavideo_Mp3Encoder_close(JNIEnv *env, jclass cls, jlong h) {
    if (h) lame_close((lame_global_flags *) (intptr_t) h);
}

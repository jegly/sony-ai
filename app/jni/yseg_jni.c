// JNI glue between com.ghosts.app.Yseg and the engine in ../../engine.
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "yseg.h"

static void throw_error(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

JNIEXPORT jint JNICALL Java_com_ghosts_app_Yseg_nativeLoad(JNIEnv *env, jclass cls, jobject assets, jstring jname) {
    AAssetManager *am = AAssetManager_fromJava(env, assets);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    AAsset *a = am ? AAssetManager_open(am, name, AASSET_MODE_BUFFER) : NULL;
    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (!a) {
        throw_error(env, "model asset not found");
        return 0;
    }
    const void *buf = AAsset_getBuffer(a);
    size_t len = AAsset_getLength(a);
    char err[200] = "";
    YSeg *m = buf ? yseg_load(buf, len, err, sizeof err) : NULL;
    AAsset_close(a);
    if (!m) {
        throw_error(env, buf ? err : "could not read the model asset");
        return 0;
    }
    return (jint) (intptr_t) m;
}

JNIEXPORT void JNICALL Java_com_ghosts_app_Yseg_nativeFree(JNIEnv *env, jclass cls, jint handle) {
    yseg_free((YSeg *) (intptr_t) handle);
}

/* Boxes of the people found by the last run: x1 y1 x2 y2 score each, model input pixels. */
JNIEXPORT jfloatArray JNICALL Java_com_ghosts_app_Yseg_nativeBoxes(JNIEnv *env, jclass cls, jint handle) {
    float buf[5 * 300];
    int n = yseg_boxes((YSeg *) (intptr_t) handle, buf, 300);
    jfloatArray out = (*env)->NewFloatArray(env, n * 5);
    if (out && n > 0) (*env)->SetFloatArrayRegion(env, out, 0, n * 5, buf);
    return out;
}

/* Runs a plain model (no people step) and copies all its outputs, one after another, into out.
 * Returns how many floats were written, or -1. */
JNIEXPORT jint JNICALL Java_com_ghosts_app_Yseg_nativeRunRaw(JNIEnv *env, jclass cls, jint handle, jbyteArray jrgb,
        jint w, jint h, jfloatArray jout) {
    YSeg *m = (YSeg *) (intptr_t) handle;
    if ((*env)->GetArrayLength(env, jrgb) < w * h * 3) {
        throw_error(env, "buffer too small");
        return -1;
    }
    unsigned char *rgb = malloc((size_t) w * h * 3);
    if (!rgb) {
        throw_error(env, "out of memory");
        return -1;
    }
    (*env)->GetByteArrayRegion(env, jrgb, 0, w * h * 3, (jbyte *) rgb);
    int ok = yseg_run(m, rgb, w, h, 1);
    free(rgb);
    if (ok != 0) {
        throw_error(env, "out of memory running the model");
        return -1;
    }
    jsize cap = (*env)->GetArrayLength(env, jout);
    int off = 0;
    for (int i = 0; i < yseg_num_outputs(m); i++) {
        int c, hh, ww;
        const float *d = yseg_output(m, i, &c, &hh, &ww);
        int n = c * hh * ww;
        if (off + n > cap) {
            throw_error(env, "output buffer too small");
            return -1;
        }
        (*env)->SetFloatArrayRegion(env, jout, off, n, d);
        off += n;
    }
    return off;
}

/* Runs the model on rgb (w*h*3 bytes) and writes the people mask (w*h) into mask.
 * Returns the number of people. timings (9 doubles, may be null) gets ms per op type. */
JNIEXPORT jint JNICALL Java_com_ghosts_app_Yseg_nativeRun(JNIEnv *env, jclass cls, jint handle, jbyteArray jrgb,
        jint w, jint h, jint threads, jfloat conf, jbyteArray jclasses, jbyteArray jmask, jdoubleArray jtimes) {
    YSeg *m = (YSeg *) (intptr_t) handle;
    if ((*env)->GetArrayLength(env, jrgb) < w * h * 3 || (*env)->GetArrayLength(env, jmask) < w * h) {
        throw_error(env, "buffer too small");
        return -1;
    }
    unsigned char *rgb = malloc((size_t) w * h * 3);
    unsigned char *mask = malloc((size_t) w * h);
    if (!rgb || !mask) {
        free(rgb);
        free(mask);
        throw_error(env, "out of memory");
        return -1;
    }
    (*env)->GetByteArrayRegion(env, jrgb, 0, w * h * 3, (jbyte *) rgb);
    unsigned char classes[80];
    int nc = jclasses ? (*env)->GetArrayLength(env, jclasses) : 0;
    if (nc > 80) nc = 80;
    memset(classes, 0, sizeof classes);
    if (nc > 0) (*env)->GetByteArrayRegion(env, jclasses, 0, nc, (jbyte *) classes);
    else classes[0] = 1;
    int n = -1;
    if (yseg_run(m, rgb, w, h, threads) == 0) n = yseg_objects(m, conf, 0.7f, mask, 300, classes);
    free(rgb);
    if (n < 0) {
        free(mask);
        throw_error(env, "out of memory running the model");
        return -1;
    }
    (*env)->SetByteArrayRegion(env, jmask, 0, w * h, (jbyte *) mask);
    free(mask);
    if (jtimes) {
        double t[9];
        yseg_timings(m, t, 9);
        jsize len = (*env)->GetArrayLength(env, jtimes);
        (*env)->SetDoubleArrayRegion(env, jtimes, 0, len < 9 ? len : 9, t);
    }
    return n;
}

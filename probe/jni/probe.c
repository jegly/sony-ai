// Small native benchmarks for the AI probe. Plain C only (no libc calls beyond malloc/free),
// so it loads on the camera's old Android. probe_neon.c holds the NEON version.
#include <jni.h>
#include <stdlib.h>

/* C = A * B for n x n float matrices, simple blocked loop. */
static void matmul(const float *a, const float *b, float *c, int n) {
    for (int i = 0; i < n * n; i++) c[i] = 0;
    for (int i = 0; i < n; i++) {
        for (int k = 0; k < n; k++) {
            float av = a[i * n + k];
            const float *br = b + k * n;
            float *cr = c + i * n;
            for (int j = 0; j < n; j++) cr[j] += av * br[j];
        }
    }
}

void matmul_neon(const float *a, const float *b, float *c, int n);

static jfloat run(int n, int reps, int neon) {
    float *a = malloc(sizeof(float) * n * n);
    float *b = malloc(sizeof(float) * n * n);
    float *c = malloc(sizeof(float) * n * n);
    if (!a || !b || !c) {
        free(a); free(b); free(c);
        return -1;
    }
    for (int i = 0; i < n * n; i++) {
        a[i] = (float) (i % 7) * 0.25f;
        b[i] = (float) (i % 5) * 0.5f;
    }
    for (int r = 0; r < reps; r++) {
        if (neon) matmul_neon(a, b, c, n);
        else matmul(a, b, c, n);
    }
    float sum = 0;
    for (int i = 0; i < n * n; i++) sum += c[i];
    free(a); free(b); free(c);
    return sum;
}

JNIEXPORT jfloat JNICALL Java_com_ghosts_probe_ProbeActivity_nativeMatmul(JNIEnv *env, jclass cls, jint n, jint reps) {
    return run(n, reps, 0);
}

JNIEXPORT jfloat JNICALL Java_com_ghosts_probe_ProbeActivity_neonMatmul(JNIEnv *env, jclass cls, jint n, jint reps) {
    return run(n, reps, 1);
}

void gemm_fma(const float *ap, const float *bp, float *c, int M, int K, int N, int threads);
void gemm_mla(const float *ap, const float *bp, float *c, int M, int K, int N, int threads);

/* Runs one conv-shaped multiply reps times; returns the checksum. Timing is done in Java. */
JNIEXPORT jfloat JNICALL Java_com_ghosts_probe_ProbeActivity_gemm(JNIEnv *env, jclass cls,
        jint fused, jint M, jint K, jint N, jint threads, jint reps) {
    float *a = malloc(sizeof(float) * M * K);
    float *b = malloc(sizeof(float) * K * N);
    float *c = malloc(sizeof(float) * M * N);
    if (!a || !b || !c) {
        free(a); free(b); free(c);
        return -1;
    }
    for (int i = 0; i < M * K; i++) a[i] = (float) (i % 7) * 0.01f;
    for (int i = 0; i < K * N; i++) b[i] = (float) (i % 5) * 0.02f;
    for (int r = 0; r < reps; r++) {
        if (fused) gemm_fma(a, b, c, M, K, N, threads);
        else gemm_mla(a, b, c, M, K, N, threads);
    }
    float sum = 0;
    for (int i = 0; i < M * N; i += 97) sum += c[i];
    free(a); free(b); free(c);
    return sum;
}

// Matrix multiply in the shape a YOLO conv layer needs (C = A * B, A = weights M x K,
// B = image patches K x N), with a 4x4 register block and pthreads. No NEON.
// Built twice: gemm_fma (vfma, fused) and gemm_mla (vmla, separate multiply and add).
#include <pthread.h>
#include <stdlib.h>

#ifndef NAME
#define NAME gemm_fma
#endif

/* Ap: A packed as [M/4][K][4]; Bp: B packed as [N/4][K][4]; C: M x N row-major. */
static void kernel(const float *ap, const float *bp, float *c, int K, int ldc) {
    float c00 = 0, c01 = 0, c02 = 0, c03 = 0, c10 = 0, c11 = 0, c12 = 0, c13 = 0;
    float c20 = 0, c21 = 0, c22 = 0, c23 = 0, c30 = 0, c31 = 0, c32 = 0, c33 = 0;
    for (int k = 0; k < K; k++) {
        float a0 = ap[0], a1 = ap[1], a2 = ap[2], a3 = ap[3];
        float b0 = bp[0], b1 = bp[1], b2 = bp[2], b3 = bp[3];
        c00 += a0 * b0; c01 += a0 * b1; c02 += a0 * b2; c03 += a0 * b3;
        c10 += a1 * b0; c11 += a1 * b1; c12 += a1 * b2; c13 += a1 * b3;
        c20 += a2 * b0; c21 += a2 * b1; c22 += a2 * b2; c23 += a2 * b3;
        c30 += a3 * b0; c31 += a3 * b1; c32 += a3 * b2; c33 += a3 * b3;
        ap += 4;
        bp += 4;
    }
    c[0] = c00; c[1] = c01; c[2] = c02; c[3] = c03; c += ldc;
    c[0] = c10; c[1] = c11; c[2] = c12; c[3] = c13; c += ldc;
    c[0] = c20; c[1] = c21; c[2] = c22; c[3] = c23; c += ldc;
    c[0] = c30; c[1] = c31; c[2] = c32; c[3] = c33;
}

typedef struct {
    const float *ap, *bp;
    float *c;
    int M, K, N, n0, n1;
} Job;

static void *work(void *arg) {
    Job *j = arg;
    for (int n = j->n0; n < j->n1; n += 4) {
        const float *bp = j->bp + (size_t) n * j->K;
        for (int m = 0; m < j->M; m += 4) {
            kernel(j->ap + (size_t) m * j->K, bp, j->c + (size_t) m * j->N + n, j->K, j->N);
        }
    }
    return 0;
}

void NAME(const float *ap, const float *bp, float *c, int M, int K, int N, int threads) {
    pthread_t t[8];
    Job jobs[8];
    int per = (N / 4 + threads - 1) / threads * 4;
    for (int i = 0; i < threads; i++) {
        int n0 = i * per, n1 = n0 + per > N ? N : n0 + per;
        jobs[i] = (Job) {ap, bp, c, M, K, N, n0, n1};
        if (i > 0) pthread_create(&t[i], 0, work, &jobs[i]);
    }
    work(&jobs[0]);
    for (int i = 1; i < threads; i++) pthread_join(t[i], 0);
}

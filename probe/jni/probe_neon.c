// NEON matrix multiply, only called when /proc/cpuinfo lists neon. n must be a multiple of 4.
#include <arm_neon.h>

void matmul_neon(const float *a, const float *b, float *c, int n) {
    for (int i = 0; i < n * n; i++) c[i] = 0;
    for (int i = 0; i < n; i++) {
        float *cr = c + i * n;
        for (int k = 0; k < n; k++) {
            float32x4_t av = vdupq_n_f32(a[i * n + k]);
            const float *br = b + k * n;
            for (int j = 0; j < n; j += 4) {
                vst1q_f32(cr + j, vmlaq_f32(vld1q_f32(cr + j), av, vld1q_f32(br + j)));
            }
        }
    }
}

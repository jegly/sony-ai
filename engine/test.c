// Desktop test: yseg_test model.bin image.rgb w h threads out.bin
// Writes each output as raw floats, then the people mask, and prints timings.
#include "yseg.h"
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static void *slurp(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    *len = ftell(f);
    fseek(f, 0, SEEK_SET);
    void *b = malloc(*len);
    if (fread(b, 1, *len, f) != *len) { fclose(f); free(b); return NULL; }
    fclose(f);
    return b;
}

int main(int argc, char **argv) {
    if (argc < 7) { fprintf(stderr, "usage: yseg_test model.bin image.rgb w h threads out.bin\n"); return 2; }
    size_t mlen, ilen;
    void *mdata = slurp(argv[1], &mlen);
    unsigned char *rgb = slurp(argv[2], &ilen);
    int w = atoi(argv[3]), h = atoi(argv[4]), th = atoi(argv[5]);
    if (!mdata || !rgb || ilen != (size_t) w * h * 3) { fprintf(stderr, "bad input\n"); return 1; }
    char err[200];
    YSeg *m = yseg_load(mdata, mlen, err, sizeof err);
    if (!m) { fprintf(stderr, "load: %s\n", err); return 1; }
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    if (yseg_run(m, rgb, w, h, th)) { fprintf(stderr, "run failed\n"); return 1; }
    clock_gettime(CLOCK_MONOTONIC, &b);
    printf("run %.0f ms\n", (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6);
    double ms[9];
    yseg_timings(m, ms, 9);
    const char *names[] = {"", "conv", "add", "concat", "slice", "maxpool", "up2", "convt2", "attn"};
    for (int i = 1; i < 9; i++) printf("  %s %.1f ms\n", names[i], ms[i]);
    FILE *f = fopen(argv[6], "wb");
    for (int i = 0; i < yseg_num_outputs(m); i++) {
        int c, hh, ww;
        const float *d = yseg_output(m, i, &c, &hh, &ww);
        fwrite(d, sizeof(float), (size_t) c * hh * ww, f);
    }
    unsigned char *mask = malloc(w * h);
    int n = yseg_people(m, 0.2f, 0.7f, mask, 300);
    printf("people %d\n", n);
    float boxes[5 * 64];
    int nb = n > 0 ? yseg_boxes(m, boxes, 64) : 0;
    for (int i = 0; i < nb; i++) printf("box %.1f %.1f %.1f %.1f %.3f\n", boxes[5 * i], boxes[5 * i + 1], boxes[5 * i + 2], boxes[5 * i + 3], boxes[5 * i + 4]);
    fwrite(mask, 1, w * h, f);
    fclose(f);
    yseg_free(m);
    return 0;
}

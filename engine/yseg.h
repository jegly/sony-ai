// A small engine that runs a YOLO11-seg model exported by tools/lower.py, written for the
// Sony A7 II's CPU (Cortex-A5, VFPv4, no NEON). Plain C99 plus pthreads.
#ifndef YSEG_H
#define YSEG_H

#include <stddef.h>

typedef struct YSeg YSeg;

/* Parses a model file (the buffer can be freed afterwards). Returns NULL and fills err on failure. */
YSeg *yseg_load(const void *data, size_t len, char *err, int errlen);
void yseg_free(YSeg *m);

/* Runs the network on an interleaved RGB image (w and h multiples of 32). threads: 1..8.
 * Returns 0, or -1 if out of memory. */
int yseg_run(YSeg *m, const unsigned char *rgb, int w, int h, int threads);

/* After yseg_run: raw output i (see lower.py for the order). */
const float *yseg_output(YSeg *m, int i, int *c, int *h, int *w);
int yseg_num_outputs(YSeg *m);

/* After yseg_run: finds people (score >= conf, NMS at iou) and writes their combined mask
 * probability (0..255) at the input resolution (w x h) into mask. Returns how many people
 * were found, or -1 if out of memory. */
int yseg_people(YSeg *m, float conf, float iou, unsigned char *mask, int max_people);

/* After yseg_people: the people's boxes (x1 y1 x2 y2 score in input pixels, best first), up to max.
 * Returns how many. */
int yseg_boxes(YSeg *m, float *out, int max);

/* Milliseconds spent in each op type during the last run, for tuning. Fills up to n values. */
void yseg_timings(YSeg *m, double *ms, int n);

#endif

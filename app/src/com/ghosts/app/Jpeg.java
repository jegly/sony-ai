package com.ghosts.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** JPEG helpers from Film Sim: the original's Exif, and a streaming encoder for copies too big for memory. */
public class Jpeg {
    /** Returns the original's Exif APP1 segment (marker included) so the copy keeps its date and settings. */
    public static byte[] readExif(InputStream in) throws IOException {
        try {
            if (in.read() != 0xFF || in.read() != 0xD8) return null;
            while (true) {
                int ff = in.read(), marker = in.read();
                if (ff != 0xFF || marker < 0 || marker == 0xDA || marker == 0xD9) return null;
                int len = (in.read() << 8) | in.read();
                if (len < 2) return null;
                byte[] seg = new byte[len - 2];
                int got = 0;
                while (got < seg.length) {
                    int n = in.read(seg, got, seg.length - got);
                    if (n < 0) return null;
                    got += n;
                }
                if (marker == 0xE1 && seg.length > 6 && seg[0] == 'E' && seg[1] == 'x' && seg[2] == 'i' && seg[3] == 'f') {
                    byte[] out = new byte[len + 2];
                    out[0] = (byte) 0xFF;
                    out[1] = (byte) 0xE1;
                    out[2] = (byte) (len >> 8);
                    out[3] = (byte) len;
                    System.arraycopy(seg, 0, out, 4, seg.length);
                    return out;
                }
            }
        } finally {
            in.close();
        }
    }

    /** Streaming baseline JPEG encoder, 4:2:0, fed 16 rows at a time. */
    public static class Writer {
        private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21,
            28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61,
            54, 47, 55, 62, 63};
        private static final int[] LUMA_Q = {
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51,
            87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99};
        private static final int[] CHROMA_Q = {
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99};
        // standard Huffman tables (ITU T.81 annex K)
        private static final int[] DC_L_BITS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
        private static final int[] DC_L_VAL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        private static final int[] DC_C_BITS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
        private static final int[] DC_C_VAL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        private static final int[] AC_L_BITS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
        private static final int[] AC_L_VAL = {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14,
            0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09,
            0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a,
            0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65,
            0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88,
            0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9,
            0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca,
            0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea,
            0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa};
        private static final int[] AC_C_BITS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
        private static final int[] AC_C_VAL = {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32,
            0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16,
            0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39,
            0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64,
            0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86,
            0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8,
            0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9,
            0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa};

        private final OutputStream out;
        private final int width, height;
        private final int[] qY = new int[64], qC = new int[64];
        private final int[][] dcL, dcC, acL, acC; // [symbol] -> {code, length}
        private int bitBuf, bitCount;
        private int predY, predCb, predCr;
        private int rowsWritten;
        // one MCU row (16 rows) of Y, Cb, Cr at full and half resolution
        private final float[] y16, cb8, cr8;
        private final int mcuW;
        private final float[] block = new float[64];
        private final float[] divY = new float[64], divC = new float[64];
        private static final double[] AAN = {1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379};
        private final int[] zz = new int[64];

        public Writer(OutputStream out, int width, int height, int quality, byte[] exifApp1) throws IOException {
            this.out = out;
            this.width = width;
            this.height = height;
            int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
            for (int i = 0; i < 64; i++) {
                qY[i] = Math.max(1, Math.min(255, (LUMA_Q[i] * scale + 50) / 100));
                qC[i] = Math.max(1, Math.min(255, (CHROMA_Q[i] * scale + 50) / 100));
            }
            for (int i = 0; i < 64; i++) {
                double sc = AAN[i / 8] * AAN[i % 8] * 8.0;
                divY[i] = (float) (1.0 / (qY[i] * sc));
                divC[i] = (float) (1.0 / (qC[i] * sc));
            }
            dcL = huff(DC_L_BITS, DC_L_VAL);
            dcC = huff(DC_C_BITS, DC_C_VAL);
            acL = huff(AC_L_BITS, AC_L_VAL);
            acC = huff(AC_C_BITS, AC_C_VAL);
            mcuW = (width + 15) / 16;
            y16 = new float[mcuW * 16 * 16];
            cb8 = new float[mcuW * 8 * 8];
            cr8 = new float[mcuW * 8 * 8];
            writeHeaders(exifApp1);
        }

        private static int[][] huff(int[] bits, int[] vals) {
            int[][] table = new int[256][];
            int code = 0, k = 0;
            for (int len = 1; len <= 16; len++) {
                for (int i = 0; i < bits[len - 1]; i++) table[vals[k++]] = new int[] {code++, len};
                code <<= 1;
            }
            return table;
        }

        private void writeHeaders(byte[] exif) throws IOException {
            out.write(0xFF);
            out.write(0xD8);
            if (exif != null) {
                out.write(exif);
            } else {
                out.write(new byte[] {(byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
            }
            // DQT
            out.write(new byte[] {(byte) 0xFF, (byte) 0xDB, 0, (byte) 132});
            out.write(0);
            for (int i = 0; i < 64; i++) out.write(qY[ZIGZAG[i]]);
            out.write(1);
            for (int i = 0; i < 64; i++) out.write(qC[ZIGZAG[i]]);
            // SOF0
            out.write(new byte[] {(byte) 0xFF, (byte) 0xC0, 0, 17, 8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 3,
                1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1});
            // DHT
            writeDht(0x00, DC_L_BITS, DC_L_VAL);
            writeDht(0x10, AC_L_BITS, AC_L_VAL);
            writeDht(0x01, DC_C_BITS, DC_C_VAL);
            writeDht(0x11, AC_C_BITS, AC_C_VAL);
            // SOS
            out.write(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 12, 3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0});
        }

        private void writeDht(int cls, int[] bits, int[] vals) throws IOException {
            int len = 2 + 1 + 16 + vals.length;
            out.write(0xFF);
            out.write(0xC4);
            out.write(len >> 8);
            out.write(len);
            out.write(cls);
            for (int b : bits) out.write(b);
            for (int v : vals) out.write(v);
        }

        /** Takes `count` rows (16 per call, fewer only for the last call) of packed RGB. */
        public void writeRows(int[] rgb, int count) throws IOException {
            for (int r0 = 0; r0 < count; r0 += 16) {
                int n = Math.min(16, count - r0);
                fillMcuRow(rgb, r0, n);
                encodeMcuRow();
                rowsWritten += n;
            }
        }

        private void fillMcuRow(int[] rgb, int r0, int n) {
            int fullW = mcuW * 16;
            java.util.Arrays.fill(cb8, 0);
            java.util.Arrays.fill(cr8, 0);
            for (int r = 0; r < 16; r++) {
                int sr = r0 + Math.min(r, n - 1); // repeat the last row past the bottom edge
                for (int x = 0; x < fullW; x++) {
                    int px = rgb[sr * width + Math.min(x, width - 1)];
                    int R = (px >> 16) & 255, G = (px >> 8) & 255, B = px & 255;
                    y16[r * fullW + x] = ((19595 * R + 38470 * G + 7471 * B) >> 16) - 128;
                    int ci = (r >> 1) * (mcuW * 8) + (x >> 1);
                    cb8[ci] += ((-11059 * R - 21709 * G + 32768 * B) >> 16) * 0.25f;
                    cr8[ci] += ((32768 * R - 27439 * G - 5329 * B) >> 16) * 0.25f;
                }
            }
        }

        private void encodeMcuRow() throws IOException {
            int fullW = mcuW * 16, cW = mcuW * 8;
            for (int m = 0; m < mcuW; m++) {
                for (int by = 0; by < 2; by++) {
                    for (int bx = 0; bx < 2; bx++) {
                        for (int y = 0; y < 8; y++) {
                            System.arraycopy(y16, (by * 8 + y) * fullW + m * 16 + bx * 8, block, y * 8, 8);
                        }
                        predY = encodeBlock(qY, dcL, acL, predY);
                    }
                }
                for (int y = 0; y < 8; y++) System.arraycopy(cb8, y * cW + m * 8, block, y * 8, 8);
                predCb = encodeBlock(qC, dcC, acC, predCb);
                for (int y = 0; y < 8; y++) System.arraycopy(cr8, y * cW + m * 8, block, y * 8, 8);
                predCr = encodeBlock(qC, dcC, acC, predCr);
            }
        }

        private int encodeBlock(int[] q, int[][] dc, int[][] ac, int pred) throws IOException {
            float[] d = block;
            for (int i = 0; i < 64; i += 8) fdct8(d, i, 1);
            for (int i = 0; i < 8; i++) fdct8(d, i, 8);
            float[] div = q == qY ? divY : divC;
            for (int i = 0; i < 64; i++) zz[i] = Math.round(d[ZIGZAG[i]] * div[ZIGZAG[i]]);
            int diff = zz[0] - pred;
            int cat = category(diff);
            emit(dc[cat]);
            if (cat > 0) bits(diff < 0 ? diff + (1 << cat) - 1 : diff, cat);
            int run = 0;
            for (int i = 1; i < 64; i++) {
                if (zz[i] == 0) {
                    run++;
                    continue;
                }
                while (run > 15) {
                    emit(ac[0xF0]);
                    run -= 16;
                }
                int c = category(zz[i]);
                emit(ac[(run << 4) | c]);
                bits(zz[i] < 0 ? zz[i] + (1 << c) - 1 : zz[i], c);
                run = 0;
            }
            if (run > 0) emit(ac[0x00]);
            return zz[0];
        }

        /** One 8-point AAN forward DCT pass over d[o], d[o + step], ... */
        private static void fdct8(float[] d, int o, int step) {
            int i0 = o, i1 = o + step, i2 = o + 2 * step, i3 = o + 3 * step, i4 = o + 4 * step, i5 = o + 5 * step, i6 = o + 6 * step,
                    i7 = o + 7 * step;
            float t0 = d[i0] + d[i7], t7 = d[i0] - d[i7];
            float t1 = d[i1] + d[i6], t6 = d[i1] - d[i6];
            float t2 = d[i2] + d[i5], t5 = d[i2] - d[i5];
            float t3 = d[i3] + d[i4], t4 = d[i3] - d[i4];
            float t10 = t0 + t3, t13 = t0 - t3, t11 = t1 + t2, t12 = t1 - t2;
            d[i0] = t10 + t11;
            d[i4] = t10 - t11;
            float z1 = (t12 + t13) * 0.707106781f;
            d[i2] = t13 + z1;
            d[i6] = t13 - z1;
            t10 = t4 + t5;
            t11 = t5 + t6;
            t12 = t6 + t7;
            float z5 = (t10 - t12) * 0.382683433f;
            float z2 = 0.541196100f * t10 + z5;
            float z4 = 1.306562965f * t12 + z5;
            float z3 = t11 * 0.707106781f;
            float z11 = t7 + z3, z13 = t7 - z3;
            d[i5] = z13 + z2;
            d[i3] = z13 - z2;
            d[i1] = z11 + z4;
            d[i7] = z11 - z4;
        }

        private static int category(int v) {
            v = Math.abs(v);
            int c = 0;
            while (v > 0) {
                c++;
                v >>= 1;
            }
            return c;
        }

        private void emit(int[] code) throws IOException {
            bits(code[0], code[1]);
        }

        private final byte[] obuf = new byte[64 * 1024];
        private int opos;

        private void put(int b) throws IOException {
            if (opos == obuf.length) {
                out.write(obuf, 0, opos);
                opos = 0;
            }
            obuf[opos++] = (byte) b;
        }

        /** Appends `len` (at most 16) bits, writing whole bytes as they fill. */
        private void bits(int value, int len) throws IOException {
            bitBuf = (bitBuf << len) | (value & ((1 << len) - 1));
            bitCount += len;
            while (bitCount >= 8) {
                int b = (bitBuf >> (bitCount - 8)) & 0xFF;
                put(b);
                if (b == 0xFF) put(0);
                bitCount -= 8;
            }
            bitBuf &= (1 << bitCount) - 1;
        }

        public void finish() throws IOException {
            if (bitCount > 0) bits((1 << (8 - bitCount)) - 1, 8 - bitCount);
            put(0xFF);
            put(0xD9);
            out.write(obuf, 0, opos);
            opos = 0;
            out.flush();
        }
    }
}

package com.ghosts.probe;

import android.app.Activity;
import android.app.DAConnectionManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.view.KeyEvent;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sony.scalar.sysutil.ScalarInput;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Finds out whether the A7 II can run a neural network, and how fast.
 *
 * Runs once on start: device info, a Java and a native maths benchmark, then a tuned
 * multi-core matrix multiply shaped like YOLO's conv layers. (The first probe showed the
 * CPU has no NEON, so LiteRT's arm builds can't run here.) Each step is written to AIPROBE/LOG.TXT before it starts, so if the
 * app crashes the log shows where.
 */
public class ProbeActivity extends Activity {

    private TextView text;
    private ScrollView scroll;
    private FileWriter log;
    private final StringBuilder screen = new StringBuilder();
    private boolean started;
    private boolean hasNeon;

    static native float nativeMatmul(int n, int reps);

    static native float neonMatmul(int n, int reps);

    static native float gemm(int fused, int m, int k, int n, int threads, int reps);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        text = (TextView) findViewById(R.id.text);
        scroll = (ScrollView) findViewById(R.id.scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyAppInfo();
        openLog();
        if (!started) {
            started = true;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    runAll();
                }
            });
            t.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeLog();
    }

    private void runAll() {
        log("==== AI probe start");
        try {
            deviceInfo();
        } catch (Throwable t) {
            log("device info failed: " + t);
        }
        try {
            javaBench();
        } catch (Throwable t) {
            log("java bench failed: " + t);
        }
        try {
            nativeBench();
        } catch (Throwable t) {
            log("native bench failed: " + t);
        }
        try {
            gemmBench();
        } catch (Throwable t) {
            log("gemm bench failed: " + t);
        }
        log("==== done. Press MENU to exit.");
    }

    // ---------------- tests ----------------

    private void deviceInfo() throws IOException {
        log("Build: " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE
                + " abi=" + Build.CPU_ABI + " abi2=" + Build.CPU_ABI2 + " board=" + Build.BOARD + " hw=" + Build.HARDWARE);
        Runtime rt = Runtime.getRuntime();
        log("cores=" + rt.availableProcessors() + " heap max=" + rt.maxMemory() / 1024 + "K total=" + rt.totalMemory() / 1024
                + "K free=" + rt.freeMemory() / 1024 + "K");
        String cpu = readFile("/proc/cpuinfo", 60);
        log("cpuinfo:\n" + cpu);
        hasNeon = cpu.indexOf(" neon") >= 0;
        log("neon=" + hasNeon);
        log("meminfo:\n" + readFile("/proc/meminfo", 8));
        log("cpu max freq: " + readFile("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq", 1).trim());
        log("online cpus: " + readFile("/sys/devices/system/cpu/online", 1).trim());
        StatFs fs = new StatFs(getFilesDir().getPath());
        log("app storage free=" + (long) fs.getAvailableBlocks() * fs.getBlockSize() / 1024 + "K");
        log("nativeLibraryDir=" + getApplicationInfo().nativeLibraryDir);
        String[] libs = new File(getApplicationInfo().nativeLibraryDir).list();
        log("extracted libs: " + (libs == null ? "none" : java.util.Arrays.toString(libs)));
    }

    private void javaBench() {
        int n = 96;
        float[] a = new float[n * n], b = new float[n * n], c = new float[n * n];
        for (int i = 0; i < n * n; i++) {
            a[i] = (i % 7) * 0.25f;
            b[i] = (i % 5) * 0.5f;
        }
        long t0 = System.nanoTime();
        int reps = 3;
        for (int r = 0; r < reps; r++) {
            for (int i = 0; i < n * n; i++) c[i] = 0;
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < n; k++) {
                    float av = a[i * n + k];
                    int bo = k * n, co = i * n;
                    for (int j = 0; j < n; j++) c[co + j] += av * b[bo + j];
                }
            }
        }
        long ns = System.nanoTime() - t0;
        log("java matmul " + n + ": " + mflops(n, reps, ns));
    }

    private void nativeBench() throws IOException {
        log("loading libprobe");
        boolean loaded = false;
        try {
            System.loadLibrary("probe");
            loaded = true;
            log("libprobe loaded from the APK's lib folder");
        } catch (Throwable t) {
            log("loadLibrary(probe) failed: " + t + ", trying a copy from assets");
        }
        if (!loaded) {
            System.load(copyAsset("libs/libprobe.so", "libprobe.so").getPath());
            log("libprobe loaded from a copy");
        }
        int n = 128;
        int reps = 4;
        long t0 = System.nanoTime();
        float s = nativeMatmul(n, reps);
        log("C matmul " + n + ": " + mflops(n, reps, System.nanoTime() - t0) + " (check " + s + ")");
        if (hasNeon) {
            log("running NEON matmul");
            t0 = System.nanoTime();
            s = neonMatmul(n, reps);
            log("NEON matmul " + n + ": " + mflops(n, reps, System.nanoTime() - t0) + " (check " + s + ")");
        } else {
            log("no NEON in cpuinfo, skipping the NEON test");
        }
    }

    /** Conv-layer-shaped multiplies, the bulk of YOLO's work, on 1, 2 and 4 cores. */
    private void gemmBench() {
        int[][] shapes = {{32, 288, 6400}, {64, 576, 1600}, {128, 1152, 400}};
        int[] threads = {1, 2, 4};
        for (int fused = 1; fused >= 0; fused--) {
            for (int[] sh : shapes) {
                for (int th : threads) {
                    int m = sh[0], k = sh[1], n = sh[2];
                    long t0 = System.nanoTime();
                    float sum = gemm(fused, m, k, n, th, 2);
                    long ns = System.nanoTime() - t0;
                    double flops = 2.0 * m * k * n * 2;
                    log(String.format("%s M=%d K=%d N=%d, %d core: %.0f ms, %.0f MFLOPS (check %.1f)",
                            fused == 1 ? "fma" : "mla", m, k, n, th, ns / 1e6, flops / (ns / 1e3), sum));
                }
            }
        }
    }

    private File copyAsset(String asset, String name) throws IOException {
        File f = new File(getFilesDir(), name);
        InputStream is = getAssets().open(asset);
        OutputStream os = new FileOutputStream(f);
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = is.read(b)) > 0) os.write(b, 0, n);
        os.close();
        is.close();
        return f;
    }

    private static String readFile(String path, int maxLines) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < maxLines) sb.append(line).append('\n');
            r.close();
        } catch (IOException e) {
            sb.append("(").append(e).append(")");
        }
        return sb.toString();
    }

    private static String mflops(int n, int reps, long ns) {
        double flops = 2.0 * n * n * n * reps;
        return String.format("%.0f ms, %.1f MFLOPS", ns / 1e6, flops / (ns / 1e3));
    }

    private static String ms(long t0) {
        return (System.nanoTime() - t0) / 1000000 + " ms";
    }

    // ---------------- log and screen ----------------

    private void openLog() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "AIPROBE");
            dir.mkdirs();
            log = new FileWriter(new File(dir, "LOG.TXT"), true);
        } catch (IOException e) {
            log = null;
        }
    }

    private synchronized void closeLog() {
        if (log != null) {
            try {
                log.close();
            } catch (IOException ignored) {
            }
            log = null;
        }
    }

    private synchronized void log(final String msg) {
        if (log != null) {
            try {
                log.write(msg + "\n");
                log.flush();
            } catch (IOException ignored) {
            }
        }
        runOnUiThread(new Runnable() {
            public void run() {
                screen.append(msg).append('\n');
                text.setText(screen);
                scroll.post(new Runnable() {
                    public void run() {
                        scroll.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    // ---------------- keys and exit ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
            case ScalarInput.ISV_KEY_SK2:
            case ScalarInput.ISV_KEY_DELETE:
            case ScalarInput.ISV_KEY_PLAY:
                exitApp();
                return true;
            case ScalarInput.ISV_KEY_UP:
                scroll.pageScroll(ScrollView.FOCUS_UP);
                return true;
            case ScalarInput.ISV_KEY_DOWN:
                scroll.pageScroll(ScrollView.FOCUS_DOWN);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void exitApp() {
        try {
            new DAConnectionManager(this).finish();
        } catch (Throwable ignored) {
        }
        finish();
    }

    private void notifyAppInfo() {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getComponentName().getPackageName());
        intent.putExtra("class_name", getComponentName().getClassName());
        sendBroadcast(intent);
    }
}

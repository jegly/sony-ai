package com.ghosts.app;

import android.app.Activity;
import android.app.DAConnectionManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.sony.scalar.graphics.OptimizedImage;
import com.sony.scalar.graphics.OptimizedImageFactory;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.media.AvindexContentInfo;
import com.sony.scalar.provider.AvindexStore;
import com.sony.scalar.sysutil.KeyStatus;
import com.sony.scalar.sysutil.ScalarInput;
import com.sony.scalar.sysutil.didep.Gpelibrary;
import com.sony.scalar.widget.OptimizedImageView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Ghosts: after each shot, finds the people in the photo (YOLO11n-seg, run on the camera's own CPU) and
 * saves a copy in GHOSTS with them turned into glowing white figures or glowing wireframes. It can also (or
 * instead) add a diffusion filter look, like a Black Pro-Mist. Nothing is on until picked in the Fn menu.
 *
 * Controls (A7 II): rear dial = shutter (S/M) or exposure comp (P/A), front dial = ISO, Fn = menu
 * (people, diffusion, model size, look, opacity, colour, glow, copy size, Sony's live soft focus or soft high-key,
 * soft skin, white balance, Kelvin, D-Range: up/down picks a row, left/right or
 * the wheel changes it), centre = focus magnifier, C3 = white balance, DISP/up = info on/off,
 * Play = review photos (C1: ghost copy or original, C2: ghost this photo), MENU = exit.
 */
public class GhostsActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterListener {
    // Mode dial positions as reported through ScalarInput (same values as the ISV_KEY_MODE_* scan codes).
    private static final int DIAL_AUTO = 535, DIAL_SUPERIOR_AUTO = 536, DIAL_P = 537, DIAL_S = 538, DIAL_A = 539, DIAL_M = 540;
    private static final int EV_DIAL = 620;
    private static final int MAG_STEP = 100, MAG_LIMIT = 800;

    private final Handler handler = new Handler();

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private boolean surfaceReady;
    private TextView topBar, exposureBar, picker, message, keyInfo;
    private View pickerPanel;

    private CameraEx camera;
    private boolean takingPicture;
    private boolean pausing;
    private boolean overlay = true;

    // Fn menu: one row per setting
    private static final String[] MENU_ROWS = {"People", "Diffusion", "Model", "Look", "Opacity", "Colour", "Glow", "Copy size",
            "Live", "Soft skin", "WB", "Kelvin", "D-Range"};
    private static final int ROW_PEOPLE = 0, ROW_DIFFUSION = 1, ROW_MODEL = 2, ROW_LOOK = 3, ROW_OPACITY = 4, ROW_COLOUR = 5,
            ROW_GLOW = 6, ROW_SIZE = 7, ROW_LIVE = 8, ROW_SKIN = 9, ROW_WB = 10, ROW_KELVIN = 11, ROW_DRO = 12;
    private static final String[] PEOPLE_NAMES = {"Off", "Ghosts", "Wireframe"};
    private static final int[] MODEL_SIZES = {320, 480, 640};
    private static final String[] MODEL_NAMES = {"Fast", "Normal", "Fine"};
    private static final String[] MODEL_NOTES = {"320 px, finds fewer people", "480 px", "640 px, slowest, finds the most people"};
    private static final String[] GLOW_NAMES = {"Off", "Soft", "Strong"};
    private static final int[] OPACITIES = {100, 85, 70, 50, 30};
    private static final String[] LOOK_NOTES = {"opaque white", "feathered, halo of light", "ethereal in a hazy, softer scene"};
    private boolean menuOpen;
    private int menuRow = ROW_PEOPLE;
    private int model = 1;
    private int glow = 2;
    private boolean largeCopy;
    /** Nothing is on until the user picks something: no copies are made. */
    private int people = Ghoster.PEOPLE_OFF;
    /** Diffusion setting, see Diffusion.NAMES (0 = off). */
    private int diffusion = 0;
    private int look = Ghost.ETHEREAL;
    private int opacity = 1;
    private int colour = 0;
    // Sony's own effects, which show in the viewfinder and go into the camera's JPEG (JPEG quality only)
    private final List<String> liveNames = new ArrayList<String>();
    private final List<String[]> liveValues = new ArrayList<String[]>(); // {effect, level or tint}
    private int live;
    private String liveKey = "Off";
    private final List<String> skinModes = new ArrayList<String>();
    private String skin = "off";
    // Sony's D-Range Optimizer: 0 off, 1 auto, 2.. level 1..maxDro
    private int dro;
    private int maxDro = 5;
    private List<String> droModes = new ArrayList<String>();

    // White balance
    private static final String[] WB_ORDER = {"auto", "daylight", "shade", "cloudy-daylight", "incandescent",
            "warm-fluorescent", "fluorescent-coolwhite", "fluorescent-daywhite", "fluorescent-daylight", "flash", "color-temp"};
    private static final String WB_KELVIN = "color-temp";
    private final List<String> wbModes = new ArrayList<String>();
    private String wbMode = "auto";
    private int kelvin = 5500, minKelvin = 2500, maxKelvin = 9900;
    private String savedWb;
    private int savedKelvin;

    // Exposure state
    private String sceneMode = "";
    private List<String> supportedSceneModes = new ArrayList<String>();
    private List<Integer> isoValues = new ArrayList<Integer>();
    private int minEv, maxEv;
    private float evStep;
    private int shutterN, shutterD;
    private int autoIso;
    private int meterLevel = Integer.MIN_VALUE;
    private int iso;
    private int evIndex;

    // Focus magnifier
    private List<Integer> magLevels = new ArrayList<Integer>();
    private int magIndex = -1;
    private int magX, magY;

    // In-app playback
    /** Created only while reviewing photos: kept in the layout it stopped hidden overlays from clearing. */
    private OptimizedImageView playView;
    private RelativeLayout root;
    private TextView playInfo;
    private boolean playback;
    private final List<String> playFiles = new ArrayList<String>();
    private int playIndex;
    private OptimizedImage playImage;
    private String playName;
    /** Shows the ghost copy over the original when one exists. */
    private ImageView devView;
    private Bitmap devBitmap;
    private boolean showGhost = true;
    /** The ghost copy decoded by Sony's viewer, which shows full colour (the app's overlay is 16 levels per colour). */
    private OptimizedImage devImage;

    // Ghost copies, made in the background after each shot
    private Ghoster ghoster;
    private int queued;
    private String stage = "";
    /** Newest files before the next shot. The camera's clock can't be trusted (it reads 1970), so new files are
     * the ones that weren't there before. */
    private File knownJpeg;
    private long exitConfirmUntil;

    private FileWriter log;

    private static File ghostDir() {
        return new File(Environment.getExternalStorageDirectory(), "GHOSTS");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        surfaceHolder.addCallback(this);
        topBar = (TextView) findViewById(R.id.topBar);
        exposureBar = (TextView) findViewById(R.id.exposureBar);
        pickerPanel = findViewById(R.id.pickerPanel);
        picker = (TextView) findViewById(R.id.picker);
        message = (TextView) findViewById(R.id.message);
        keyInfo = (TextView) findViewById(R.id.keyInfo);
        root = (RelativeLayout) findViewById(R.id.root);
        playInfo = (TextView) findViewById(R.id.playInfo);
        ghoster = new Ghoster(ghostDir(), getAssets(), new Ghoster.Listener() {
            public boolean cameraBusy() {
                return takingPicture;
            }

            public void onStatus(final int q, final String s) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        queued = q;
                        stage = s;
                        refreshTopBar();
                    }
                });
            }

            public void onDone(final String source, final File result, final int people, final long millis) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        log((result == null ? "no people in " + source : "copied " + source + " -> " + result.getName())
                                + ", " + people + " people, " + millis + " ms: " + ghoster.lastTiming);
                        if (result == null) {
                            showMessage("No people found in " + source);
                        } else if (people <= 0) {
                            showMessage((people == 0 ? "No people, diffused copy saved: " : "Diffused copy saved: ")
                                    + result.getName() + " (" + (millis / 1000) + " s)");
                        } else {
                            showMessage(people + (people == 1 ? " person" : " people") + " done, saved: " + result.getName()
                                    + " (" + (millis / 1000) + " s)");
                        }
                        if (playback) showPhoto();
                    }
                });
            }

            public void onFailed(final String source, final Throwable error) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        log("ghost " + source + " failed: " + error);
                        showMessage("Ghosting failed: " + error.getMessage());
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        pausing = false;
        openLog();
        notifyAppInfo();
        restorePrefs();
        knownJpeg = newestJpeg(new File(Environment.getExternalStorageDirectory(), "DCIM"));
        if (!openCamera()) return;
        readCapabilities();
        startShooting();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausing = true;
        ghoster.cancel();
        handler.removeCallbacksAndMessages(null);
        savePrefs();
        closePlayback(false);
        closeCamera();
        takingPicture = false;
        log("exit");
        closeLog();
    }

    /** Settings that have to be (re)applied every time the camera is opened. */
    private void startShooting() {
        if (savedWb != null) setWhiteBalance(savedWb, savedKelvin, false);
        applyDro();
        live = Math.max(0, liveNames.indexOf(liveKey));
        if (live > 0) applyLive(false);
        applySkin();
        applyModeDial();
        applyEvDial();
        refreshAll();
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, 1000);
    }

    private boolean openCamera() {
        try {
            camera = CameraEx.open(0, null);
            camera.setShutterListener(this);
            camera.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
                public void onShutterSpeedChange(final CameraEx.ShutterSpeedInfo info, CameraEx c) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            shutterN = info.currentShutterSpeed_n;
                            shutterD = info.currentShutterSpeed_d;
                            refreshExposureBar();
                        }
                    });
                }
            });
            camera.setAutoISOSensitivityListener(new CameraEx.AutoISOSensitivityListener() {
                public void onChanged(final int iso, CameraEx c) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            autoIso = iso;
                            refreshExposureBar();
                        }
                    });
                }
            });
            camera.setProperExposureLevelCallback(new CameraEx.ProperExposureLevelCallback() {
                public void onControlLevelNotify(final int level, CameraEx c) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            meterLevel = level;
                            refreshExposureBar();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            log("CameraEx.open failed: " + t);
            message.setText("Camera open failed: " + t);
            message.setVisibility(View.VISIBLE);
            camera = null;
            return false;
        }
        if (surfaceReady) startPreview();
        return true;
    }

    /** Hands the camera back (on exit, and while reviewing photos, like Sony's apps). */
    private void closeCamera() {
        if (camera == null) return;
        handler.removeCallbacks(poll);
        stopMagnifier();
        clearSonyEffects();
        try {
            camera.getNormalCamera().stopPreview();
        } catch (Throwable t) {
            log("stopPreview failed: " + t);
        }
        try {
            camera.release();
        } catch (Throwable t) {
            log("release failed: " + t);
        }
        camera = null;
    }

    // ---------------- capabilities ----------------

    @SuppressWarnings("rawtypes")
    private void readCapabilities() {
        log("==== Ghosts on " + Build.MODEL + " sdk " + Build.VERSION.SDK_INT);
        try {
            Camera.Parameters sup = camera.getSupportedParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(sup);
            supportedSceneModes = sup.getSupportedSceneModes();
            if (supportedSceneModes == null) supportedSceneModes = new ArrayList<String>();
            isoValues.clear();
            List isoList = m.getSupportedISOSensitivities();
            if (isoList != null) {
                for (Object o : isoList) {
                    try {
                        isoValues.add(Integer.parseInt(o.toString()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Collections.sort(isoValues);
            magLevels.clear();
            List mag = m.getSupportedPreviewMagnification();
            if (mag != null) {
                for (Object o : mag) {
                    int v = Integer.parseInt(o.toString());
                    if (v > 0) magLevels.add(v);
                }
            }
            Collections.sort(magLevels);
            try {
                List modes = m.getSupportedDROModes();
                droModes.clear();
                if (modes != null) for (Object o : modes) droModes.add(o.toString());
                maxDro = Math.max(1, m.getMaxDROLevel());
            } catch (Throwable t) {
                log("dro capabilities failed: " + t);
            }
            readEffects(m);
            wbModes.clear();
            List<String> wb = sup.getSupportedWhiteBalance();
            for (String mode : WB_ORDER) {
                if (wb == null || wb.contains(mode)) wbModes.add(mode);
            }
            try {
                minKelvin = m.getMinColorTemperatureForWhiteBalance();
                maxKelvin = m.getMaxColorTemperatureForWhiteBalance();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            log("readCapabilities failed: " + t);
        }
        try {
            Camera.Parameters p = camera.getNormalCamera().getParameters();
            minEv = p.getMinExposureCompensation();
            maxEv = p.getMaxExposureCompensation();
            evStep = p.getExposureCompensationStep();
        } catch (Throwable t) {
            log("ev range failed: " + t);
        }
    }

    // ---------------- menu ----------------

    private void openMenu(int row) {
        menuOpen = true;
        menuRow = row;
        refreshMenu();
    }

    private void closeMenu() {
        menuOpen = false;
        refreshMenu();
    }

    private void menuMove(int dir) {
        menuRow = (menuRow + dir + MENU_ROWS.length) % MENU_ROWS.length;
        refreshMenu();
    }

    /** Left/right or the wheel: change the value on the highlighted row. */
    private void menuChange(int dir) {
        switch (menuRow) {
            case ROW_PEOPLE:
                people = (people + dir + PEOPLE_NAMES.length) % PEOPLE_NAMES.length;
                break;
            case ROW_DIFFUSION:
                diffusion = (diffusion + dir + Diffusion.NAMES.length) % Diffusion.NAMES.length;
                break;
            case ROW_MODEL:
                model = (model + dir + MODEL_SIZES.length) % MODEL_SIZES.length;
                break;
            case ROW_GLOW:
                glow = (glow + dir + GLOW_NAMES.length) % GLOW_NAMES.length;
                break;
            case ROW_SIZE:
                largeCopy = !largeCopy;
                break;
            case ROW_LOOK:
                look = (look + dir + Ghost.LOOKS.length) % Ghost.LOOKS.length;
                break;
            case ROW_OPACITY:
                opacity = (opacity + dir + OPACITIES.length) % OPACITIES.length;
                break;
            case ROW_COLOUR:
                colour = (colour + dir + Ghost.COLOURS.length) % Ghost.COLOURS.length;
                break;
            case ROW_LIVE:
                if (liveNames.size() > 1) {
                    live = (live + dir + liveNames.size()) % liveNames.size();
                    liveKey = liveNames.get(live);
                    applyLive(true);
                }
                break;
            case ROW_SKIN:
                if (skinModes.size() > 1) {
                    int i = Math.max(0, skinModes.indexOf(skin));
                    skin = skinModes.get((i + dir + skinModes.size()) % skinModes.size());
                    applySkin();
                }
                break;
            case ROW_WB:
                if (!wbModes.isEmpty()) {
                    int i = Math.max(0, wbModes.indexOf(wbMode));
                    i = (i + dir + wbModes.size()) % wbModes.size();
                    setWhiteBalance(wbModes.get(i), kelvin, true);
                }
                break;
            case ROW_KELVIN:
                if (wbModes.contains(WB_KELVIN)) setWhiteBalance(WB_KELVIN, kelvin + dir * 100, true);
                break;
            case ROW_DRO:
                if (!droModes.isEmpty()) {
                    int count = maxDro + 2;
                    dro = (dro + dir + count) % count;
                    applyDro();
                }
                break;
        }
        refreshTopBar();
        refreshMenu();
    }

    private void refreshMenu() {
        if (!menuOpen && pickerPanel.getVisibility() != View.GONE) repaintSoon();
        pickerPanel.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
        if (!menuOpen) return;
        boolean ghostsOn = people == Ghoster.PEOPLE_GHOSTS, peopleOn = people != Ghoster.PEOPLE_OFF;
        String[] values = {
            PEOPLE_NAMES[people] + (people == Ghoster.PEOPLE_WIREFRAME ? "  (Opacity dims the person)" : ""),
            Diffusion.NAMES[diffusion] + (diffusion > 0 && !peopleOn ? "  (no AI, quick)" : ""),
            MODEL_NAMES[model] + "  (" + MODEL_NOTES[model] + ")",
            Ghost.LOOKS[look] + "  (" + LOOK_NOTES[look] + ")",
            OPACITIES[opacity] + "%" + (opacity == 0 ? "  (pure white)" : ""),
            Ghost.COLOURS[colour],
            GLOW_NAMES[glow],
            largeCopy ? "Large  (3000 px, slower to save)" : "Small  (1500 px)",
            liveNames.size() <= 1 ? "n/a" : liveNames.get(live) + (live == 0 ? "" : "  (viewfinder and JPEG)"),
            skinModes.size() <= 1 ? "n/a" : skinLabel(skin),
            wbModes.isEmpty() ? "n/a" : wbLabel(wbMode, kelvin),
            kelvin + "K" + (WB_KELVIN.equals(wbMode) ? "" : "  (sets WB)"),
            droLabel(),
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MENU_ROWS.length; i++) {
            String label = MENU_ROWS[i];
            sb.append(i == menuRow ? "> " : "  ").append(label);
            for (int k = label.length(); k < 10; k++) sb.append(' ');
            sb.append(i == menuRow ? "<  " + values[i] + "  >" : "   " + values[i]).append('\n');
        }
        sb.append("\nup/down: row   left/right: change   Fn: close");
        if (menuRow >= ROW_MODEL && menuRow <= ROW_GLOW && !peopleOn) sb.append("\n(only used when People is on)");
        else if (menuRow == ROW_LOOK && !ghostsOn) sb.append("\n(only used for Ghosts)");
        else if (menuRow == ROW_LIVE || menuRow == ROW_SKIN) sb.append("\nSony's own effect: needs Quality set to JPEG (not RAW)");
        else if (menuRow <= ROW_SIZE) sb.append(effectsOn() ? "\nEach shot gets a copy in GHOSTS (needs JPEG)"
                : "\nPeople and Diffusion are off: shots are left as they are");
        picker.setText(sb.toString());
    }

    // ---------------- Sony's live effects ----------------

    @SuppressWarnings("rawtypes")
    private void readEffects(CameraEx.ParametersModifier m) {
        liveNames.clear();
        liveValues.clear();
        liveNames.add("Off");
        liveValues.add(new String[] {CameraEx.ParametersModifier.PICTURE_EFFECT_OFF, null});
        try {
            List effects = m.getSupportedPictureEffects();
            log("picture effects " + effects);
            if (effects != null && effects.contains(CameraEx.ParametersModifier.PICTURE_EFFECT_SOFT_FOCUS)) {
                int lo = 1, hi = 3;
                try {
                    lo = m.getMinPictureEffectSoftFocusEffectLevel();
                    hi = m.getMaxPictureEffectSoftFocusEffectLevel();
                } catch (Throwable t) {
                    log("soft focus levels failed: " + t);
                }
                for (int lv = lo; lv <= hi; lv++) {
                    liveNames.add("Soft focus " + lv);
                    liveValues.add(new String[] {CameraEx.ParametersModifier.PICTURE_EFFECT_SOFT_FOCUS, String.valueOf(lv)});
                }
            }
            if (effects != null && effects.contains(CameraEx.ParametersModifier.PICTURE_EFFECT_SOFT_HIGH_KEY)) {
                List tints = null;
                try {
                    tints = m.getSupportedPictureEffectSoftHighKeyEffects();
                } catch (Throwable t) {
                    log("soft high-key tints failed: " + t);
                }
                if (tints == null || tints.isEmpty()) {
                    liveNames.add("Soft high-key");
                    liveValues.add(new String[] {CameraEx.ParametersModifier.PICTURE_EFFECT_SOFT_HIGH_KEY, null});
                } else {
                    for (Object o : tints) {
                        String tint = o.toString();
                        liveNames.add("Soft high-key " + tint);
                        liveValues.add(new String[] {CameraEx.ParametersModifier.PICTURE_EFFECT_SOFT_HIGH_KEY, tint});
                    }
                }
            }
        } catch (Throwable t) {
            log("picture effects failed: " + t);
        }
        skinModes.clear();
        try {
            List modes = m.getSupportedSoftSkinEffects();
            log("soft skin " + modes);
            if (modes != null) for (Object o : modes) skinModes.add(o.toString());
        } catch (Throwable t) {
            log("soft skin failed: " + t);
        }
    }

    /** Sets the chosen live effect; with tell, says so (or why it didn't take). */
    private void applyLive(boolean tell) {
        if (camera == null || liveValues.isEmpty()) return;
        String[] v = liveValues.get(Math.min(live, liveValues.size() - 1));
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            m.setPictureEffect(v[0]);
            if (CameraEx.ParametersModifier.PICTURE_EFFECT_SOFT_FOCUS.equals(v[0])) {
                m.setPictureEffectSoftFocusEffectLevel(Integer.parseInt(v[1]));
            } else if (CameraEx.ParametersModifier.PICTURE_EFFECT_SOFT_HIGH_KEY.equals(v[0]) && v[1] != null) {
                m.setPictureEffectSoftHighKeyEffect(v[1]);
            }
            camera.getNormalCamera().setParameters(p);
            String now = camera.createParametersModifier(camera.getNormalCamera().getParameters()).getPictureEffect();
            log("live effect " + liveNames.get(live) + " -> camera reports " + now);
            if (tell && !v[0].equals(now)) showMessage("The camera refused it: set Quality to JPEG (picture effects don't work with RAW)");
        } catch (Throwable t) {
            log("live effect failed: " + t);
            if (tell) showMessage("Live effect failed: " + t.getMessage());
        }
        refreshTopBar();
    }

    private void applySkin() {
        if (camera == null || skinModes.size() <= 1 || !skinModes.contains(skin)) return;
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            camera.createParametersModifier(p).setSoftSkinEffect(skin);
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("soft skin failed: " + t);
        }
    }

    /** Back to no picture effect and no soft skin, so the camera's own modes aren't left changed. */
    private void clearSonyEffects() {
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            if (live != 0) m.setPictureEffect(CameraEx.ParametersModifier.PICTURE_EFFECT_OFF);
            if (skinModes.contains("off") && !"off".equals(skin)) m.setSoftSkinEffect("off");
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("clear effects failed: " + t);
        }
    }

    private static String skinLabel(String s) {
        if ("off".equals(s)) return "Off";
        if ("low".equals(s)) return "Low";
        if ("mid".equals(s)) return "Mid";
        if ("high".equals(s)) return "High";
        return s;
    }

    // ---------------- white balance and D-Range ----------------

    private void setWhiteBalance(String mode, int k, boolean remember) {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            p.setWhiteBalance(mode);
            if (WB_KELVIN.equals(mode)) {
                k = Math.max(minKelvin, Math.min(maxKelvin, k));
                camera.createParametersModifier(p).setColorTemperatureForWhiteBalance(k);
            }
            camera.getNormalCamera().setParameters(p);
            wbMode = mode;
            if (WB_KELVIN.equals(mode)) kelvin = k;
            if (remember) {
                savedWb = mode;
                savedKelvin = kelvin;
            }
        } catch (Throwable t) {
            log("setWhiteBalance " + mode + " failed: " + t);
            showMessage("White balance failed: " + t);
        }
        refreshExposureBar();
        refreshMenu();
    }

    private void applyDro() {
        if (camera == null || droModes.isEmpty()) return;
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            if (dro == 0) {
                m.setDROMode(CameraEx.ParametersModifier.DRO_MODE_OFF);
            } else if (dro == 1 && droModes.contains(CameraEx.ParametersModifier.DRO_MODE_AUTO)) {
                m.setDROMode(CameraEx.ParametersModifier.DRO_MODE_AUTO);
            } else {
                m.setDROMode(CameraEx.ParametersModifier.DRO_MODE_ON);
                m.setDROLevel(Math.max(1, Math.min(maxDro, dro - 1)));
            }
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("applyDro failed: " + t);
            showMessage("D-Range failed: " + t);
        }
    }

    private String droLabel() {
        if (droModes.isEmpty()) return "n/a";
        return dro == 0 ? "Off" : dro == 1 ? "Auto" : "Level " + (dro - 1);
    }

    private static String wbLabel(String mode, int k) {
        if ("auto".equals(mode)) return "AWB";
        if ("daylight".equals(mode)) return "Daylight";
        if ("shade".equals(mode)) return "Shade";
        if ("cloudy-daylight".equals(mode)) return "Cloudy";
        if ("incandescent".equals(mode)) return "Tungsten";
        if ("warm-fluorescent".equals(mode)) return "Fluor. warm";
        if ("fluorescent-coolwhite".equals(mode)) return "Fluor. cool";
        if ("fluorescent-daywhite".equals(mode)) return "Fluor. day white";
        if ("fluorescent-daylight".equals(mode)) return "Fluor. daylight";
        if ("flash".equals(mode)) return "Flash";
        if (WB_KELVIN.equals(mode)) return k + "K";
        return mode;
    }

    // ---------------- exposure ----------------

    private void applyModeDial() {
        try {
            KeyStatus ks = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_MODE_DIAL);
            if (ks == null || ks.valid != 1) return;
            String mode = null;
            switch (ks.status) {
                case DIAL_P: mode = "program-auto"; break;
                case DIAL_S: mode = "shutter-speed"; break;
                case DIAL_A: mode = "aperture-priority"; break;
                case DIAL_M: mode = "manual-exposure"; break;
                case DIAL_AUTO:
                case DIAL_SUPERIOR_AUTO: mode = supportedSceneModes.contains("auto") ? "auto" : "program-auto"; break;
            }
            if (mode == null) {
                showMessage("Set the mode dial to P, A, S or M");
                return;
            }
            if (!supportedSceneModes.isEmpty() && !supportedSceneModes.contains(mode)) {
                log("scene mode " + mode + " not supported");
                return;
            }
            Camera.Parameters p = camera.createEmptyParameters();
            p.setSceneMode(mode);
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("applyModeDial failed: " + t);
        }
        readExposure();
    }

    private void applyEvDial() {
        try {
            KeyStatus ks = ScalarInput.getKeyStatus(EV_DIAL);
            if (ks == null || ks.valid != 1) return;
            int pos = ks.status;
            if ((pos & 512) == 512) pos = -(pos - 512);
            setEvIndex(pos);
        } catch (Throwable t) {
            log("applyEvDial failed: " + t);
        }
    }

    private void setEvIndex(int index) {
        index = Math.max(minEv, Math.min(maxEv, index));
        try {
            Camera.Parameters p = camera.createEmptyParameters();
            p.setExposureCompensation(index);
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("setExposureCompensation failed: " + t);
        }
        readExposure();
    }

    private void stepEv(int dir) {
        try {
            setEvIndex(camera.getNormalCamera().getParameters().getExposureCompensation() + dir);
        } catch (Throwable t) {
            log("stepEv failed: " + t);
        }
    }

    private void stepShutter(int dir) {
        try {
            if (dir > 0) camera.incrementShutterSpeed();
            else camera.decrementShutterSpeed();
        } catch (Throwable t) {
            log("shutter step failed: " + t);
        }
        handler.postDelayed(new Runnable() {
            public void run() {
                readExposure();
            }
        }, 100);
    }

    private void stepIso(int dir) {
        if (isoValues.isEmpty()) return;
        try {
            int cur = camera.createParametersModifier(camera.getNormalCamera().getParameters()).getISOSensitivity();
            int i = isoValues.indexOf(cur);
            if (i < 0) {
                i = 0;
                while (i < isoValues.size() - 1 && isoValues.get(i) < cur) i++;
            }
            i = Math.max(0, Math.min(isoValues.size() - 1, i + dir));
            Camera.Parameters p = camera.createEmptyParameters();
            camera.createParametersModifier(p).setISOSensitivity(isoValues.get(i));
            camera.getNormalCamera().setParameters(p);
        } catch (Throwable t) {
            log("stepIso failed: " + t);
        }
        readExposure();
    }

    private boolean shutterControlsShutter() {
        return "shutter-speed".equals(sceneMode) || "manual-exposure".equals(sceneMode);
    }

    private void readExposure() {
        if (camera == null || takingPicture) return;
        try {
            Camera.Parameters p = camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier m = camera.createParametersModifier(p);
            sceneMode = p.getSceneMode() == null ? "" : p.getSceneMode();
            iso = m.getISOSensitivity();
            evIndex = p.getExposureCompensation();
            if (p.getWhiteBalance() != null) wbMode = p.getWhiteBalance();
            try {
                int k = m.getColorTemperatureForWhiteBalance();
                if (k > 0) kelvin = k;
            } catch (Throwable ignored) {
            }
            Pair<Integer, Integer> ss = m.getShutterSpeed();
            if (ss != null) {
                shutterN = ss.first;
                shutterD = ss.second;
            }
            if ("manual-exposure".equals(sceneMode)) camera.getProperExposureLevel();
        } catch (Throwable t) {
            log("readExposure failed: " + t);
        }
        refreshExposureBar();
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            readExposure();
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------- focus magnifier ----------------

    private void cycleMagnifier() {
        if (magLevels.isEmpty()) {
            showMessage("Focus magnifier not available");
            return;
        }
        magIndex++;
        if (magIndex >= magLevels.size()) {
            stopMagnifier();
        } else {
            moveMagnifier(0, 0);
        }
        refreshExposureBar();
    }

    private void moveMagnifier(int dx, int dy) {
        magX = Math.max(-MAG_LIMIT, Math.min(MAG_LIMIT, magX + dx));
        magY = Math.max(-MAG_LIMIT, Math.min(MAG_LIMIT, magY + dy));
        try {
            camera.setPreviewMagnification(magLevels.get(magIndex), new Pair<Integer, Integer>(magX, magY));
        } catch (Throwable t) {
            log("magnifier failed: " + t);
            showMessage("Magnifier failed: " + t);
            magIndex = -1;
        }
    }

    private void stopMagnifier() {
        if (magIndex < 0) return;
        magIndex = -1;
        magX = magY = 0;
        try {
            camera.stopPreviewMagnification();
        } catch (Throwable t) {
            log("stop magnifier failed: " + t);
        }
        refreshExposureBar();
    }

    // ---------------- capture ----------------

    private void shoot() {
        if (camera == null || takingPicture) return;
        stopMagnifier();
        takingPicture = true;
        try {
            camera.burstableTakePicture();
        } catch (Throwable t) {
            takingPicture = false;
            log("burstableTakePicture failed: " + t);
        }
    }

    /** The exposure is finished: end the burst after one frame. */
    public void onShutter(int status, CameraEx cameraEx) {
        try {
            cameraEx.cancelTakePicture();
        } catch (Throwable t) {
            log("cancelTakePicture failed: " + t);
        }
        runOnUiThread(new Runnable() {
            public void run() {
                takingPicture = false;
                handler.postDelayed(new Runnable() {
                    public void run() {
                        queueNewShot();
                    }
                }, 3000);
            }
        });
    }

    /** Queues the photo just taken, once the camera has written it. */
    private void queueNewShot() {
        if (takingPicture) {
            handler.postDelayed(new Runnable() {
                public void run() {
                    queueNewShot();
                }
            }, 1000);
            return;
        }
        File jpeg = newestJpeg(new File(Environment.getExternalStorageDirectory(), "DCIM"));
        boolean fresh = jpeg != null && !jpeg.equals(knownJpeg);
        knownJpeg = jpeg;
        if (!effectsOn()) return;
        if (fresh) {
            ghoster.add(jpeg, options());
        } else {
            showMessage("Ghosts needs JPEG: set Quality to RAW & JPEG or JPEG");
        }
    }

    private boolean effectsOn() {
        return people != Ghoster.PEOPLE_OFF || diffusion > 0;
    }

    private Ghoster.Options options() {
        Ghoster.Options opt = new Ghoster.Options();
        opt.modelSize = MODEL_SIZES[model];
        opt.glow = glow;
        opt.sample = largeCopy ? 2 : 4;
        opt.people = people;
        opt.diffusion = diffusion;
        opt.look = look;
        opt.colour = colour;
        opt.opacity = OPACITIES[opacity] / 100f;
        return opt;
    }

    private static File newestJpeg(File dcim) {
        File best = null;
        File[] dirs = dcim.listFiles();
        if (dirs == null) return null;
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.getName().toUpperCase().endsWith(".JPG") && (best == null || f.lastModified() > best.lastModified())) best = f;
            }
        }
        return best;
    }

    /** The JPEG with this file name in any DCIM folder. */
    private static File findPhoto(String name) {
        File[] dirs = new File(Environment.getExternalStorageDirectory(), "DCIM").listFiles();
        if (dirs == null || name == null) return null;
        for (File d : dirs) {
            File f = new File(d, name);
            if (f.isFile()) return f;
        }
        return null;
    }

    // ---------------- playback ----------------

    private void openPlayback() {
        if (takingPicture) {
            showMessage("Wait for the photo to finish saving");
            return;
        }
        playFiles.clear();
        Cursor c = null;
        try {
            c = getContentResolver().query(AvindexStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] {AvindexStore.Images.Media.DATA}, null, null,
                    AvindexStore.Images.Media.CONTENT_CREATED_UTC_DATE_TIME + " DESC");
            while (c != null && c.moveToNext()) playFiles.add(c.getString(0));
        } catch (Throwable t) {
            log("playback query failed: " + t);
        } finally {
            if (c != null) c.close();
        }
        if (playFiles.isEmpty()) {
            showMessage("No photos on the card");
            return;
        }
        closeMenu();
        closeCamera();
        playback = true;
        playIndex = 0;
        surfaceView.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        exposureBar.setVisibility(View.GONE);
        setOverlayColour(true);
        playView = new OptimizedImageView(this);
        // just above the live view surface, below the info line
        root.addView(playView, 1, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        devView = new ImageView(this);
        devView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        devView.setBackgroundColor(0xFF000000);
        devView.setVisibility(View.GONE);
        root.addView(devView, 2, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playInfo.setVisibility(View.VISIBLE);
        showPhoto();
    }

    /** Leaves playback; with reopen, takes the camera back. */
    private void closePlayback(boolean reopen) {
        if (!playback) return;
        playback = false;
        try {
            if (playView != null) playView.setOptimizedImage(null);
        } catch (Throwable t) {
            log("clear image failed: " + t);
        }
        releasePlayImage();
        repaintSoon();
        if (devView != null) {
            devView.setImageBitmap(null);
            root.removeView(devView);
            devView = null;
        }
        releaseDevBitmap();
        releaseDevImage();
        setOverlayColour(false);
        if (playView != null) {
            root.removeView(playView);
            try {
                playView.release();
            } catch (Throwable t) {
                log("release image view failed: " + t);
            }
            playView = null;
        }
        playInfo.setVisibility(View.GONE);
        surfaceView.setVisibility(View.VISIBLE);
        if (reopen && openCamera()) startShooting();
    }

    private void releaseDevImage() {
        if (devImage != null) {
            try {
                devImage.release();
            } catch (Throwable ignored) {
            }
            devImage = null;
        }
    }

    /** Full colour (8 bits per channel) or the camera's usual 4-bit overlay. */
    private void setOverlayColour(boolean full) {
        try {
            Gpelibrary.changeFrameBufferPixel(full ? Gpelibrary.GS_FRAMEBUFFER_TYPE.ABGR8888 : Gpelibrary.GS_FRAMEBUFFER_TYPE.RGBA4444);
        } catch (Throwable t) {
            log("overlay colour change failed: " + t);
        }
    }

    private void releaseDevBitmap() {
        if (devBitmap != null) {
            devBitmap.recycle();
            devBitmap = null;
        }
    }

    private void releasePlayImage() {
        if (playImage != null) {
            try {
                playImage.release();
            } catch (Throwable ignored) {
            }
            playImage = null;
        }
    }

    private boolean onPlaybackKey(int scan) {
        switch (scan) {
            case ScalarInput.ISV_KEY_RIGHT:
            case ScalarInput.ISV_KEY_DOWN:
            case ScalarInput.ISV_DIAL_KURU_CLOCKWISE:
            case ScalarInput.ISV_DIAL_3_CLOCKWISE:
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
                stepPhoto(1);
                return true;
            case ScalarInput.ISV_KEY_LEFT:
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_DIAL_KURU_COUNTERCW:
            case ScalarInput.ISV_DIAL_3_COUNTERCW:
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
            case ScalarInput.ISV_DIAL_2_COUNTERCW:
                stepPhoto(-1);
                return true;
            case ScalarInput.ISV_KEY_PLAY:
            case ScalarInput.ISV_KEY_S1_1:
            case ScalarInput.ISV_KEY_S2:
                closePlayback(true);
                return true;
            case ScalarInput.ISV_KEY_CUSTOM1:
                showGhost = !showGhost;
                showPhoto();
                return true;
            case ScalarInput.ISV_KEY_CUSTOM2:
            case ScalarInput.ISV_KEY_ENTER:
                ghostThisPhoto();
                return true;
        }
        return true;
    }

    /** Queues the photo on screen (one taken earlier, maybe in another app). */
    private void ghostThisPhoto() {
        File f = findPhoto(playName);
        if (f == null || !f.getName().toUpperCase().endsWith(".JPG")) {
            showMessage(playName != null && playName.toUpperCase().endsWith(".ARW")
                    ? "This is a RAW file: Ghosts needs the JPEG" : "Can't find this photo's JPEG");
            return;
        }
        if (!effectsOn()) {
            showMessage("Pick People or Diffusion in the Fn menu first");
            return;
        }
        ghoster.add(f, options());
        showMessage((people == Ghoster.PEOPLE_OFF ? "Diffusing " : people == Ghoster.PEOPLE_WIREFRAME ? "Wireframing " : "Ghosting ")
                + f.getName() + "...");
    }

    /** dir 1 = older photo, -1 = newer. */
    private void stepPhoto(int dir) {
        int i = playIndex + dir;
        if (i < 0 || i >= playFiles.size()) return;
        playIndex = i;
        showPhoto();
    }

    private void showPhoto() {
        if (!playback) return;
        String data = playFiles.get(playIndex);
        OptimizedImage img = null;
        String info = "";
        int orientation = 1;
        String name = null;
        try {
            OptimizedImageFactory.Options o = new OptimizedImageFactory.Options();
            o.imageType = 3;
            o.bBasicInfo = true;
            o.bCamInfo = true;
            img = OptimizedImageFactory.decodeImage(data, o);
            AvindexContentInfo ci = o.outContentInfo;
            if (ci != null) {
                double t = ci.getAttributeDouble(AvindexContentInfo.TAG_EXPOSURE_TIME, 0);
                int isoValue = ci.getAttributeInt(AvindexContentInfo.TAG_ISO, 0);
                if (t > 0) info += (t < 1 ? "1/" + Math.round(1 / t) : String.format("%.1f\"", t)) + "   ";
                if (isoValue > 0) info += "ISO " + isoValue + "   ";
                orientation = ci.getAttributeInt(AvindexContentInfo.TAG_ORIENTATION, 1);
                // playback hands out avindex:// ids; the file name comes from the photo's info
                name = ci.getAttribute(AvindexContentInfo.TAG_DCF_TBL_FILE_NAME);
            }
            playView.setDisplayType(OptimizedImageView.DisplayType.DISPLAY_TYPE_CENTER_INNER);
            playView.setOptimizedImage(img);
        } catch (Throwable t) {
            log("showPhoto failed: " + t);
        }
        releasePlayImage();
        playImage = img;
        if (name == null || name.length() == 0) {
            try {
                AvindexContentInfo full = AvindexStore.Images.Media.getImageInfo(data);
                if (full != null) name = full.getAttribute(AvindexContentInfo.TAG_DCF_TBL_FILE_NAME);
            } catch (Throwable t) {
                log("file name lookup failed: " + t);
            }
        }
        if (name == null || name.length() == 0) name = new File(data).getName();
        playName = name;
        File dev = new File(ghostDir(), Ghoster.outName(name) + ".JPG");
        boolean hasDev = dev.isFile();
        if (!hasDev || !showGhost) {
            devView.setImageBitmap(null);
            releaseDevBitmap();
            devView.setVisibility(View.GONE);
        }
        boolean devShown = false;
        releaseDevImage();
        if (hasDev && showGhost) {
            // Sony's viewer shows full colour; try it first
            try {
                OptimizedImageFactory.Options o = new OptimizedImageFactory.Options();
                o.imageType = 3;
                devImage = OptimizedImageFactory.decodeImage(dev.getAbsolutePath(), o);
            } catch (Throwable t) {
                log("viewer decode of copy failed: " + t);
                devImage = null;
            }
            if (devImage != null) {
                devView.setImageBitmap(null);
                releaseDevBitmap();
                devView.setVisibility(View.GONE);
                playView.setVisibility(View.VISIBLE);
                try {
                    playView.setOptimizedImage(devImage);
                    devShown = true;
                } catch (Throwable t) {
                    log("viewer show of copy failed: " + t);
                    releaseDevImage();
                }
            }
            if (!devShown && showDevCopy(dev, orientation)) {
                // Sony's viewer draws on its own layer above the app's views, so hide it under the overlay copy
                playView.setVisibility(View.INVISIBLE);
                devShown = true;
            }
        }
        if (!devShown) {
            playView.setVisibility(View.VISIBLE);
            if (img != null) {
                try {
                    playView.setOptimizedImage(img);
                } catch (Throwable t) {
                    log("re-show original failed: " + t);
                }
            }
        }
        playLabel = !hasDev ? "C2: ghost this   " : devShown ? "GHOSTS (C1: original)   " : "ORIGINAL (C1: ghosts)   ";
        playTail = name + "   " + info + (playIndex + 1) + "/" + playFiles.size() + "      < > browse   Play: back"
                + (img == null && !devShown ? "\n(could not show this file)" : "");
        updatePlayInfo();
    }

    private String playLabel = "", playTail = "";

    /** The info line in playback, with the ghosting progress when a photo is being worked on. */
    private void updatePlayInfo() {
        String label = queued > 0 ? (stage.length() > 0 ? stage : "Ghosting") + " (" + queued + ")...   " : playLabel;
        playInfo.setText(label + playTail);
    }

    /** Decodes the ghost copy at screen size and shows it over the original. */
    private boolean showDevCopy(File dev, int orientation) {
        devView.setImageBitmap(null);
        releaseDevBitmap();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(dev.getPath(), o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 800) o.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(dev.getPath(), o);
            if (b == null) {
                devView.setVisibility(View.GONE);
                return false;
            }
            int deg = orientation == 6 ? 90 : orientation == 3 ? 180 : orientation == 8 ? 270 : 0;
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            devBitmap = b;
            devView.setImageBitmap(b);
            devView.setVisibility(View.VISIBLE);
            return true;
        } catch (Throwable t) {
            log("showDevCopy failed: " + t);
            devView.setVisibility(View.GONE);
            return false;
        }
    }

    // ---------------- preview ----------------

    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (camera != null) startPreview();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    private void startPreview() {
        try {
            camera.getNormalCamera().setPreviewDisplay(surfaceHolder);
            camera.getNormalCamera().startPreview();
        } catch (Throwable t) {
            log("preview failed: " + t);
        }
    }

    // ---------------- keys ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        if (playback) return onPlaybackKey(scan);
        if (camera == null) return super.onKeyDown(keyCode, event);
        if (scan == ScalarInput.ISV_KEY_MODE_DIAL || (scan >= 534 && scan <= 551)) {
            applyModeDial();
            return true;
        }
        switch (scan) {
            case ScalarInput.ISV_KEY_S1_1:
                closeMenu();
                stopMagnifier();
                try {
                    camera.getNormalCamera().autoFocus(null);
                } catch (Throwable t) {
                    log("autoFocus failed: " + t);
                }
                return true;
            case ScalarInput.ISV_KEY_S1_2:
                return true;
            case ScalarInput.ISV_KEY_S2:
                closeMenu();
                shoot();
                return true;
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_COUNTERCW: {
                int dir = scan == ScalarInput.ISV_DIAL_2_CLOCKWISE ? 1 : -1;
                if (shutterControlsShutter()) stepShutter(dir);
                else stepEv(dir);
                return true;
            }
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
                stepIso(scan == ScalarInput.ISV_DIAL_1_CLOCKWISE ? 1 : -1);
                return true;
            case ScalarInput.ISV_DIAL_KURU_CLOCKWISE:
            case ScalarInput.ISV_DIAL_KURU_COUNTERCW:
            case ScalarInput.ISV_DIAL_3_CLOCKWISE:
            case ScalarInput.ISV_DIAL_3_COUNTERCW: {
                int dir = (scan == ScalarInput.ISV_DIAL_KURU_CLOCKWISE || scan == ScalarInput.ISV_DIAL_3_CLOCKWISE) ? 1 : -1;
                if (menuOpen) menuChange(dir);
                return true;
            }
            case EV_DIAL:
                applyEvDial();
                return true;
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_KEY_DOWN: {
                int dir = scan == ScalarInput.ISV_KEY_DOWN ? 1 : -1;
                if (menuOpen) menuMove(dir);
                else if (magIndex >= 0) moveMagnifier(0, dir * MAG_STEP);
                else if (scan == ScalarInput.ISV_KEY_UP) toggleOverlay();
                return true;
            }
            case ScalarInput.ISV_KEY_LEFT:
            case ScalarInput.ISV_KEY_RIGHT: {
                int dir = scan == ScalarInput.ISV_KEY_RIGHT ? 1 : -1;
                if (menuOpen) menuChange(dir);
                else if (magIndex >= 0) moveMagnifier(dir * MAG_STEP, 0);
                return true;
            }
            case ScalarInput.ISV_KEY_WB:
            case ScalarInput.ISV_KEY_CUSTOM:
                if (menuOpen) closeMenu();
                else openMenu(ROW_WB);
                return true;
            case ScalarInput.ISV_KEY_ENTER:
                if (menuOpen) closeMenu();
                else cycleMagnifier();
                return true;
            case ScalarInput.ISV_KEY_FN:
                if (menuOpen) closeMenu();
                else openMenu(menuRow);
                return true;
            case ScalarInput.ISV_KEY_DISP:
                toggleOverlay();
                return true;
            case ScalarInput.ISV_KEY_PLAY:
                openPlayback();
                return true;
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
                return true;
        }
        showKey(scan);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        switch (scan) {
            case ScalarInput.ISV_KEY_S1_1:
                if (camera != null) {
                    try {
                        camera.getNormalCamera().cancelAutoFocus();
                    } catch (Throwable t) {
                        log("cancelAutoFocus failed: " + t);
                    }
                }
                return true;
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
                if (playback) closePlayback(true);
                else if (menuOpen) closeMenu();
                else exitApp();
                return true;
        }
        return true;
    }

    private void exitApp() {
        if (takingPicture) {
            showMessage("Wait for the photo to finish saving");
            return;
        }
        if (ghoster.pending() > 0 && System.currentTimeMillis() > exitConfirmUntil) {
            exitConfirmUntil = System.currentTimeMillis() + 3000;
            showMessage("Still ghosting a photo. Press MENU again to stop it and exit");
            return;
        }
        ghoster.cancel();
        try {
            new DAConnectionManager(this).finish();
        } catch (Throwable t) {
            log("DAConnectionManager.finish failed: " + t);
        }
        finish();
    }

    private void notifyAppInfo() {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getComponentName().getPackageName());
        intent.putExtra("class_name", getComponentName().getClassName());
        sendBroadcast(intent);
    }

    // ---------------- display ----------------

    private void toggleOverlay() {
        overlay = !overlay;
        refreshAll();
        repaintSoon();
    }

    private void refreshAll() {
        refreshTopBar();
        refreshExposureBar();
        refreshMenu();
    }

    private void refreshTopBar() {
        if (playback) {
            updatePlayInfo();
            return;
        }
        topBar.setVisibility(overlay || queued > 0 ? View.VISIBLE : View.GONE);
        String status = queued > 0 ? (stage.length() > 0 ? stage : "Waiting") + (queued > 1 ? " (" + queued + " queued)" : "") + "...    " : "";
        String liveLabel = live > 0 && live < liveNames.size() ? "   [" + liveNames.get(live) + "]" : "";
        String what = people == Ghoster.PEOPLE_GHOSTS ? Ghost.LOOKS[look] + " " + OPACITIES[opacity] + "%   " + MODEL_NAMES[model]
                : people == Ghoster.PEOPLE_WIREFRAME ? "Wireframe " + Ghost.COLOURS[colour].toLowerCase() + "   " + MODEL_NAMES[model]
                : "";
        if (diffusion > 0) what += (what.length() > 0 ? "   + " : "") + Diffusion.NAMES[diffusion];
        if (what.length() == 0) what = "off";
        topBar.setText(status + "GHOSTS   " + what + liveLabel
                + (queued > 0 ? "" : "   (Fn: menu)"));
    }

    private void refreshExposureBar() {
        exposureBar.setVisibility(overlay ? View.VISIBLE : View.GONE);
        StringBuilder sb = new StringBuilder();
        sb.append(modeLetter()).append("    ").append(formatShutter()).append("    ");
        if (iso == 0) {
            sb.append("ISO AUTO");
            if (autoIso > 0) sb.append(" (").append(autoIso).append(')');
        } else {
            sb.append("ISO ").append(iso);
        }
        sb.append("    ").append(wbLabel(wbMode, kelvin));
        float ev = evIndex * evStep;
        sb.append("    ").append(ev >= 0 ? "+" : "").append(String.format("%.1f", ev)).append(" EV");
        if ("manual-exposure".equals(sceneMode) && meterLevel != Integer.MIN_VALUE) {
            sb.append("    meter ").append(meterLevel);
        }
        if (magIndex >= 0) {
            sb.append("    zoom x").append(String.format("%.1f", magLevels.get(magIndex) / 100f));
        }
        exposureBar.setText(sb.toString());
    }

    private String modeLetter() {
        if ("manual-exposure".equals(sceneMode)) return "M";
        if ("shutter-speed".equals(sceneMode)) return "S";
        if ("aperture-priority".equals(sceneMode)) return "A";
        if ("program-auto".equals(sceneMode)) return "P";
        if ("auto".equals(sceneMode)) return "AUTO";
        return sceneMode;
    }

    private String formatShutter() {
        if (shutterD <= 0) return "--";
        if (shutterN == 1 && shutterD > 1) return "1/" + shutterD;
        float s = shutterN / (float) shutterD;
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.format("%.1f", s)) + "\"";
    }

    private final Runnable hideMessage = new Runnable() {
        public void run() {
            message.setVisibility(View.GONE);
            repaintSoon();
        }
    };

    /** Full repaints over the next few frames, so every screen buffer drops whatever was just hidden. */
    private void repaintSoon() {
        root.invalidate();
        for (int delay : new int[] {40, 100, 200, 350}) {
            handler.postDelayed(repaint, delay);
        }
    }

    private final Runnable repaint = new Runnable() {
        public void run() {
            root.invalidate();
        }
    };

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, 2500);
    }

    private final Runnable hideKey = new Runnable() {
        public void run() {
            keyInfo.setVisibility(View.GONE);
        }
    };

    /** Shows the scan code of a button the app doesn't use yet, so it can be mapped later. */
    private void showKey(int scan) {
        keyInfo.setText("button " + scan);
        keyInfo.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideKey);
        handler.postDelayed(hideKey, 3000);
    }

    // ---------------- settings ----------------

    private void restorePrefs() {
        SharedPreferences prefs = getSharedPreferences("ghosts", MODE_PRIVATE);
        overlay = prefs.getBoolean("overlay", true);
        model = clamp(prefs.getInt("model", 1), MODEL_SIZES.length);
        glow = clamp(prefs.getInt("glow", 2), GLOW_NAMES.length);
        largeCopy = prefs.getBoolean("largecopy", false);
        people = clamp(prefs.getInt("people", Ghoster.PEOPLE_OFF), PEOPLE_NAMES.length);
        colour = clamp(prefs.getInt("colour", 0), Ghost.COLOURS.length);
        liveKey = prefs.getString("live", "Off");
        skin = prefs.getString("skin", "off");
        diffusion = clamp(prefs.getInt("diffusion2", 0), Diffusion.NAMES.length);
        look = clamp(prefs.getInt("look", Ghost.ETHEREAL), Ghost.LOOKS.length);
        opacity = clamp(prefs.getInt("opacity", 1), OPACITIES.length);
        dro = Math.max(0, prefs.getInt("dro", 0));
        savedWb = prefs.getString("wb", null);
        savedKelvin = prefs.getInt("kelvin", 5500);
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    private void savePrefs() {
        getSharedPreferences("ghosts", MODE_PRIVATE).edit()
                .putBoolean("overlay", overlay)
                .putInt("model", model)
                .putInt("glow", glow)
                .putBoolean("largecopy", largeCopy)
                .putInt("people", people)
                .putInt("colour", colour)
                .putString("live", liveKey)
                .putString("skin", skin)
                .putInt("diffusion2", diffusion)
                .putInt("look", look)
                .putInt("opacity", opacity)
                .putInt("dro", dro)
                .putString("wb", savedWb)
                .putInt("kelvin", savedKelvin)
                .commit();
    }

    // ---------------- log ----------------

    private void openLog() {
        try {
            File dir = ghostDir();
            dir.mkdirs();
            log = new FileWriter(new File(dir, "LOG.TXT"), true);
        } catch (IOException e) {
            log = null;
        }
    }

    private void closeLog() {
        if (log == null) return;
        try {
            log.close();
        } catch (IOException ignored) {
        }
        log = null;
    }

    /** Writes to GHOSTS/LOG.TXT, but never while a photo is being saved. */
    private void log(String msg) {
        if (log == null || takingPicture) return;
        try {
            log.write(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + msg + "\n");
            log.flush();
        } catch (IOException ignored) {
        }
    }
}

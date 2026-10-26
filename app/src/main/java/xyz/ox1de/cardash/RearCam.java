package xyz.ox1de.cardash;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The head unit's own camera input (the reversing camera wired into it), through Android's
 * Camera2 API. Most Chinese units expose the AV-in / reverse camera as a normal camera; if this one
 * doesn't, or its own reverse screen is holding the camera, we say so and the app fallback is used.
 */
final class RearCam {

    interface Cb {                                // UI thread
        void rearStatus(String s);
        void rearPlaying();
        void rearSize(int w, int h);
    }

    final Context ctx;
    final Handler ui;
    final Cb cb;
    final CameraManager mgr;
    HandlerThread th;
    Handler h;
    CameraDevice dev;
    CameraCaptureSession ses;
    volatile boolean closed;

    RearCam(Context c, Handler ui, Cb cb) {
        ctx = c.getApplicationContext();
        this.ui = ui;
        this.cb = cb;
        mgr = ctx.getSystemService(CameraManager.class);
    }

    /** Human-readable list of cameras the head unit exposes: {id, description}. */
    static List<String[]> list(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            CameraManager m = c.getSystemService(CameraManager.class);
            for (String id : m.getCameraIdList()) {
                CameraCharacteristics ch = m.getCameraCharacteristics(id);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                String facing = f == null ? "" : f == CameraCharacteristics.LENS_FACING_BACK ? "back"
                        : f == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "external";
                Size s = pick(ch);
                out.add(new String[]{id, "Camera " + id + "  (" + facing + (s == null ? "" : ", " + s.getWidth() + "×" + s.getHeight()) + ")"});
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Largest preview size up to 1280x720 (AV-in cameras are usually 720x480 or 1280x720). */
    static Size pick(CameraCharacteristics ch) {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] sizes = map.getOutputSizes(SurfaceHolder.class);
        if (sizes == null || sizes.length == 0) return null;
        List<Size> l = new ArrayList<>();
        Collections.addAll(l, sizes);
        Collections.sort(l, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        for (Size s : l) if (s.getWidth() <= 1280 && s.getHeight() <= 720) return s;
        return l.get(l.size() - 1);
    }

    /** Size the SurfaceView should be set to (holder.setFixedSize) before {@link #start}. */
    Size sizeFor(String id) {
        try { return pick(mgr.getCameraCharacteristics(id)); } catch (Exception e) { return null; }
    }

    @SuppressLint("MissingPermission") // caller checks CAMERA permission
    void start(String id, final Surface surface) {
        closed = false;
        th = new HandlerThread("rearcam");
        th.start();
        h = new Handler(th.getLooper());
        status("Opening the head unit camera…");
        try {
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (closed) { d.close(); return; }
                    dev = d;
                    session(surface);
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (!closed) status("The head unit took the camera back (reverse gear?).\nTap REAR to try again.");
                }
                @Override public void onError(CameraDevice d, int err) {
                    d.close();
                    if (closed) return;
                    status(err == ERROR_CAMERA_IN_USE || err == ERROR_MAX_CAMERAS_IN_USE
                            ? "The head unit's own camera screen is using it right now."
                            : "The head unit camera reported an error (" + err + ").\nTry another camera in Settings → Dash cam.");
                }
            }, h);
        } catch (Exception e) {
            status("Can't open the head unit camera: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    void session(final Surface surface) {
        try {
            List<Surface> outs = new ArrayList<>();
            outs.add(surface);
            dev.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (closed) { s.close(); return; }
                    ses = s;
                    try {
                        CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(surface);
                        s.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                            boolean first = true;
                            @Override public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest r, android.hardware.camera2.TotalCaptureResult res) {
                                if (first) { first = false; ui.post(() -> { if (!closed) cb.rearPlaying(); }); }
                            }
                        }, h);
                    } catch (Exception e) {
                        status("Camera preview failed: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    status("This camera can't show a preview at this size.\nTry another camera in Settings → Dash cam.");
                }
            }, h);
        } catch (Exception e) {
            status("Camera session failed: " + e.getMessage());
        }
    }

    void status(final String s) { ui.post(() -> { if (!closed) cb.rearStatus(s); }); }

    void stop() {
        closed = true;
        try { if (ses != null) ses.close(); } catch (Exception ignored) { }
        try { if (dev != null) dev.close(); } catch (Exception ignored) { }
        ses = null;
        dev = null;
        if (th != null) th.quitSafely();
        th = null;
    }
}

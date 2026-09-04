package za.web.visser.ancientcam;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * ancient-cam: a single-purpose, full-screen RTSP viewer for an ancient Android 5.0 tablet.
 *
 * - Plays ONE hardcoded camera (from res/values/secrets.xml -> R.string.rtsp_url) full-screen.
 * - Correct aspect ratio, no crop/zoom (VLCVideoLayout letterboxes automatically).
 * - Auto-reconnects: a progress watchdog restarts a FROZEN stream (which emits no error at all),
 *   and libVLC error/end events trigger an immediate retry.
 * - Keeps the screen on and stays immersive full-screen forever.
 */
public class PlayerActivity extends Activity {

    private static final String TAG = "ancient-cam";

    // No decoded frame for this long => the stream is frozen => hard-restart.
    private static final long STALL_MS = 8000;
    private static final long WATCH_EVERY_MS = 2000;
    // Retry delay after a hard error/end, so we do not hammer a camera that is down.
    private static final long RETRY_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LibVLC libVlc;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private PowerManager.WakeLock wakeLock;

    private volatile long lastProgressMs;
    private boolean reconnectPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        setContentView(R.layout.activity_player);
        videoLayout = findViewById(R.id.video_layout);
        applyImmersive();

        // Forces the panel bright even before the window draws at a cold boot.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        //noinspection deprecation
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ancientcam:screen");
        wakeLock.acquire();
    }

    @Override
    protected void onStart() {
        super.onStart();
        openStream();
        handler.postDelayed(watchdog, WATCH_EVERY_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        reconnectPending = false;
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ---- playback ----

    private void openStream() {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--rtsp-tcp");             // camera speaks RTSP interleaved over TCP
        opts.add("--network-caching=300");  // ms buffer; low for a live wall display
        opts.add("--drop-late-frames");
        opts.add("--skip-frames");
        opts.add("--no-audio");             // it is a camera view
        libVlc = new LibVLC(getApplicationContext(), opts);

        player = new MediaPlayer(libVlc);
        player.attachViews(videoLayout, null, false, false);  // auto aspect, no crop
        player.setEventListener(eventListener);

        lastProgressMs = SystemClock.elapsedRealtime();

        Media media = new Media(libVlc, Uri.parse(getString(R.string.rtsp_url)));
        media.setHWDecoderEnabled(true, false);      // MediaCodec HW decode on the MT8127
        media.addOption(":rtsp-tcp");
        media.addOption(":network-caching=300");
        player.setMedia(media);
        media.release();
        player.play();
    }

    private final MediaPlayer.EventListener eventListener = event -> {
        switch (event.type) {
            case MediaPlayer.Event.TimeChanged:
            case MediaPlayer.Event.PositionChanged:
            case MediaPlayer.Event.Vout:
            case MediaPlayer.Event.Playing:
                // Real progress -> the stream is alive.
                lastProgressMs = SystemClock.elapsedRealtime();
                break;
            case MediaPlayer.Event.EncounteredError:
            case MediaPlayer.Event.EndReached:
                Log.w(TAG, "stream error/end -> reconnect in " + RETRY_MS + "ms");
                scheduleReconnect(RETRY_MS);
                break;
            default:
                break;
        }
    };

    /** Fires every WATCH_EVERY_MS; a frozen stream produces no events, so this is what catches it. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            long stalledFor = SystemClock.elapsedRealtime() - lastProgressMs;
            if (stalledFor > STALL_MS) {
                Log.w(TAG, "no frames for " + stalledFor + "ms -> restart");
                scheduleReconnect(0);
            } else {
                handler.postDelayed(this, WATCH_EVERY_MS);
            }
        }
    };

    private void scheduleReconnect(long delayMs) {
        if (reconnectPending) return;
        reconnectPending = true;
        handler.removeCallbacks(watchdog);
        handler.postDelayed(() -> {
            reconnectPending = false;
            restart();
        }, delayMs);
    }

    private void restart() {
        releasePlayer();
        openStream();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCH_EVERY_MS);
    }

    private void releasePlayer() {
        try {
            if (player != null) {
                player.setEventListener(null);
                player.stop();
                player.detachViews();
                player.release();
                player = null;
            }
            if (libVlc != null) {
                libVlc.release();
                libVlc = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "release: " + e);
        }
    }

    // ---- full-screen ----

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}

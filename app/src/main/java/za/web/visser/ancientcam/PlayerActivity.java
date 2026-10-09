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

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

/**
 * ancient-cam: a single-purpose, full-screen RTSP viewer for an ancient Android 5.0 tablet.
 *
 * Uses ExoPlayer/Media3's RTSP, forced over TCP. That stack is pure Java (no live555), so it
 * never calls live555's ourIPAddress(), which returns 0.0.0.0 on this tablet and broke libVLC.
 *
 * - one hardcoded camera (res/values/secrets.xml -> R.string.rtsp_url), full-screen, no crop
 * - auto-reconnect: a progress watchdog restarts a frozen stream; errors trigger a retry
 * - keeps the screen on, stays immersive full-screen
 * - NO boot-autostart (it crashed surfaceflinger during this tablet's fragile boot)
 */
@UnstableApi
public class PlayerActivity extends Activity {

    private static final String TAG = "ancient-cam";
    private static final long STALL_MS = 8000;        // no frame progress this long => restart
    private static final long WATCH_EVERY_MS = 2000;
    private static final long RETRY_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private PlayerView playerView;
    private PowerManager.WakeLock wakeLock;
    private CameraLocator locator;
    private String activeHost;

    private long lastPos = -1;
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
        playerView = findViewById(R.id.player_view);
        locator = new CameraLocator(getString(R.string.rtsp_url));
        applyImmersive();

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
        open();
        handler.postDelayed(watchdog, WATCH_EVERY_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        reconnectPending = false;
        release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ---- playback ----

    private void open() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        activeHost = locator.pickHost();
        String url = locator.urlFor(activeHost);
        Log.i(TAG, "opening camera at " + activeHost);
        MediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)       // TCP interleaved: no UDP local-source-address lookup
                .setTimeoutMs(10000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));

        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.addListener(playerListener);
        player.prepare();

        lastPos = -1;
        lastProgressMs = SystemClock.elapsedRealtime();
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlayerError(PlaybackException error) {
            Log.w(TAG, "player error " + error.getErrorCodeName() + " -> reconnect in " + RETRY_MS + "ms");
            scheduleReconnect(RETRY_MS);
        }

        @Override
        public void onRenderedFirstFrame() {
            lastProgressMs = SystemClock.elapsedRealtime();
            if (activeHost != null) locator.onFrames(activeHost);   // this host is the camera; lock it
        }
    };

    /** A live stream that freezes stops advancing its position but emits no error; this catches it. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                long pos = player.getCurrentPosition();
                if (pos != lastPos) {
                    lastPos = pos;
                    lastProgressMs = SystemClock.elapsedRealtime();
                }
                long stalledFor = SystemClock.elapsedRealtime() - lastProgressMs;
                if (stalledFor > STALL_MS) {
                    Log.w(TAG, "no progress for " + stalledFor + "ms -> restart");
                    scheduleReconnect(0);
                    return;
                }
            }
            handler.postDelayed(this, WATCH_EVERY_MS);
        }
    };

    private void scheduleReconnect(long delayMs) {
        if (reconnectPending) return;
        reconnectPending = true;
        handler.removeCallbacks(watchdog);
        handler.postDelayed(() -> {
            reconnectPending = false;
            locator.onFailure();     // count the failure; after a couple, rescan the LAN for the camera
            release();
            open();
            handler.postDelayed(watchdog, WATCH_EVERY_MS);
        }, delayMs);
    }

    private void release() {
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
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

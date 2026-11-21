// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

/** One always-on, muted RTSP feed that reconnects itself on error or stall. */
@OptIn(markerClass = UnstableApi.class)
final class CameraFeed {
    private static final long RETRY_MS = 3000, STALL_MS = 15000, WATCHDOG_MS = 5000;

    private final Context ctx;
    private final String sdUrl, hdUrl;
    private boolean hd;
    private final TextureView surface;
    private final Runnable onLive;
    private boolean live;
    private final TextView state;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private long notReadySince;
    private boolean running;

    /** onLive runs on the main thread each time a (re)connection shows its first frame. */
    CameraFeed(Context ctx, String sdUrl, String hdUrl, TextureView surface, TextView state, Runnable onLive) {
        this.ctx = ctx; this.sdUrl = sdUrl; this.hdUrl = hdUrl; this.surface = surface; this.state = state;
        this.onLive = onLive;
    }

    boolean isLive() { return live; }

    /** Switch between the low-res and 1080p stream (reconnects if running). */
    void setHd(boolean on) {
        if (hd == on) return;
        hd = on;
        if (running) { h.removeCallbacks(reconnect); connect(); }
    }

    void start() {
        if (running) return;
        running = true;
        connect();
        h.postDelayed(watchdog, WATCHDOG_MS);
    }

    void stop() {
        running = false;
        live = false;
        h.removeCallbacksAndMessages(null);
        release();
    }

    private void connect() {
        release();
        show(hd ? "Connecting (HD)…" : "Connecting…");
        surface.animate().cancel();
        surface.setAlpha(0f);
        DefaultTrackSelector ts = new DefaultTrackSelector(ctx);
        ts.setParameters(ts.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true));
        player = new ExoPlayer.Builder(ctx)
                .setTrackSelector(ts)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(500, 2000, 250, 250).build())
                .build();
        player.setVolume(0f);
        player.setVideoTextureView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                show(null); live = true; onLive.run();
                surface.animate().alpha(1f).setDuration(500).start();   // ease the picture in rather than pop
            }
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_ENDED) retry("Stream ended");
            }
            @Override public void onPlayerError(PlaybackException e) {
                retry("Reconnecting… (" + e.getErrorCodeName().replace("ERROR_CODE_", "") + ")");
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(hd ? hdUrl : sdUrl))));
        player.setPlayWhenReady(true);
        player.prepare();
        notReadySince = SystemClock.elapsedRealtime();
    }

    private void retry(String msg) {
        show(msg);
        release();
        h.removeCallbacks(reconnect);
        if (running) h.postDelayed(reconnect, RETRY_MS);
    }

    private final Runnable reconnect = () -> { if (running) connect(); };

    /** Restart if the player sits buffering (frozen picture) for too long. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (player != null) {
                if (player.getPlaybackState() == Player.STATE_READY) notReadySince = now;
                else if (now - notReadySince > STALL_MS) retry("Stalled, reconnecting…");
            }
            h.postDelayed(this, WATCHDOG_MS);
        }
    };

    private void release() {
        if (player != null) { player.release(); player = null; }
    }

    private void show(String msg) {
        if (msg != null) live = false;
        state.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) state.setText(msg);
    }
}

package com.fongmi.android.tv.offline;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;

/** CPU protection owned by the download service, only while a transfer is running. */
final class OfflineDownloadPower implements AutoCloseable {
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long RENEW_MS = TIMEOUT_MS / 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DownloadManager manager;
    private final PowerManager.WakeLock lock;
    private final Runnable renew = this::update;
    private boolean closed;

    OfflineDownloadPower(Context context, DownloadManager manager) {
        this.manager = manager;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Android-TV:OfflineCache");
        lock.setReferenceCounted(false);
    }

    void update() {
        handler.removeCallbacks(renew);
        if (closed) return;
        boolean active = !manager.getDownloadsPaused() && manager.getNotMetRequirements() == 0;
        boolean downloading = false;
        if (active) {
            for (Download download : manager.getCurrentDownloads()) {
                if (download.state == Download.STATE_DOWNLOADING) {
                    downloading = true;
                    break;
                }
            }
        }
        if (downloading) {
            // Renew a bounded lease; never rely on an unbounded lock for a long video.
            lock.acquire(TIMEOUT_MS);
            handler.postDelayed(renew, RENEW_MS);
        } else if (lock.isHeld()) lock.release();
    }

    @Override public void close() {
        closed = true;
        handler.removeCallbacks(renew);
        if (lock.isHeld()) lock.release();
    }
}

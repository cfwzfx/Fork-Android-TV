package com.fongmi.android.tv.offline;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;

import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.PlatformScheduler;
import androidx.media3.exoplayer.scheduler.Scheduler;
import androidx.media3.exoplayer.scheduler.Requirements;

import com.fongmi.android.tv.R;

import java.util.List;

public final class OfflineDownloadService extends DownloadService {
    private static final String CHANNEL = "offline_downloads";
    private DownloadNotificationHelper notifications;
    private OfflineDownloadPower power;
    private DownloadManager manager;
    private final DownloadManager.Listener powerListener = new DownloadManager.Listener() {
        @Override public void onInitialized(DownloadManager manager) { power.update(); }
        @Override public void onDownloadChanged(DownloadManager manager, Download download, Exception error) { power.update(); }
        @Override public void onDownloadRemoved(DownloadManager manager, Download download) { power.update(); }
        @Override public void onDownloadsPausedChanged(DownloadManager manager, boolean paused) { power.update(); }
        @Override public void onRequirementsStateChanged(DownloadManager manager, Requirements requirements, int unmet) { power.update(); }
        @Override public void onIdle(DownloadManager manager) { power.update(); }
    };

    public OfflineDownloadService() {
        super(7301, 1000, CHANNEL, R.string.offline_title, 0);
    }

    @Override public void onCreate() {
        super.onCreate();
        manager = getDownloadManager();
        power = new OfflineDownloadPower(this, manager);
        manager.addListener(powerListener);
        power.update();
    }

    @Override public void onDestroy() {
        if (manager != null) manager.removeListener(powerListener);
        if (power != null) power.close();
        super.onDestroy();
    }

    @Override
    protected DownloadManager getDownloadManager() {
        return OfflineIntegration.get(this).manager();
    }

    @Override
    protected Scheduler getScheduler() {
        return new PlatformScheduler(this, 7302);
    }

    @Override
    protected Notification getForegroundNotification(List<Download> downloads, int notMetRequirements) {
        if (notifications == null) notifications = new DownloadNotificationHelper(this, CHANNEL);
        PendingIntent intent = PendingIntent.getActivity(this, 7301, new Intent(this, OfflineCacheActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return notifications.buildProgressNotification(this, R.drawable.offline_download, intent,
                getString(R.string.offline_notification), downloads, notMetRequirements);
    }
}

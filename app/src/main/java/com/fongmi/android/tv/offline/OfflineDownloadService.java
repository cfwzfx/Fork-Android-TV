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

import com.fongmi.android.tv.R;

import java.util.List;

public final class OfflineDownloadService extends DownloadService {
    private static final String CHANNEL = "offline_downloads";
    private DownloadNotificationHelper notifications;

    public OfflineDownloadService() {
        super(7301, 1000, CHANNEL, R.string.offline_title, 0);
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

package com.fongmi.android.tv.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Standalone offline engine. Never shares the online player's disposable cache.
 * Call commands on the main thread; query the download index off the main thread.
 * Host-specific player and HTTP wiring belongs only in OfflineIntegration.
 */
public final class OfflineCache {
    public interface NetworkFactory {
        HttpDataSource.Factory create(Map<String, String> headers);
    }

    public interface Callback {
        void complete(int message);
    }

    // Only the application context is retained; activities never enter this singleton.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static OfflineCache instance;
    private final Context context;
    private final SimpleCache cache;
    private final DownloadManager manager;
    private final SharedPreferences errors;
    private final NetworkFactory network;
    private final Map<String, DownloadHelper> preparing = new HashMap<>();
    private final Set<String> submitting = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static synchronized OfflineCache get(Context context, NetworkFactory network) {
        if (instance == null) instance = new OfflineCache(context.getApplicationContext(), network);
        return instance;
    }

    private OfflineCache(Context context, NetworkFactory network) {
        this.context = context;
        this.network = network;
        errors = context.getSharedPreferences("offline_errors", Context.MODE_PRIVATE);
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        cache = new SimpleCache(new File(context.getFilesDir(), "offline_media"), new NoOpCacheEvictor(), database);
        manager = new DownloadManager(context, new DefaultDownloadIndex(database, "offline"), request -> {
            OfflineVideo video = OfflineVideo.decode(request.data);
            return new DefaultDownloaderFactory(dataSource(video, false), Runnable::run).createDownloader(request);
        });
        manager.setRequirements(new Requirements(Requirements.NETWORK));
        manager.setMaxParallelDownloads(2);
        manager.addListener(new DownloadManager.Listener() {
            @Override
            public void onDownloadChanged(DownloadManager manager, Download download, Exception exception) {
                submitting.remove(download.request.id);
                if (exception != null) {
                    // Do not persist exception messages containing signed URLs, cookies or other credentials.
                    String reason = exception instanceof HttpDataSource.InvalidResponseCodeException ? "http" : "download";
                    errors.edit().putString(download.request.id, reason).apply();
                } else if (download.state == Download.STATE_COMPLETED) {
                    errors.edit().remove(download.request.id).apply();
                }
            }

            @Override
            public void onDownloadRemoved(DownloadManager manager, Download download) {
                submitting.remove(download.request.id);
                errors.edit().remove(download.request.id).apply();
            }
        });
    }

    DownloadManager manager() {
        return manager;
    }

    public void resumeService() {
        DownloadService.start(context, OfflineDownloadService.class);
    }

    private CacheDataSource.Factory dataSource(OfflineVideo video, boolean offline) {
        CacheDataSource.Factory factory = new CacheDataSource.Factory().setCache(cache)
                .setCacheKeyFactory(spec -> video.id + ":" + (spec.key == null ? spec.uri.toString() : spec.key));
        if (offline) return factory.setCacheWriteDataSinkFactory(null);
        return factory.setUpstreamDataSourceFactory(network.create(video.headers));
    }

    public CacheDataSource.Factory playback(Download download) {
        if (download.state != Download.STATE_COMPLETED) throw new IllegalStateException("Download is incomplete");
        return dataSource(OfflineVideo.decode(download.request.data), true);
    }

    public List<Download> list() throws IOException {
        List<Download> result = new ArrayList<>();
        try (DownloadCursor cursor = manager.getDownloadIndex().getDownloads()) {
            while (cursor.moveToNext()) result.add(cursor.getDownload());
        }
        result.sort(Comparator.comparingLong((Download value) -> value.startTimeMs).reversed());
        return result;
    }

    public Download find(String id) throws IOException {
        return manager.getDownloadIndex().getDownload(id);
    }

    public int failure(String id) {
        return "http".equals(errors.getString(id, "download"))
                ? com.fongmi.android.tv.R.string.offline_http_error : com.fongmi.android.tv.R.string.offline_download_error;
    }

    public void add(OfflineVideo video, TrackSelectionParameters parameters, Callback callback) {
        if (submitting.contains(video.id)) {
            callback.complete(com.fongmi.android.tv.R.string.offline_exists);
            return;
        }
        if (preparing.containsKey(video.id)) {
            callback.complete(com.fongmi.android.tv.R.string.offline_preparing);
            return;
        }
        try {
            Download existing = find(video.id);
            if (existing != null) {
                callback.complete(com.fongmi.android.tv.R.string.offline_exists);
                return;
            }
        } catch (IOException e) {
            callback.complete(com.fongmi.android.tv.R.string.offline_storage_error);
            return;
        }
        MediaItem item = new MediaItem.Builder().setUri(Uri.parse(video.url)).setMimeType(video.mimeType).build();
        DownloadHelper helper = new DownloadHelper.Factory()
                .setDataSourceFactory(network.create(video.headers))
                .setRenderersFactory(new DefaultRenderersFactory(context))
                .setTrackSelectionParameters(parameters).create(item);
        preparing.put(video.id, helper);
        callback.complete(com.fongmi.android.tv.R.string.offline_preparing);
        Runnable timeout = () -> finish(video.id, helper, callback, com.fongmi.android.tv.R.string.offline_prepare_error);
        handler.postDelayed(timeout, 45000);
        helper.prepare(new DownloadHelper.Callback() {
            @Override
            public void onPrepared(DownloadHelper prepared, boolean tracksInfoAvailable) {
                if (preparing.get(video.id) != prepared) return;
                handler.removeCallbacks(timeout);
                try {
                    // Reject protected tracks discovered in the manifest, too.
                    for (int period = 0; tracksInfoAvailable && period < prepared.getPeriodCount(); period++) {
                        boolean hasVideo = false, hasAudio = false, selectedVideo = false, selectedAudio = false;
                        for (androidx.media3.common.Tracks.Group group : prepared.getTracks(period).getGroups()) {
                            int type = group.getType();
                            hasVideo |= type == androidx.media3.common.C.TRACK_TYPE_VIDEO;
                            hasAudio |= type == androidx.media3.common.C.TRACK_TYPE_AUDIO;
                            selectedVideo |= type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected();
                            selectedAudio |= type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected();
                            for (int i = 0; i < group.length; i++) {
                                if (group.isTrackSelected(i) && group.getTrackFormat(i).drmInitData != null)
                                    throw new IllegalArgumentException("Protected track");
                            }
                        }
                        if ((hasVideo && !selectedVideo) || (hasAudio && !selectedAudio) || (!hasVideo && !hasAudio))
                            throw new IllegalArgumentException("No complete playable track selection");
                    }
                    DownloadRequest request = prepared.getDownloadRequest(video.id, video.encode());
                    submitting.add(video.id);
                    DownloadService.sendAddDownload(context, OfflineDownloadService.class, request, false);
                    handler.postDelayed(() -> submitting.remove(video.id), 30000);
                    finish(video.id, prepared, callback, com.fongmi.android.tv.R.string.offline_added);
                } catch (RuntimeException e) {
                    submitting.remove(video.id);
                    finish(video.id, prepared, callback, com.fongmi.android.tv.R.string.offline_unsupported);
                }
            }

            @Override
            public void onPrepareError(DownloadHelper prepared, IOException e) {
                handler.removeCallbacks(timeout);
                finish(video.id, prepared, callback, e instanceof DownloadHelper.LiveContentUnsupportedException
                        ? com.fongmi.android.tv.R.string.offline_unsupported
                        : com.fongmi.android.tv.R.string.offline_prepare_error);
            }
        });
    }

    private void finish(String id, DownloadHelper helper, Callback callback, int message) {
        if (preparing.get(id) != helper) return;
        preparing.remove(id);
        helper.release();
        callback.complete(message);
    }

    public void pause(String id) {
        DownloadService.sendSetStopReason(context, OfflineDownloadService.class, id, 1, false);
    }

    public void continueDownload(Download download) {
        errors.edit().remove(download.request.id).apply();
        DownloadService.sendAddDownload(context, OfflineDownloadService.class, download.request, false);
        DownloadService.sendSetStopReason(context, OfflineDownloadService.class, download.request.id, 0, false);
    }

    public void remove(String id) {
        DownloadService.sendRemoveDownload(context, OfflineDownloadService.class, id, false);
    }
}

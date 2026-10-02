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
    private final Set<String> probing = new HashSet<>();
    private final Map<String, String> inFlightEpisodes = new HashMap<>();
    private final Map<String, Object> admission = new HashMap<>();
    private final Set<String> importing = new HashSet<>();
    private final Map<String, Object> flightTokens = new HashMap<>();
    private final Set<String> submitting = new HashSet<>();
    private final Map<String, Replacement> replacements = new HashMap<>();
    private record Replacement(OfflineVideo video, Object token, Callback callback) {}
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static synchronized OfflineCache get(Context context, NetworkFactory network) {
        if (instance == null) instance = new OfflineCache(context.getApplicationContext(), network);
        return instance;
    }

    static synchronized OfflineCache peek() { return instance; }
    androidx.media3.datasource.DataSource.Factory subtitleNetwork(OfflineVideo video) { return network.create(video.headers); }

    private OfflineCache(Context context, NetworkFactory network) {
        this.context = context;
        this.network = headers -> OfflineHttpDataSource.factory(network.create(headers));
        errors = context.getSharedPreferences("offline_errors", Context.MODE_PRIVATE);
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        cache = new SimpleCache(new File(context.getFilesDir(), "offline_media"), new NoOpCacheEvictor(), database);
        manager = new DownloadManager(context, new DefaultDownloadIndex(database, "offline"), request -> {
            OfflineVideo video = OfflineVideo.decode(request.data);
            return OfflineCacheIntegrity.checked(context, cache, request, dataSource(video, true),
                    new DefaultDownloaderFactory(dataSource(video, false), Runnable::run).createDownloader(request));
        });
        manager.setRequirements(new Requirements(Requirements.NETWORK));
        manager.setMaxParallelDownloads(2);
        manager.addListener(new DownloadManager.Listener() {
            @Override
            public void onDownloadChanged(DownloadManager manager, Download download, Exception exception) {
                releaseFlight(download.request.id);
                if (exception != null) {
                    // Do not persist exception messages containing signed URLs, cookies or other credentials.
                    String reason = exception instanceof HttpDataSource.InvalidResponseCodeException ? "http" : "download";
                    for (Throwable cause = exception; cause != null; cause = cause.getCause())
                        if (cause instanceof OfflineCacheIntegrity.Incomplete || cause instanceof java.io.EOFException) { reason = "integrity"; break; }
                    errors.edit().putString(download.request.id, reason).apply();
                } else if (download.state == Download.STATE_COMPLETED) {
                    errors.edit().remove(download.request.id).apply();
                }
            }

            @Override
            public void onDownloadRemoved(DownloadManager manager, Download download) {
                releaseFlight(download.request.id);
                Replacement replacement = replacements.get(download.request.id);
                if (replacement != null) { finishReplacement(replacement); return; }
                try {
                    if (importing.contains(OfflineVideo.decode(download.request.data).episodeKey())) return;
                    for (String key : cache.getKeys()) {
                        if (key.startsWith(download.request.id + ":http://offline.subtitle/")) cache.removeResource(key);
                    }
                } catch (RuntimeException ignored) {}
                errors.edit().remove(download.request.id).apply();
                context.getSharedPreferences("offline_playback", 0).edit().remove("subtitles:" + download.request.id)
                        .remove("danmaku:" + download.request.id).remove("danmakuSelected:" + download.request.id).apply();
            }
        });
    }

    DownloadManager manager() {
        return manager;
    }

    androidx.media3.datasource.cache.SimpleCache storage() { return cache; }

    /** Called by the transfer worker; reservation shares the normal download admission gate. */
    boolean reserveImport(OfflineVideo video) throws IOException {
        return onMain(() -> {
            if (admission.containsKey(video.episodeKey()) || inFlightEpisodes.containsKey(video.episodeKey())
                    || existingEpisode(video.episodeKey(), video.id) != null) return false;
            importing.add(video.episodeKey());
            admission.put(video.episodeKey(), video.id);
            inFlightEpisodes.put(video.episodeKey(), video.id);
            return true;
        });
    }

    void releaseImport(OfflineVideo video) throws IOException {
        onMain(() -> {
            if (importing.remove(video.episodeKey())) admission.remove(video.episodeKey(), video.id);
            releaseFlight(video.id);
            return null;
        });
    }

    private <T> T onMain(java.util.concurrent.Callable<T> work) throws IOException {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(work);
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else handler.post(task);
        try { return task.get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Transfer interrupted", error); }
        catch (java.util.concurrent.ExecutionException error) { throw new IOException("Cache unavailable", error.getCause()); }
    }

    public void resumeService() {
        DownloadService.start(context, OfflineDownloadService.class);
    }

    CacheDataSource.Factory dataSource(OfflineVideo video, boolean offline) {
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

    /** Exact configuration/source/show/line/episode-name identity; unnamed episodes use their URL. */
    Download completedFor(com.fongmi.android.tv.bean.History history) throws IOException {
        for (Download download : list()) {
            if (download.state != Download.STATE_COMPLETED) continue;
            try {
                OfflineVideo video = OfflineVideo.decode(download.request.data);
                com.fongmi.android.tv.bean.History original = OfflineHistory.original(video);
                if (!OfflineVideo.sameEpisode(original, history)) continue;
                long available = 0;
                for (String key : cache.getKeys()) {
                    if (!key.startsWith(video.id + ":")) continue;
                    for (androidx.media3.datasource.cache.CacheSpan span : cache.getCachedSpans(key)) {
                        if (span.file == null || !span.file.isFile() || span.file.length() != span.length) {
                            available = -1;
                            break;
                        }
                        available += span.length;
                    }
                    if (available < 0) break;
                }
                String primaryKey = video.id + ":" + (download.request.customCacheKey == null
                        ? download.request.uri.toString() : download.request.customCacheKey);
                if (available > 0 && available >= download.getBytesDownloaded()
                        && !cache.getCachedSpans(primaryKey).isEmpty()) {
                    try { verify(download); return download; }
                    catch (IOException error) { /* Damaged completed records cannot replace online media. */ }
                } else errors.edit().putString(download.request.id, "integrity").apply();
            } catch (RuntimeException ignored) { /* Invalid metadata is never a match. */ }
        }
        return null;
    }

    /** Runs on a worker; never requests the video source. */
    public void verify(Download download) throws IOException {
        try {
            OfflineCacheIntegrity.verify(context, cache, download.request, dataSource(OfflineVideo.decode(download.request.data), true));
            if (download.state == Download.STATE_COMPLETED) errors.edit().remove(download.request.id).apply();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException("Cache verification interrupted", error);
        } catch (IOException error) {
            errors.edit().putString(download.request.id, "integrity").apply(); throw error;
        }
    }

    private Download existingEpisode(String episodeKey, String id) throws IOException {
        Download existing = id == null ? null : find(id);
        if (existing != null && existing.state == Download.STATE_COMPLETED) return existing;
        for (Download download : list()) {
            try {
                if (!episodeKey.equals(OfflineVideo.decode(download.request.data).episodeKey())) continue;
                if (download.state == Download.STATE_COMPLETED) return download;
                if (existing == null) existing = download;
            } catch (RuntimeException ignored) {}
        }
        return existing;
    }

    private int existingMessage(Download download) {
        return download.state == Download.STATE_COMPLETED ? com.fongmi.android.tv.R.string.offline_already_cached
                : com.fongmi.android.tv.R.string.offline_exists;
    }

    /** Reserves the logical episode before source resolution; every public entry uses this gate. */
    void requestEpisode(com.fongmi.android.tv.bean.History history,
                        java.util.function.Consumer<Callback> ready, Callback callback) {
        String key = OfflineVideo.identity(history);
        if (admission.containsKey(key) || inFlightEpisodes.containsKey(key)) {
            callback.complete(com.fongmi.android.tv.R.string.offline_preparing);
            return;
        }
        Object token = new Object();
        admission.put(key, token);
        Callback finish = message -> {
            if (message != com.fongmi.android.tv.R.string.offline_preparing) admission.remove(key, token);
            callback.complete(message);
        };
        callback.complete(com.fongmi.android.tv.R.string.offline_preparing);
        com.fongmi.android.tv.utils.Task.execute(() -> {
            try {
                Download existing = existingEpisode(key, null);
                handler.post(() -> {
                    if (admission.get(key) != token) return;
                    if (existing != null) finish.complete(existingMessage(existing));
                    else if (inFlightEpisodes.containsKey(key)) finish.complete(com.fongmi.android.tv.R.string.offline_exists);
                    else {
                        try { ready.accept(finish); }
                        catch (RuntimeException error) { finish.complete(com.fongmi.android.tv.R.string.offline_storage_error); }
                    }
                });
            } catch (IOException error) {
                handler.post(() -> finish.complete(com.fongmi.android.tv.R.string.offline_storage_error));
            }
        });
    }

    private void releaseFlight(String id) {
        submitting.remove(id);
        flightTokens.remove(id);
        inFlightEpisodes.values().removeIf(id::equals);
    }

    public int failure(String id) {
        if (damaged(id)) return com.fongmi.android.tv.R.string.offline_integrity_error;
        return "http".equals(errors.getString(id, "download"))
                ? com.fongmi.android.tv.R.string.offline_http_error : com.fongmi.android.tv.R.string.offline_download_error;
    }

    public boolean damaged(String id) { return "integrity".equals(errors.getString(id, "")); }

    public void add(OfflineVideo video, TrackSelectionParameters parameters, Callback callback) {
        if (replacements.values().stream().anyMatch(value -> value.video.episodeKey().equals(video.episodeKey()))
                || importing.contains(video.episodeKey()) || inFlightEpisodes.containsKey(video.episodeKey()) || submitting.contains(video.id)) {
            callback.complete(com.fongmi.android.tv.R.string.offline_exists);
            return;
        }
        if (probing.contains(video.id) || preparing.containsKey(video.id)) {
            callback.complete(com.fongmi.android.tv.R.string.offline_preparing);
            return;
        }
        try {
            Download existing = existingEpisode(video.episodeKey(), video.id);
            if (existing != null) {
                callback.complete(existingMessage(existing));
                return;
            }
        } catch (IOException e) {
            callback.complete(com.fongmi.android.tv.R.string.offline_storage_error);
            return;
        }
        inFlightEpisodes.put(video.episodeKey(), video.id);
        flightTokens.put(video.id, new Object());
        probing.add(video.id);
        callback.complete(com.fongmi.android.tv.R.string.offline_preparing);
        com.fongmi.android.tv.utils.Task.execute(() -> {
            try {
                OfflineVideo inspected = OfflineProbe.inspect(video, network);
                OfflineSubtitles.capture(context, this, inspected);
                handler.post(() -> {
                    probing.remove(video.id);
                    try { prepare(inspected, parameters, callback); }
                    catch (RuntimeException error) {
                        DownloadHelper helper = preparing.get(video.id);
                        if (helper != null) finish(video.id, helper, callback, com.fongmi.android.tv.R.string.offline_unsupported);
                        else { releaseFlight(video.id); callback.complete(com.fongmi.android.tv.R.string.offline_unsupported); }
                    }
                });
            } catch (Exception error) {
                handler.post(() -> {
                    probing.remove(video.id);
                    releaseFlight(video.id);
                    callback.complete(OfflineProbe.failure(error));
                });
            }
        });
    }

    private void prepare(OfflineVideo video, TrackSelectionParameters parameters, Callback callback) {
        MediaItem item = new MediaItem.Builder().setUri(Uri.parse(video.url)).setMimeType(video.mimeType).setAdblock(com.fongmi.android.tv.setting.Setting.isAdblock()).build();
        DownloadHelper helper = new DownloadHelper.Factory()
                .setDataSourceFactory(network.create(video.headers))
                .setRenderersFactory(new DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
                .setTrackSelectionParameters(parameters).create(item);
        preparing.put(video.id, helper);
        Runnable timeout = () -> finish(video.id, helper, callback, com.fongmi.android.tv.R.string.offline_prepare_error);
        handler.postDelayed(timeout, 45000);
        helper.prepare(new DownloadHelper.Callback() {
            @Override
            public void onPrepared(DownloadHelper prepared, boolean tracksInfoAvailable) {
                if (preparing.get(video.id) != prepared) return;
                handler.removeCallbacks(timeout);
                try {
                    // Reject protected tracks discovered in the manifest, too.
                    if (tracksInfoAvailable) selectAllSubtitles(prepared);
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
                    Object token = flightTokens.get(video.id);
                    handler.postDelayed(() -> { if (flightTokens.get(video.id) == token) releaseFlight(video.id); }, 30000);
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
                        : OfflineProbe.failure(e));
            }
        });
    }

    private void finish(String id, DownloadHelper helper, Callback callback, int message) {
        if (preparing.get(id) != helper) return;
        preparing.remove(id);
        helper.release();
        if (message != com.fongmi.android.tv.R.string.offline_added) releaseFlight(id);
        callback.complete(message);
    }

    private void selectAllSubtitles(DownloadHelper helper) {
        for (int period = 0; period < helper.getPeriodCount(); period++) {
            var mapping = helper.getMappedTrackInfo(period);
            for (int renderer = 0; renderer < mapping.getRendererCount(); renderer++) {
                if (mapping.getRendererType(renderer) != androidx.media3.common.C.TRACK_TYPE_TEXT) continue;
                var groups = mapping.getTrackGroups(renderer);
                List<androidx.media3.exoplayer.trackselection.DefaultTrackSelector.SelectionOverride> overrides = new ArrayList<>();
                for (int group = 0; group < groups.length; group++) {
                    List<Integer> indices = new ArrayList<>();
                    for (int track = 0; track < groups.get(group).length; track++) {
                        if (mapping.getTrackSupport(renderer, group, track) >= androidx.media3.common.C.FORMAT_EXCEEDS_CAPABILITIES
                                && groups.get(group).getFormat(track).drmInitData == null) indices.add(track);
                    }
                    if (!indices.isEmpty()) overrides.add(new androidx.media3.exoplayer.trackselection.DefaultTrackSelector.SelectionOverride(group,
                            indices.stream().mapToInt(Integer::intValue).toArray()));
                }
                if (!overrides.isEmpty()) helper.addTrackSelectionForSingleRenderer(period, renderer,
                        DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build(), overrides);
            }
        }
    }

    public void pause(String id) {
        DownloadService.sendSetStopReason(context, OfflineDownloadService.class, id, 1, false);
    }

    public void continueDownload(Download download) {
        errors.edit().remove(download.request.id).apply();
        DownloadService.sendAddDownload(context, OfflineDownloadService.class, download.request, false);
        DownloadService.sendSetStopReason(context, OfflineDownloadService.class, download.request.id, 0, false);
    }

    public void replace(Download old, OfflineVideo fresh, Callback callback) {
        Replacement pending = null;
        try {
            Download current = find(old.request.id);
            if (current == null || current.startTimeMs != old.startTimeMs || !java.util.Arrays.equals(current.request.data, old.request.data)
                    || (current.state != Download.STATE_COMPLETED && current.state != Download.STATE_FAILED && current.state != Download.STATE_STOPPED)
                    || !OfflineVideo.sameEpisode(OfflineHistory.original(OfflineVideo.decode(old.request.data)), OfflineHistory.original(fresh))) {
                callback.complete(com.fongmi.android.tv.R.string.offline_not_ready); return;
            }
            String key = fresh.episodeKey();
            if (admission.containsKey(key) || inFlightEpisodes.containsKey(key) || replacements.containsKey(old.request.id)) {
                callback.complete(com.fongmi.android.tv.R.string.offline_exists); return;
            }
            var preferences = context.getSharedPreferences("offline_playback", 0);
            String comments = preferences.getString("danmaku:" + old.request.id, OfflineVideo.decode(old.request.data).danmaku);
            var retained = new ArrayList<>(com.fongmi.android.tv.bean.Danmaku.arrayFrom(comments));
            for (var item : com.fongmi.android.tv.bean.Danmaku.arrayFrom(fresh.danmaku)) if (!retained.contains(item)) retained.add(item);
            OfflineVideo video = fresh.withId(old.request.id).withPlaybackState(fresh.history, com.fongmi.android.tv.App.gson().toJson(retained));
            Object token = new Object();
            Replacement replacement = new Replacement(video, token, callback);
            replacements.put(old.request.id, replacement); admission.put(key, token);
            pending = replacement;
            callback.complete(com.fongmi.android.tv.R.string.offline_preparing);
            com.fongmi.android.tv.utils.Task.execute(() -> {
                try {
                    OfflineProbe.inspect(video, network);
                    handler.post(() -> {
                        if (replacements.get(video.id) != replacement) return;
                        try { DownloadService.sendRemoveDownload(context, OfflineDownloadService.class, video.id, false); }
                        catch (RuntimeException error) { endReplacement(replacement, com.fongmi.android.tv.R.string.offline_storage_error); }
                    });
                } catch (Exception error) {
                    handler.post(() -> endReplacement(replacement, OfflineProbe.failure(error)));
                }
            });
        } catch (Exception error) {
            if (pending != null) endReplacement(pending, com.fongmi.android.tv.R.string.offline_storage_error);
            else callback.complete(com.fongmi.android.tv.R.string.offline_storage_error);
        }
    }

    private void endReplacement(Replacement replacement, int result) {
        replacements.remove(replacement.video.id, replacement);
        admission.remove(replacement.video.episodeKey(), replacement.token);
        replacement.callback.complete(result);
    }

    private void finishReplacement(Replacement replacement) {
        com.fongmi.android.tv.utils.Task.execute(() -> {
            try {
                for (String key : cache.getKeys())
                    if (key.startsWith(replacement.video.id + ":") && !key.startsWith(replacement.video.id + ":http://offline.subtitle/"))
                        cache.removeResource(key);
                handler.post(() -> {
                    replacements.remove(replacement.video.id, replacement);
                    try { add(replacement.video, OfflineIntegration.downloadParameters().build(), result -> {
                        if (result != com.fongmi.android.tv.R.string.offline_preparing)
                            admission.remove(replacement.video.episodeKey(), replacement.token);
                        replacement.callback.complete(result);
                    }); } catch (RuntimeException error) { endReplacement(replacement, com.fongmi.android.tv.R.string.offline_storage_error); }
                });
            } catch (Exception error) {
                handler.post(() -> endReplacement(replacement, com.fongmi.android.tv.R.string.offline_storage_error));
            }
        });
    }

    public void remove(String id) {
        DownloadService.sendRemoveDownload(context, OfflineDownloadService.class, id, false);
    }
}

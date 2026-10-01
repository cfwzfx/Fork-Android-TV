package com.fongmi.android.tv.offline;

import android.content.SharedPreferences;

import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaMetadata;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.PlayerManager;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Cache-first media selection inside an online page; its original history and episodes remain intact. */
public final class CachedVodPlayback {
    private final FragmentActivity activity;
    private final OfflineCache cache;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private int generation;
    private boolean closed;

    public CachedVodPlayback(FragmentActivity activity) {
        this.activity = activity;
        cache = OfflineIntegration.get(activity);
    }

    public void start(History requested, PlayerManager players, long position, MediaMetadata metadata,
                      String key, BooleanSupplier valid, Consumer<Boolean> ready) {
        History history = requested.copy();
        int token = ++generation;
        reader.execute(() -> {
            Download match;
            try { match = cache.completedFor(history); }
            catch (Exception error) { match = null; }
            Download download = match;
            activity.runOnUiThread(() -> {
                if (closed || token != generation || activity.isDestroyed() || !valid.getAsBoolean()) return;
                if (download == null) { ready.accept(false); return; }
                try {
                    OfflineVideo video = OfflineVideo.decode(download.request.data);
                    SharedPreferences saved = activity.getSharedPreferences("offline_playback", 0);
                    List<Danmaku> comments = App.gson().fromJson(saved.getString("danmaku:" + video.id, video.danmaku),
                            new TypeToken<List<Danmaku>>() {}.getType());
                    if (comments == null) comments = new ArrayList<>();
                    Result source = video.source.isEmpty() ? new Result() : Result.objectFrom(video.source);
                    if (source == null) source = new Result();
                    if (source.getKey().isEmpty()) source.setKey(history.getKey().split(java.util.regex.Pattern.quote(com.fongmi.android.tv.db.AppDatabase.SYMBOL), -1)[0]);
                    if (source.getUrl().isEmpty()) source.setUrl(history.getEpisodeUrl());
                    if (source.getFlag().isEmpty()) source.setFlag(history.getVodFlag());
                    players.setSourceResult(source);
                    // Publish the canonical playing request before synchronous player callbacks.
                    ready.accept(true);
                    players.startOffline(OfflineSubtitles.attach(activity, video.id, download.request.toMediaItem()).buildUpon().setMediaMetadata(metadata).setMediaId(key).build(),
                            new DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(activity, cache.playback(download))),
                            comments, item -> saved.edit().putString("danmaku:" + video.id, App.gson().toJson(players.getDanmakus())).apply(), position);
                } catch (RuntimeException error) { ready.accept(false); }
            });
        });
    }

    public void cancel() { generation++; }

    public void close() {
        closed = true;
        cancel();
        reader.shutdownNow();
    }
}

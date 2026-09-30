package com.fongmi.android.tv.offline;

import android.content.Intent;
import android.content.SharedPreferences;

import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Url;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.Players;
import com.fongmi.android.tv.utils.Notify;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Adapts persisted downloads to the existing VideoActivity detail/episode/player flow. */
public final class OfflinePlayback {
    private final FragmentActivity activity;
    private final OfflineCache cache;
    private final SharedPreferences preferences;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private OfflineVideo selected;
    private String group;
    private boolean closed;
    private int generation;

    public static boolean isOffline(Intent intent) {
        return intent.hasExtra("offline_id");
    }

    public OfflinePlayback(FragmentActivity activity) {
        this.activity = activity;
        cache = OfflineIntegration.get(activity);
        preferences = activity.getSharedPreferences("offline_playback", 0);
    }

    private static String group(OfflineVideo video) {
        try {
            if (!video.history.isEmpty()) return History.objectFrom(video.history).getKey();
        } catch (RuntimeException ignored) {}
        return UUID.nameUUIDFromBytes((video.title + "\n" + video.line).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public void loadDetail(Consumer<Vod> ready) {
        int token = ++generation;
        String id = activity.getIntent().getStringExtra("offline_id");
        reader.execute(() -> {
            try {
                Download download = cache.find(id);
                if (download == null || download.state != Download.STATE_COMPLETED) throw new IOException();
                OfflineVideo video = OfflineVideo.decode(download.request.data);
                com.fongmi.android.tv.api.config.VodConfig.get().restoreParses();
                String show = group(video);
                Flag flag = Flag.create(activity.getString(R.string.offline_title));
                for (Download candidate : cache.list()) {
                    if (candidate.state != Download.STATE_COMPLETED) continue;
                    OfflineVideo item = OfflineVideo.decode(candidate.request.data);
                    if (show.equals(group(item))) flag.getEpisodes().add(Episode.create(item.episode, "offline:" + item.id));
                }
                flag.getEpisodes().sort(Comparator.comparingInt(Episode::getNumber));
                Vod detail = new Vod();
                detail.setVodId(id);
                detail.setVodName(video.title);
                detail.setVodFlags(Collections.singletonList(flag));
                activity.runOnUiThread(() -> {
                    if (closed || token != generation || activity.isDestroyed()) return;
                    selected = video;
                    group = show;
                    activity.getIntent().putExtra("mark", video.episode);
                    ready.accept(detail);
                });
            } catch (IOException | RuntimeException e) {
                activity.runOnUiThread(() -> {
                    if (closed || token != generation || activity.isDestroyed()) return;
                    Notify.show(R.string.offline_missing);
                    activity.finish();
                });
            }
        });
    }

    public Site site() {
        Site site = new Site();
        site.setKey("offline");
        site.setName(activity.getString(R.string.offline_title));
        site.setChangeable(false);
        return site;
    }

    public History history() {
        History history;
        try {
            String saved = preferences.getString("history:" + group, selected.history);
            history = saved.isEmpty() ? new History() : History.objectFrom(saved);
        } catch (RuntimeException e) {
            history = new History();
        }
        if (history == null) history = new History();
        if (!selected.episode.equals(history.getVodRemarks())) history.setPosition(C.TIME_UNSET);
        history.setKey("offline:" + group);
        history.setVodName(selected.title);
        history.setVodFlag(activity.getString(R.string.offline_title));
        history.setVodRemarks(selected.episode);
        history.setEpisodeUrl("offline:" + selected.id);
        return history;
    }

    public Result source() {
        if (selected == null) return null;
        if (!selected.source.isEmpty()) return Result.objectFrom(selected.source);
        Result result = new Result();
        String url = selected.url;
        try {
            History original = selected.history.isEmpty() ? null : History.objectFrom(selected.history);
            if (original != null) {
                if (original.getEpisodeUrl() != null && !original.getEpisodeUrl().isEmpty()) url = original.getEpisodeUrl();
                if (original.getKey() != null) result.setKey(original.getKey().split(java.util.regex.Pattern.quote(AppDatabase.SYMBOL))[0]);
                result.setFlag(original.getVodFlag());
            }
        } catch (RuntimeException ignored) {}
        result.setUrl(Url.create().add(url));
        return result;
    }

    public void save(History history) {
        if (group != null && history != null && !Setting.isIncognito()) {
            preferences.edit().putString("history:" + group, history.toString()).apply();
        }
    }

    public void play(Episode episode, Players players, Runnable missing) {
        int token = ++generation;
        String id = episode.getUrl().substring("offline:".length());
        reader.execute(() -> {
            try {
                Download download = cache.find(id);
                if (download == null || download.state != Download.STATE_COMPLETED) throw new IOException();
                OfflineVideo video = OfflineVideo.decode(download.request.data);
                List<Danmaku> comments = App.gson().fromJson(preferences.getString("danmaku:" + id, video.danmaku),
                        new TypeToken<List<Danmaku>>() {}.getType());
                if (comments == null) comments = new ArrayList<>();
                List<Danmaku> items = comments;
                activity.runOnUiThread(() -> {
                    if (closed || token != generation || activity.isDestroyed()) return;
                    selected = video;
                    players.startOffline(download.request.toMediaItem(),
                            new DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(activity, cache.playback(download))), items,
                            value -> preferences.edit().putString("danmaku:" + id, App.gson().toJson(players.getDanmakus())).apply());
                });
            } catch (IOException | RuntimeException e) {
                activity.runOnUiThread(() -> {
                    if (!closed && token == generation && !activity.isDestroyed()) missing.run();
                });
            }
        });
    }

    public void close() {
        closed = true;
        generation++;
        reader.shutdownNow();
    }
}

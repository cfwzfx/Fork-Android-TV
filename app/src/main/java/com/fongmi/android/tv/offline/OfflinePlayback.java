package com.fongmi.android.tv.offline;

import android.content.Intent;
import android.content.SharedPreferences;

import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Url;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.utils.Notify;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    private final Map<String, OfflineVideo> episodes = new HashMap<>();
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
                Map<String, OfflineVideo> savedEpisodes = new HashMap<>();
                Flag flag = Flag.create(activity.getString(R.string.offline_title));
                for (Download candidate : cache.list()) {
                    if (candidate.state != Download.STATE_COMPLETED) continue;
                    OfflineVideo item = OfflineVideo.decode(candidate.request.data);
                    if (show.equals(group(item))) {
                        savedEpisodes.put(item.id, item);
                        flag.getEpisodes().add(Episode.create(item.episode, "offline:" + item.id));
                    }
                }
                flag.getEpisodes().sort(Comparator.comparingInt(Episode::getNumber));
                Vod detail = new Vod();
                detail.setId(id);
                detail.setName(video.title);
                detail.setFlags(Collections.singletonList(flag));
                activity.runOnUiThread(() -> {
                    if (closed || token != generation || activity.isDestroyed()) return;
                    selected = video;
                    episodes.clear();
                    episodes.putAll(savedEpisodes);
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
        History history = OfflineHistory.restore(selected);
        history.setKey("offline:" + group);
        history.setVodName(selected.title);
        history.setVodFlag(activity.getString(R.string.offline_title));
        history.setVodRemarks(selected.episode);
        history.setEpisodeUrl("offline:" + selected.id);
        return history;
    }

    /** Restore the shared position when selecting a different cached episode. */
    public void selectEpisode(History history, Episode episode) {
        String url = episode.getUrl();
        if (!url.startsWith("offline:") || url.equals(history.getEpisodeUrl())) return;
        OfflineVideo video = episodes.get(url.substring("offline:".length()));
        if (video == null) return;
        History restored = OfflineHistory.restore(video);
        history.setPosition(restored.getPosition());
        history.setDuration(restored.getDuration());
        selected = video;
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
        result.setUrl(url);
        return result;
    }

    public void save(History history) {
        save(history, false);
    }

    public void save(History history, boolean exit) {
        if (group == null || history == null || Setting.isIncognito()) return;
        String url = history.getEpisodeUrl();
        OfflineVideo video = url.startsWith("offline:") ? episodes.get(url.substring("offline:".length())) : null;
        if (video == null) return;
        OfflineHistory.save(video, history, exit);
    }

    public void play(Episode episode, PlayerManager players, Runnable missing) {
        play(episode, players, missing, C.TIME_UNSET, androidx.media3.common.MediaMetadata.EMPTY, "offline");
    }

    public void play(Episode episode, PlayerManager players, Runnable missing, long position, androidx.media3.common.MediaMetadata metadata, String key) {
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
                    players.startOffline(download.request.toMediaItem().buildUpon().setMediaMetadata(metadata).setMediaId(key).build(),
                            new DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(activity, cache.playback(download))), items,
                            value -> preferences.edit().putString("danmaku:" + id, App.gson().toJson(players.getDanmakus())).apply(), position);
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

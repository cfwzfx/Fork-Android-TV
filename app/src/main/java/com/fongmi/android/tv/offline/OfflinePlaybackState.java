package com.fongmi.android.tv.offline;

import android.content.Context;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.player.danmaku.CustomConfigManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.FutureTask;

/** Movie-specific state only; never transfers device-wide settings or service credentials. */
final class OfflinePlaybackState {
    static OfflineVideo snapshot(Context context, OfflineVideo video) {
        History original = OfflineHistory.original(video);
        if (original == null) return video;
        History latest = OfflineHistory.restore(video);
        latest.setKey(original.getKey()); latest.setCid(original.getCid());
        latest.setVodFlag(original.getVodFlag()); latest.setVodRemarks(original.getVodRemarks());
        latest.setEpisodeUrl(original.getEpisodeUrl());
        String comments = context.getSharedPreferences("offline_playback", 0).getString("danmaku:" + video.id, video.danmaku);
        return video.withPlaybackState(latest.toString(), comments);
    }

    static JSONObject export(Context context, OfflineVideo video) throws Exception {
        History original = OfflineHistory.original(video);
        JSONObject state = new JSONObject().put("subtitles", OfflineSubtitles.saved(context, video.id));
        var preferences = context.getSharedPreferences("offline_playback", 0);
        if (preferences.contains("danmakuSelected:" + video.id)) state.put("danmakuSelected", preferences.getString("danmakuSelected:" + video.id, ""));
        JSONArray tracks = new JSONArray();
        for (Track track : Track.find(original.getKey())) tracks.put(new JSONObject().put("type", track.getType())
                .put("name", track.getName()).put("format", track.getFormat()).put("selected", track.isSelected()));
        state.put("tracks", tracks);
        Long offset = main(() -> {
            CustomConfigManager config = CustomConfigManager.get();
            return config.hasHistoryOffset(original.getKey()) ? config.getHistoryOffset(original.getKey()) : null;
        });
        if (offset != null) state.put("danmakuOffset", offset);
        return state;
    }

    static void validate(JSONObject state, Set<String> resources) throws Exception {
        JSONArray subtitles = state.getJSONArray("subtitles"), tracks = state.getJSONArray("tracks");
        Set<String> seen = new HashSet<>();
        if (subtitles.length() > 1000 || tracks.length() > 3) throw new IOException("Invalid movie settings");
        for (int i = 0; i < subtitles.length(); i++) {
            JSONObject subtitle = subtitles.getJSONObject(i);
            String url = subtitle.getString("url");
            if (!url.matches("http://offline\\.subtitle/[0-9a-f-]{36}\\.sub") || !resources.contains(url) || !seen.add(url))
                throw new IOException("Subtitle data is missing");
            subtitle.getString("name"); subtitle.getString("format");
        }
        Set<Integer> types = new HashSet<>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.getJSONObject(i);
            int type = track.getInt("type");
            if (type < 1 || type > 3 || !types.add(type)) throw new IOException("Invalid movie track selection");
            track.getString("format"); track.getBoolean("selected");
        }
        if (state.has("danmakuOffset") && (state.getLong("danmakuOffset") < -300 || state.getLong("danmakuOffset") > 300))
            throw new IOException("Invalid comment offset");
    }

    static void apply(Context context, OfflineVideo video, JSONObject state) throws Exception {
        History incoming = OfflineHistory.original(video);
        if (!context.getSharedPreferences("offline_playback", 0).edit()
                .putString("subtitles:" + video.id, state.getJSONArray("subtitles").toString())
                .putString("danmaku:" + video.id, video.danmaku).commit()) throw new IOException("Cannot save movie settings");
        if (state.has("danmakuSelected") && !context.getSharedPreferences("offline_playback", 0).edit()
                .putString("danmakuSelected:" + video.id, state.getString("danmakuSelected")).commit()) throw new IOException("Cannot save comment selection");
        AppDatabase.get().runInTransaction(() -> {
            History existing = AppDatabase.get().getHistoryDao().find(incoming.getCid(), incoming.getKey());
            if (existing == null || incoming.getCreateTime() > existing.getCreateTime()) incoming.save();
            Set<Integer> local = new HashSet<>();
            for (Track track : Track.find(incoming.getKey())) local.add(track.getType());
            JSONArray tracks = state.optJSONArray("tracks");
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject item = tracks.optJSONObject(i);
                if (local.contains(item.optInt("type"))) continue;
                Track track = new Track(item.optInt("type"), item.optString("name"), item.optString("format"));
                track.setSelected(item.optBoolean("selected")); track.key(incoming.getKey()).save();
            }
        });
        if (state.has("danmakuOffset")) main(() -> {
            CustomConfigManager config = CustomConfigManager.get();
            if (!config.hasHistoryOffset(incoming.getKey())) config.addOrUpdateHistory(incoming.getKey(), state.optLong("danmakuOffset"));
            return null;
        });
    }

    private static <T> T main(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) task.run(); else App.post(task);
        return task.get();
    }
}

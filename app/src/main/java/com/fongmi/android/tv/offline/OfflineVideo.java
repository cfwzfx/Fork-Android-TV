package com.fongmi.android.tv.offline;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Immutable download metadata. Explicit JSON keeps persisted data stable across R8 and upgrades. */
public final class OfflineVideo {
    public final String id;
    public final String title;
    public final String episode;
    public final String line;
    public final String url;
    public final String mimeType;
    public final Map<String, String> headers;
    public final String history;
    public final String danmaku;
    public final String source;

    public OfflineVideo(String identity, String title, String episode, String line, String url,
                        String mimeType, Map<String, String> headers) {
        this(identity, title, episode, line, url, mimeType, headers, "", "[]");
    }

    public OfflineVideo(String identity, String title, String episode, String line, String url,
                        String mimeType, Map<String, String> headers, String history, String danmaku) {
        this(identity, title, episode, line, url, mimeType, headers, history, danmaku, "");
    }

    public OfflineVideo(String identity, String title, String episode, String line, String url,
                        String mimeType, Map<String, String> headers, String history, String danmaku, String source) {
        this(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),
                title, episode, line, url, mimeType, headers, history, danmaku, source, true);
    }

    private OfflineVideo(String id, String title, String episode, String line, String url,
                         String mimeType, Map<String, String> headers, String history, String danmaku, String source, boolean persisted) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.episode = episode == null ? "" : episode;
        this.line = line == null ? "" : line;
        this.url = url;
        this.mimeType = mimeType;
        this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
        this.source = source == null ? "" : source;
        this.history = history == null ? "" : history;
        this.danmaku = danmaku == null ? "[]" : danmaku;
    }

    static String identity(com.fongmi.android.tv.bean.History history) {
        return new org.json.JSONArray().put(history.getKey()).put(history.getVodFlag())
                .put(history.getEpisodeUrl()).toString();
    }

    String episodeKey() {
        com.fongmi.android.tv.bean.History original = OfflineHistory.original(this);
        return original == null || original.getEpisodeUrl().isEmpty() ? "id:" + id : identity(original);
    }

    OfflineVideo withMimeType(String mime) {
        return new OfflineVideo(id, title, episode, line, url, mime, headers, history, danmaku, source, true);
    }

    OfflineVideo withPlaybackState(String history, String danmaku) {
        return new OfflineVideo(id, title, episode, line, url, mimeType, headers, history, danmaku, source, true);
    }

    public String groupKey() {
        try {
            String original = new JSONObject(history).optString("key");
            if (!original.isEmpty()) return "source:" + original;
        } catch (JSONException ignored) {}
        return title.isEmpty() ? "video:" + id : "title:" + title;
    }

    public byte[] encode() {
        try {
            JSONObject json = new JSONObject();
            json.put("id", id).put("title", title).put("episode", episode).put("line", line);
            json.put("url", url).put("mime", mimeType).put("headers", new JSONObject(headers));
            json.put("history", history).put("danmaku", danmaku).put("source", source);
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid offline metadata", e);
        }
    }

    public static OfflineVideo decode(byte[] data) {
        try {
            JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            Map<String, String> headers = new HashMap<>();
            JSONObject values = json.optJSONObject("headers");
            if (values != null) {
                Iterator<String> keys = values.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    headers.put(key, values.getString(key));
                }
            }
            return new OfflineVideo(json.getString("id"), json.optString("title"),
                    json.optString("episode"), json.optString("line"), json.getString("url"),
                    json.has("mime") ? json.getString("mime") : null, headers,
                    json.optString("history"), json.optString("danmaku", "[]"), json.optString("source"), true);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid offline metadata", e);
        }
    }
}

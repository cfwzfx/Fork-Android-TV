package com.fongmi.android.tv.offline;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.exoplayer.offline.Download;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.utils.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** External subtitles use the same per-download SimpleCache as video, including LAN imports. */
public final class OfflineSubtitles {
    private OfflineSubtitles() {}

    static JSONArray saved(Context context, String id) throws IOException {
        try { return new JSONArray(context.getSharedPreferences("offline_playback", 0).getString("subtitles:" + id, "[]")); }
        catch (Exception error) { throw new IOException("Subtitle metadata is invalid", error); }
    }

    public static MediaItem attach(Context context, String id, MediaItem item) {
        try {
            List<Sub> subtitles = new ArrayList<>();
            JSONArray saved = saved(context, id);
            for (int i = 0; i < saved.length(); i++) subtitles.add(App.gson().fromJson(saved.getJSONObject(i).toString(), Sub.class));
            return item.buildUpon().setSubtitleConfigurations(MediaItemFactory.buildSubtitleConfigs(subtitles)).setTag(id).build();
        } catch (Exception error) { return item.buildUpon().setTag(id).build(); }
    }

    static void capture(Context context, OfflineCache cache, OfflineVideo video) {
        if (video.source.isEmpty()) return;
        try {
            Result source = Result.objectFrom(video.source);
            if (source == null) return;
            boolean failed = false;
            for (Sub sub : source.getSubs()) {
                try { save(context, cache, video, sub, false); }
                catch (Exception error) { failed = true; }
            }
            if (failed) App.post(() -> com.fongmi.android.tv.utils.Notify.show(com.fongmi.android.tv.R.string.offline_subtitle_error));
        } catch (RuntimeException ignored) {}
    }

    static synchronized Sub save(Context context, OfflineCache cache, OfflineVideo video, Sub sub, boolean selected) throws IOException {
        if (sub == null || sub.isEmpty()) throw new IOException("No subtitle");
        String origin = sub.getUrl();
        String uri = "http://offline.subtitle/" + UUID.nameUUIDFromBytes(origin.getBytes(StandardCharsets.UTF_8)) + ".sub";
        String key = video.id + ":" + uri;
        File temporary = null, destination = null;
        CacheSpan hole = null;
        try {
            long length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.storage().getContentMetadata(key));
            if (length <= 0 || !cache.storage().isCached(key, 0, length)) {
                temporary = File.createTempFile("offline-subtitle-", ".tmp", context.getCacheDir());
                DataSource input = new DefaultDataSource.Factory(context, cache.subtitleNetwork(video)).createDataSource();
                long bytes = 0;
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    try {
                        long expected = input.open(new DataSpec.Builder().setUri(com.fongmi.android.tv.utils.UrlUtil.uri(origin)).build());
                        byte[] buffer = new byte[8192];
                        for (int count; (count = input.read(buffer, 0, buffer.length)) != -1;) {
                            bytes += count;
                            output.write(buffer, 0, count);
                        }
                        if (bytes == 0 || (expected >= 0 && expected != bytes)) throw new IOException("Subtitle is incomplete");
                    } finally { input.close(); }
                }
                byte[] prefix = new byte[(int) Math.min(512, temporary.length())];
                try (var file = new java.io.FileInputStream(temporary)) { file.read(prefix); }
                String start = new String(prefix, StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.ROOT);
                if (start.startsWith("<html") || start.startsWith("<!doctype") || start.startsWith("{\"error") || start.startsWith("pk\u0003\u0004"))
                    throw new IOException("Source did not return subtitle text");
                hole = cache.storage().startReadWriteNonBlocking(key, 0, bytes);
                if (hole == null || hole.isCached) throw new IOException("Subtitle cache is busy");
                destination = cache.storage().startFile(key, 0, bytes);
                java.nio.file.Files.copy(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                cache.storage().commitFile(destination, bytes);
                destination = null;
                cache.storage().applyContentMetadataMutations(key,
                        ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), bytes));
            }
            JSONArray previous = saved(context, video.id), next = new JSONArray();
            JSONObject descriptor = new JSONObject(sub.toString()).put("url", uri).put("origin", sub.getOrigin())
                    .put("name", sub.getName()).put("lang", sub.getLang()).put("format", MediaItemFactory.buildSubConfig(sub).mimeType);
            if (selected) { descriptor.put("flag", androidx.media3.common.C.SELECTION_FLAG_FORCED); next.put(descriptor); }
            boolean exists = false;
            for (int i = 0; i < previous.length(); i++) {
                JSONObject value = previous.getJSONObject(i);
                if (value.optString("url").equals(uri)) { exists = true; if (selected) continue; }
                if (selected && (value.optInt("flag") & androidx.media3.common.C.SELECTION_FLAG_FORCED) != 0)
                    value.put("flag", androidx.media3.common.C.SELECTION_FLAG_AUTOSELECT);
                next.put(value);
            }
            if (!selected && !exists) next.put(descriptor);
            if (!context.getSharedPreferences("offline_playback", 0).edit().putString("subtitles:" + video.id, next.toString()).commit())
                throw new IOException("Cannot save subtitle metadata");
            return App.gson().fromJson(descriptor.toString(), Sub.class);
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Cannot save subtitle", error); }
        finally {
            if (hole != null) cache.storage().releaseHoleSpan(hole);
            if (temporary != null) temporary.delete();
            if (destination != null) destination.delete();
        }
    }

    public static void select(Context context, String id, Sub sub, Consumer<Sub> success, Consumer<Exception> failure) {
        Context application = context.getApplicationContext();
        Task.execute(() -> {
            try {
                OfflineCache cache = OfflineIntegration.get(application);
                Download download = cache.find(id);
                if (download == null || download.state != Download.STATE_COMPLETED) throw new IOException("Cache was removed");
                Sub local = save(application, cache, OfflineVideo.decode(download.request.data), sub, true);
                App.post(() -> success.accept(local));
            } catch (Exception error) { App.post(() -> failure.accept(error)); }
        });
    }

    public static void saveForMedia(Context context, String mediaId, String url, Sub sub) {
        Context application = context.getApplicationContext();
        Task.execute(() -> {
            try {
                OfflineCache cache = OfflineCache.peek();
                if (cache == null) return;
                for (Download download : cache.list()) {
                    if (download.state == Download.STATE_REMOVING) continue;
                    OfflineVideo video = OfflineVideo.decode(download.request.data);
                    com.fongmi.android.tv.bean.History original = OfflineHistory.original(video);
                    if (original != null && mediaId.equals(original.getKey()) && url.equals(video.url)) save(application, cache, video, sub, true);
                }
            } catch (Exception error) { App.post(() -> com.fongmi.android.tv.utils.Notify.show(com.fongmi.android.tv.R.string.offline_subtitle_error)); }
        });
    }
}

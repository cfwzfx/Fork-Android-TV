package com.fongmi.android.tv.offline;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.StreamKey;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadProgress;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.WritableDownloadIndex;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.History;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Wire format is separate from storage: imports use Media3's normal span and download APIs. */
public final class OfflineCacheTransfer {
    public static final int VERSION = 2;
    private final Context context;
    private final OfflineCache cache;

    public OfflineCacheTransfer(Context context, OfflineCache cache) {
        this.context = context.getApplicationContext();
        this.cache = cache;
    }

    public static final class Snapshot {
        public final JSONObject manifest;
        private final List<File> files;
        Snapshot(JSONObject manifest, List<File> files) { this.manifest = manifest; this.files = files; }

        public InputStream open() throws IOException {
            JSONArray spans = manifest.optJSONArray("spans");
            return new InputStream() {
                int index;
                long remaining;
                InputStream current;
                boolean closed;
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
                }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    if (closed) throw new IOException("Transfer closed");
                    if (count == 0) return 0;
                    while (current == null || remaining == 0) {
                        if (current != null) current.close();
                        current = null;
                        if (index == files.size()) return -1;
                        File file = files.get(index);
                        remaining = spans.optJSONObject(index++).optLong("length");
                        if (!file.isFile() || file.length() != remaining) throw new IOException("Source cache changed");
                        current = new FileInputStream(file);
                    }
                    int read = current.read(bytes, offset, (int) Math.min(count, remaining));
                    if (read < 0) throw new IOException("Source cache incomplete");
                    remaining -= read;
                    return read;
                }
                @Override public void close() throws IOException { closed = true; if (current != null) current.close(); }
            };
        }
    }

    public Snapshot export(String id, BooleanSupplier cancelled) throws IOException {
        try {
            Download download = cache.find(id);
            if (download == null || download.state != Download.STATE_COMPLETED) throw new IOException("Only completed caches can be sent");
            cache.verify(download);
            DownloadRequest request = download.request;
            if (request.keySetId != null || request.byteRange != null || request.timeRange != null)
                throw new IOException("Protected or partial caches cannot be sent");
            OfflineVideo video = OfflinePlaybackState.snapshot(context, OfflineVideo.decode(request.data));
            History history = OfflineHistory.original(video);
            if (history == null || history.getEpisodeUrl().isEmpty()) throw new IOException("Original source information is missing");
            Config config = Config.find(history.getCid());
            if (config == null || config.getType() != 0 || !http(config.getUrl()))
                throw new IOException("The source configuration needs a shared HTTP address");
            JSONObject manifest = new JSONObject().put("version", VERSION).put("video", new JSONObject(new String(video.encode(), StandardCharsets.UTF_8)))
                    .put("configUrl", config.getUrl()).put("configName", config.getName()).put("uri", request.uri.toString())
                    .put("mime", request.mimeType).put("customKey", request.customCacheKey).put("contentLength", download.contentLength);
            JSONArray streams = new JSONArray();
            for (StreamKey key : request.streamKeys) streams.put(new JSONArray().put(key.periodIndex).put(key.groupIndex).put(key.streamIndex));
            manifest.put("streams", streams);
            manifest.put("playback", OfflinePlaybackState.export(context, video));
            List<File> files = new ArrayList<>();
            JSONArray spans = new JSONArray(), resources = new JSONArray();
            long total = 0, mediaBytes = 0;
            SimpleCache storage = cache.storage();
            for (String key : storage.getKeys()) {
                if (!key.startsWith(video.id + ":")) continue;
                String suffix = key.substring(video.id.length() + 1);
                ContentMetadata metadata = storage.getContentMetadata(key);
                JSONObject resource = new JSONObject().put("key", suffix).put("length", ContentMetadata.getContentLength(metadata));
                Uri redirected = ContentMetadata.getRedirectedUri(metadata);
                if (redirected != null) resource.put("redirect", redirected.toString());
                resources.put(resource);
                for (CacheSpan span : storage.getCachedSpans(key)) {
                    if (span.file == null || !span.file.isFile() || span.file.length() != span.length) throw new IOException("Source cache incomplete");
                    spans.put(new JSONObject().put("key", suffix).put("position", span.position).put("length", span.length)
                            .put("sha256", hash(span.file, cancelled)));
                    files.add(span.file);
                    mediaBytes = Math.addExact(mediaBytes, span.length);
                }
            }
            if (mediaBytes == 0 || mediaBytes < download.getBytesDownloaded()) throw new IOException("Source cache incomplete");
            total = mediaBytes;
            Set<String> comments = new HashSet<>();
            for (Danmaku comment : Danmaku.arrayFrom(video.danmaku)) {
                String name = UUID.nameUUIDFromBytes(comment.getUrl().getBytes(StandardCharsets.UTF_8)) + ".xml";
                File saved = OfflineDanmakuCache.isLocal(comment.getUrl()) ? OfflineDanmakuCache.saveLocal(comment.getUrl()) : OfflineDanmakuCache.saved(comment.getUrl());
                if (!comments.add(name) || !saved.isFile() || saved.length() == 0) continue;
                spans.put(new JSONObject().put("comment", name).put("length", saved.length()).put("sha256", hash(saved, cancelled)));
                files.add(saved);
                total = Math.addExact(total, saved.length());
            }
            manifest.put("resources", resources).put("spans", spans).put("bytes", total).put("mediaBytes", mediaBytes)
                    .put("downloadedBytes", download.getBytesDownloaded());
            // Run the same structural validation before making the invitation available.
            validate(manifest);
            return new Snapshot(manifest, files);
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Cannot export this cache", error); }
    }

    public final class Import implements AutoCloseable {
        public final OfflineVideo video;
        public final long bytes;
        private final JSONObject manifest;
        private final DownloadRequest request;
        private boolean finished, closed;
        private final List<File> temporaryComments = new ArrayList<>();
        private final List<File> savedComments = new ArrayList<>();
        private final List<File> newlySavedComments = new ArrayList<>();
        private final android.content.SharedPreferences preferences = context.getSharedPreferences("offline_playback", 0);
        private final String previousSubtitles, previousComments, previousSelection;

        Import(JSONObject manifest, OfflineVideo video, DownloadRequest request) {
            this.manifest = manifest; this.video = video; this.request = request;
            bytes = manifest.optLong("bytes");
            previousSubtitles = preferences.getString("subtitles:" + video.id, null);
            previousComments = preferences.getString("danmaku:" + video.id, null);
            previousSelection = preferences.getString("danmakuSelected:" + video.id, null);
        }

        public Download receive(InputStream input, BooleanSupplier cancelled, LongConsumer progress) throws IOException {
            if (closed) throw new IOException("Import is closed");
            try {
                JSONArray spans = manifest.getJSONArray("spans");
                SimpleCache storage = cache.storage();
                long received = 0;
                for (int i = 0; i < spans.length(); i++) {
                    check(cancelled);
                    JSONObject span = spans.getJSONObject(i);
                    long length = span.getLong("length");
                    CacheSpan hole = null;
                    File file = null;
                    try {
                        if (span.has("comment")) {
                            File directory = new File(context.getFilesDir(), "danmaku_saved");
                            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot write comments");
                            file = File.createTempFile("sync-", ".tmp", directory);
                            temporaryComments.add(file);
                            savedComments.add(new File(directory, span.getString("comment")));
                        } else {
                            String key = video.id + ":" + span.getString("key");
                            hole = storage.startReadWriteNonBlocking(key, span.getLong("position"), length);
                            if (hole == null || hole.isCached) throw new IOException("Cache is busy");
                            file = storage.startFile(key, span.getLong("position"), length);
                        }
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        try (FileOutputStream output = new FileOutputStream(file)) {
                            byte[] buffer = new byte[64 * 1024];
                            long remaining = length;
                            while (remaining > 0) {
                                check(cancelled);
                                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (count < 0) throw new IOException("Transfer interrupted");
                                output.write(buffer, 0, count);
                                digest.update(buffer, 0, count);
                                remaining -= count;
                                received += count;
                                progress.accept(received);
                            }
                            output.getFD().sync();
                        }
                        if (!hex(digest.digest()).equals(span.getString("sha256"))) throw new IOException("Cache checksum mismatch");
                        if (hole != null) { storage.commitFile(file, length); file = null; }
                    } finally {
                        if (hole != null) storage.releaseHoleSpan(hole);
                        if (file != null && !temporaryComments.contains(file)) file.delete();
                    }
                }
                if (input.read() != -1) throw new IOException("Unexpected cache data");
                check(cancelled);
                JSONArray resources = manifest.getJSONArray("resources");
                for (int i = 0; i < resources.length(); i++) {
                    JSONObject resource = resources.getJSONObject(i);
                    ContentMetadataMutations mutations = new ContentMetadataMutations();
                    ContentMetadataMutations.setContentLength(mutations, resource.getLong("length"));
                    if (resource.has("redirect")) ContentMetadataMutations.setRedirectedUri(mutations, Uri.parse(resource.getString("redirect")));
                    storage.applyContentMetadataMutations(video.id + ":" + resource.getString("key"), mutations);
                }
                for (int i = 0; i < temporaryComments.size(); i++) {
                    // A failed or duplicate import never overwrites an existing saved comment file.
                    if (!savedComments.get(i).isFile()) {
                        if (!temporaryComments.get(i).renameTo(savedComments.get(i))) throw new IOException("Cannot save comments");
                        newlySavedComments.add(savedComments.get(i));
                    }
                }
                DownloadProgress state = new DownloadProgress();
                state.bytesDownloaded = manifest.getLong("downloadedBytes");
                state.percentDownloaded = 100;
                long now = System.currentTimeMillis();
                Download result = new Download(request, Download.STATE_COMPLETED, now, now,
                        manifest.getLong("contentLength"), 0, Download.FAILURE_REASON_NONE, state);
                OfflineCacheIntegrity.verify(context, storage, request, cache.dataSource(video, true));
                OfflinePlaybackState.apply(context, video, manifest.getJSONObject("playback"));
                ((WritableDownloadIndex) cache.manager().getDownloadIndex()).putDownload(result);
                finished = true;
                return result;
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("Cache import failed", error); }
            finally { close(); }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                if (!finished) removeOrphan(video.id);
                if (!finished) for (File file : newlySavedComments) file.delete();
                if (!finished) preferences.edit().putString("subtitles:" + video.id, previousSubtitles)
                        .putString("danmaku:" + video.id, previousComments).putString("danmakuSelected:" + video.id, previousSelection).commit();
                for (File file : temporaryComments) file.delete();
            } finally { cache.releaseImport(video); }
        }
    }

    /** Returns null for any existing task, including completed, paused and currently preparing. */
    public Import prepare(JSONObject manifest) throws IOException {
        try {
            validate(manifest);
            OfflineVideo original = OfflineVideo.decode(manifest.getJSONObject("video").toString().getBytes(StandardCharsets.UTF_8));
            History history = OfflineHistory.original(original);
            // Local database IDs are not portable. Match by the exact configuration URL without switching the active source.
            Config config;
            synchronized (OfflineCacheTransfer.class) {
                config = Config.find(manifest.getString("configUrl"), manifest.optString("configName"), 0);
                // Another configuration flow may have inserted the unique URL concurrently.
                if (config.getId() <= 0) config = Config.find(manifest.getString("configUrl"), 0);
                if (config.getId() <= 0) throw new IOException("Cannot map source configuration");
            }
            history.cid(config.getId());
            OfflineVideo video = new OfflineVideo(OfflineVideo.identity(history), original.title, original.episode, original.line,
                    original.url, original.mimeType, original.headers, history.toString(), original.danmaku, original.source);
            List<StreamKey> keys = new ArrayList<>();
            JSONArray streams = manifest.getJSONArray("streams");
            for (int i = 0; i < streams.length(); i++) {
                JSONArray key = streams.getJSONArray(i);
                keys.add(new StreamKey(key.getInt(0), key.getInt(1), key.getInt(2)));
            }
            DownloadRequest request = new DownloadRequest.Builder(video.id, Uri.parse(manifest.getString("uri")))
                    .setMimeType(manifest.optString("mime", null)).setCustomCacheKey(manifest.optString("customKey", null))
                    .setStreamKeys(keys).setData(video.encode()).build();
            if (!cache.reserveImport(video)) return null;
            try {
                removeOrphan(video.id);
                if (context.getFilesDir().getUsableSpace() < Math.addExact(manifest.getLong("bytes"), 32 * 1024 * 1024))
                    throw new IOException("Not enough storage space");
                return new Import(manifest, video, request);
            } catch (Exception error) { cache.releaseImport(video); throw error; }
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Invalid cache invitation", error); }
    }

    private void removeOrphan(String id) {
        for (String key : cache.storage().getKeys()) if (key.startsWith(id + ":")) cache.storage().removeResource(key);
    }

    private static void validate(JSONObject manifest) throws Exception {
        if (manifest.getInt("version") != VERSION) throw new IOException("The other device needs the same cache sync version");
        if (!http(manifest.getString("configUrl"))) throw new IOException("Invalid source configuration");
        OfflineVideo video = OfflineVideo.decode(manifest.getJSONObject("video").toString().getBytes(StandardCharsets.UTF_8));
        History history = OfflineHistory.original(video);
        if (history == null || history.getEpisodeUrl().isEmpty() || !http(video.url)
                || !video.url.equals(manifest.getString("uri"))) throw new IOException("Invalid original episode");
        JSONArray streams = manifest.getJSONArray("streams"), spans = manifest.getJSONArray("spans"), resources = manifest.getJSONArray("resources");
        if (spans.length() == 0 || spans.length() > 100000 || resources.length() > 100000 || streams.length() > 10000)
            throw new IOException("Invalid cache resource count");
        for (int i = 0; i < streams.length(); i++) {
            JSONArray key = streams.getJSONArray(i);
            if (key.length() != 3 || key.getInt(0) < 0 || key.getInt(1) < 0 || key.getInt(2) < 0) throw new IOException("Invalid track selection");
        }
        Set<String> keys = new HashSet<>(), comments = new HashSet<>();
        java.util.Map<String, Long> ends = new java.util.HashMap<>();
        java.util.Map<String, Long> covered = new java.util.HashMap<>();
        for (Danmaku comment : Danmaku.arrayFrom(video.danmaku)) comments.add(UUID.nameUUIDFromBytes(comment.getUrl().getBytes(StandardCharsets.UTF_8)) + ".xml");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.getJSONObject(i);
            if (!keys.add(resource.getString("key")) || resource.getLong("length") < C.LENGTH_UNSET) throw new IOException("Invalid resource");
            if (resource.has("redirect") && !http(resource.getString("redirect"))) throw new IOException("Invalid resource redirect");
        }
        long bytes = 0, media = 0;
        OfflinePlaybackState.validate(manifest.getJSONObject("playback"), keys);
        if (manifest.getJSONObject("playback").has("danmakuSelected")) {
            String selected = manifest.getJSONObject("playback").getString("danmakuSelected");
            if (!selected.isEmpty() && Danmaku.arrayFrom(video.danmaku).stream().noneMatch(item -> item.getUrl().equals(selected)))
                throw new IOException("Selected comments are missing");
        }
        boolean primary = false;
        Set<String> commentFiles = new HashSet<>();
        for (int i = 0; i < spans.length(); i++) {
            JSONObject span = spans.getJSONObject(i);
            long length = span.getLong("length");
            if (length <= 0 || !span.getString("sha256").matches("[0-9a-f]{64}")) throw new IOException("Invalid cache span");
            bytes = Math.addExact(bytes, length);
            if (span.has("comment")) {
                String name = span.getString("comment");
                if (!comments.contains(name) || !commentFiles.add(name)) throw new IOException("Invalid saved comment");
            } else {
                String key = span.getString("key");
                long position = span.getLong("position");
                if (!keys.contains(key) || position < ends.getOrDefault(key, 0L)) throw new IOException("Invalid cache span position");
                ends.put(key, Math.addExact(position, length));
                covered.put(key, Math.addExact(covered.getOrDefault(key, 0L), length));
                media = Math.addExact(media, length);
                primary |= key.equals(manifest.optString("customKey", video.url)) && position == 0;
            }
        }
        JSONArray subtitles = manifest.getJSONObject("playback").getJSONArray("subtitles");
        for (int i = 0; i < subtitles.length(); i++) {
            String url = subtitles.getJSONObject(i).getString("url");
            for (int j = 0; j < resources.length(); j++) {
                JSONObject resource = resources.getJSONObject(j);
                if (!url.equals(resource.getString("key"))) continue;
                long length = resource.getLong("length");
                if (length <= 0 || covered.getOrDefault(url, 0L) != length || ends.getOrDefault(url, 0L) != length)
                    throw new IOException("Subtitle cache is incomplete");
            }
        }
        if (!primary || media == 0 || media != manifest.getLong("mediaBytes") || bytes != manifest.getLong("bytes")
                || manifest.getLong("downloadedBytes") < 0 || manifest.getLong("downloadedBytes") > media
                || manifest.getLong("contentLength") < C.LENGTH_UNSET) throw new IOException("Incomplete cache manifest");
    }

    private static boolean http(String url) { return url != null && (url.startsWith("http://") || url.startsWith("https://")); }
    private static void check(BooleanSupplier cancelled) throws IOException { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled"); }
    private static String hash(File file, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) { check(cancelled); digest.update(buffer, 0, count); }
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
}

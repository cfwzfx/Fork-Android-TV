package com.fongmi.android.tv.offline;

import android.content.Context;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.Downloader;
import java.io.IOException;

/** Reuses Media3's selected segment/range enumeration with no network or cache writes. */
final class OfflineCacheIntegrity {
    static final class Incomplete extends IOException {
        Incomplete(String reason) { super(reason); }
        Incomplete(Exception cause) { super("Cached media is incomplete", cause); }
    }

    static void verify(Context context, SimpleCache storage, DownloadRequest request, CacheDataSource.Factory reader)
            throws IOException, InterruptedException {
        try {
            OfflineVideo video = OfflineVideo.decode(request.data);
            for (String key : storage.getKeys()) {
                if (!key.startsWith(video.id + ":")) continue;
                for (var span : storage.getCachedSpans(key))
                    if (span.file == null || !span.file.isFile() || span.file.length() != span.length)
                        throw new Incomplete("Cached file is missing or truncated");
            }
            var subtitles = OfflineSubtitles.saved(context, video.id);
            for (int i = 0; i < subtitles.length(); i++) {
                String key = video.id + ":" + subtitles.getJSONObject(i).getString("url");
                long length = ContentMetadata.getContentLength(storage.getContentMetadata(key));
                if (length <= 0 || !storage.isCached(key, 0, length)) throw new Incomplete("Cached subtitle is incomplete");
            }
            // A missing small segment must not be masked by unrelated manifest/subtitle bytes.
            new DefaultDownloaderFactory(reader, Runnable::run).createDownloader(request).download(null);
            if (androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(request.uri, request.mimeType) == androidx.media3.common.C.CONTENT_TYPE_OTHER)
                verifyMp4(storage, request, reader, video.id);
        } catch (InterruptedException error) { throw error; }
        catch (Incomplete error) { throw error; }
        catch (Exception error) { throw new Incomplete(error); }
    }

    private static byte[] header(DownloadRequest request, CacheDataSource.Factory reader, long position, int length) throws IOException {
        var source = reader.createDataSource();
        byte[] bytes = new byte[length];
        try {
            source.open(new androidx.media3.datasource.DataSpec.Builder().setUri(request.uri).setKey(request.customCacheKey)
                    .setPosition(position).setLength(length).build());
            int read = 0;
            while (read < bytes.length) {
                int count = source.read(bytes, read, bytes.length - read);
                if (count == -1) throw new Incomplete("Container header is truncated");
                read += count;
            }
            return bytes;
        } finally { source.close(); }
    }

    private static void verifyMp4(SimpleCache storage, DownloadRequest request, CacheDataSource.Factory reader, String id) throws IOException {
        String key = id + ":" + (request.customCacheKey == null ? request.uri : request.customCacheKey);
        long length = ContentMetadata.getContentLength(storage.getContentMetadata(key));
        if (length < 8) return;
        byte[] first = header(request, reader, 0, 8);
        if (!new String(first, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp")) return;
        for (long position = 0, boxes = 0; position < length; boxes++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if (boxes >= 100000 || length - position < 8) throw new Incomplete("Invalid MP4 container boundary");
            byte[] bytes = header(request, reader, position, 8);
            long size = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes).getInt());
            int minimum = 8;
            if (size == 1) {
                if (length - position < 16) throw new Incomplete("Extended MP4 header is truncated");
                size = java.nio.ByteBuffer.wrap(header(request, reader, position + 8, 8)).getLong(); minimum = 16;
            }
            if (size == 0) size = length - position;
            if (size < minimum || size > length - position) throw new Incomplete("Declared MP4 data extends past cached EOF");
            position += size;
        }
    }

    static Downloader checked(Context context, SimpleCache storage, DownloadRequest request,
                              CacheDataSource.Factory reader, Downloader download) {
        return new Downloader() {
            private volatile boolean cancelled;
            @Override public void download(ProgressListener listener) throws IOException, InterruptedException {
                download.download(listener);
                if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                verify(context, storage, request, reader);
                if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            }
            @Override public void cancel() { cancelled = true; download.cancel(); }
            @Override public void remove() { download.remove(); }
        };
    }
}

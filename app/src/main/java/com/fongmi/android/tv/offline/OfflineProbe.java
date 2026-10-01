package com.fongmi.android.tv.offline;

import android.net.Uri;

import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import com.fongmi.android.tv.R;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** A bounded GET, with the same headers as the download, before creating a task. */
final class OfflineProbe {
    static final class InvalidContent extends IOException {}

    static OfflineVideo inspect(OfflineVideo video, OfflineCache.NetworkFactory network) throws IOException {
        HttpDataSource source = network.create(video.headers).createDataSource();
        byte[] prefix = new byte[8192];
        int size = 0;
        long length;
        try {
            length = source.open(new DataSpec.Builder().setUri(Uri.parse(video.url)).build());
            while (size < prefix.length) {
                int count = source.read(prefix, size, prefix.length - size);
                if (count == -1) break;
                size += count;
            }
        } finally {
            source.close();
        }
        if (size == 0) throw new InvalidContent();
        String text = new String(prefix, 0, size, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        String lower = text.toLowerCase(Locale.ROOT);
        String mime = video.mimeType;
        if (text.startsWith("#EXTM3U")) mime = MimeTypes.APPLICATION_M3U8;
        else if (lower.matches("(?s).*<(?:[a-z0-9_]+:)?mpd(?:\\s|>).*")) mime = MimeTypes.APPLICATION_MPD;
        else if (lower.contains("<html") || lower.contains("<!doctype html") || lower.startsWith("<pre")
                || lower.startsWith("{") || lower.startsWith("[")) throw new InvalidContent();
        else if (MimeTypes.APPLICATION_M3U8.equals(mime) || MimeTypes.APPLICATION_MPD.equals(mime)
                || Uri.parse(video.url).getPath().endsWith(".m3u8") || Uri.parse(video.url).getPath().endsWith(".mpd"))
            throw new InvalidContent();
        else if (!isMedia(prefix, size, length)) throw new InvalidContent();
        return video.withMimeType(mime);
    }

    private static boolean isMedia(byte[] prefix, int size, long length) {
        androidx.media3.extractor.Extractor[] extractors = new androidx.media3.extractor.DefaultExtractorsFactory().createExtractors();
        try {
            for (androidx.media3.extractor.Extractor extractor : extractors) {
                androidx.media3.datasource.ByteArrayDataSource data = new androidx.media3.datasource.ByteArrayDataSource(
                        java.util.Arrays.copyOf(prefix, size));
                try {
                    data.open(new DataSpec.Builder().setUri("memory://probe").build());
                    if (extractor.sniff(new androidx.media3.extractor.DefaultExtractorInput(data, 0, length))) return true;
                } catch (IOException ignored) {
                    // A sniff must recognize the container; never enqueue arbitrary HTML/text as media.
                } finally { data.close(); }
            }
            return false;
        } finally {
            for (androidx.media3.extractor.Extractor extractor : extractors) extractor.release();
        }
    }

    static int failure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidContent || cause instanceof androidx.media3.common.ParserException) return R.string.offline_invalid_content;
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) return R.string.offline_http_error;
        }
        return R.string.offline_prepare_error;
    }
}

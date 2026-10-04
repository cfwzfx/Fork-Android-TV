package com.fongmi.android.tv.offline;

import android.net.Uri;

import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ForwardingExtractorInput;

import com.fongmi.android.tv.R;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** A bounded GET, with the same headers as the download, before creating a task. */
final class OfflineProbe {
    static final class InvalidContent extends IOException {}

    static OfflineVideo inspect(OfflineVideo video, OfflineCache.NetworkFactory network) throws IOException {
        HttpDataSource source = network.create(video.headers).createDataSource();
        try {
            long length = source.open(new DataSpec.Builder().setUri(Uri.parse(video.url)).build());
            ExtractorInput input = new BoundedInput(new DefaultExtractorInput(source, 0, length));
            byte[] prefix = new byte[8192];
            int size = 0;
            while (size < prefix.length) {
                int count = input.peek(prefix, size, prefix.length - size);
                if (count == -1) break;
                size += count;
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
            else if (!isMedia(input)) throw new InvalidContent();
            return video.withMimeType(mime);
        } finally {
            source.close();
        }
    }

    private static boolean isMedia(ExtractorInput input) throws IOException {
        androidx.media3.extractor.Extractor[] extractors = new androidx.media3.extractor.DefaultExtractorsFactory().createExtractors();
        ProbeLimit limit = null;
        try {
            for (androidx.media3.extractor.Extractor extractor : extractors) {
                input.resetPeekPosition();
                try {
                    if (extractor.sniff(input)) return true;
                } catch (ProbeLimit error) {
                    limit = error;
                } catch (EOFException | androidx.media3.common.ParserException ignored) {
                    // This is the real end of the response, not an artificial 8KB prefix boundary.
                }
            }
            // A bounded probe that could not finish is not evidence of an invalid/expired video.
            if (limit != null) throw limit;
            return false;
        } finally {
            for (androidx.media3.extractor.Extractor extractor : extractors) extractor.release();
        }
    }

    private static final class ProbeLimit extends IOException {}

    private static final class BoundedInput extends ForwardingExtractorInput {
        // Real 4K MP4 sample tables can exceed 1MB even when the file is fully playable.
        private static final int MAX_BYTES = 8 * 1024 * 1024;

        BoundedInput(ExtractorInput input) { super(input); }

        private void check(int length) throws ProbeLimit {
            // Check before Media3 enlarges its peek buffer, including malformed huge MP4 boxes.
            if (length < 0 || getPeekPosition() + length > MAX_BYTES) throw new ProbeLimit();
        }

        @Override public int peek(byte[] target, int offset, int length) throws IOException {
            check(length);
            return super.peek(target, offset, length);
        }

        @Override public boolean peekFully(byte[] target, int offset, int length, boolean allowEnd) throws IOException {
            check(length);
            return super.peekFully(target, offset, length, allowEnd);
        }

        @Override public void peekFully(byte[] target, int offset, int length) throws IOException {
            check(length);
            super.peekFully(target, offset, length);
        }

        @Override public boolean advancePeekPosition(int length, boolean allowEnd) throws IOException {
            check(length);
            return super.advancePeekPosition(length, allowEnd);
        }

        @Override public void advancePeekPosition(int length) throws IOException {
            check(length);
            super.advancePeekPosition(length);
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

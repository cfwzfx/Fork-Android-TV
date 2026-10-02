package com.fongmi.android.tv.offline;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.EOFException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static androidx.media3.datasource.HttpDataSource.HttpDataSourceException.TYPE_OPEN;
import static androidx.media3.datasource.HttpDataSource.HttpDataSourceException.TYPE_READ;

/** Some cloud gateways cap each 206 body. Its Content-Length is not the full resource length. */
final class OfflineHttpDataSource implements HttpDataSource {
    private static final Pattern RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private final HttpDataSource input;
    private DataSpec original;
    private long read, length, chunkRemaining, total = C.LENGTH_UNSET;
    private String etag;
    private boolean partial;

    static Factory factory(Factory upstream) {
        return new Factory() {
            @Override public HttpDataSource createDataSource() { return new OfflineHttpDataSource(upstream.createDataSource()); }
            @Override public Factory setDefaultRequestProperties(Map<String, String> values) {
                upstream.setDefaultRequestProperties(values); return this;
            }
        };
    }

    private OfflineHttpDataSource(HttpDataSource input) { this.input = input; }

    @Override public long open(DataSpec spec) throws HttpDataSourceException {
        original = spec; read = 0; total = C.LENGTH_UNSET; etag = null;
        openChunk(spec, true);
        return length;
    }

    private void openChunk(DataSpec spec, boolean first) throws HttpDataSourceException {
        long opened = input.open(spec);
        String tag = header("ETag");
        if (!first && etag != null && !tag.isEmpty() && !etag.equals(tag))
            throw invalid("Resource changed between partial responses", TYPE_OPEN);
        if (first) etag = tag.isEmpty() ? null : tag;
        partial = input.getResponseCode() == 206;
        if (!partial) {
            if (!first && total != C.LENGTH_UNSET && opened != C.LENGTH_UNSET && opened != total - spec.position)
                throw invalid("Resource length changed", TYPE_OPEN);
            chunkRemaining = opened;
            if (first) length = opened;
            return;
        }
        Matcher range = RANGE.matcher(header("Content-Range"));
        if (!range.matches()) throw invalid("Partial response has no valid Content-Range", TYPE_OPEN);
        try {
            long start = Long.parseLong(range.group(1)), end = Long.parseLong(range.group(2));
            long full = range.group(3).equals("*") ? C.LENGTH_UNSET : Long.parseLong(range.group(3));
            if (start != spec.position || end < start || (full != C.LENGTH_UNSET && end >= full))
                throw invalid("Partial response does not match the requested range", TYPE_OPEN);
            if (!header("Content-Encoding").isEmpty() && !header("Content-Encoding").equalsIgnoreCase("identity"))
                throw invalid("Compressed partial response is unsupported", TYPE_OPEN);
            if (!first && full != total)
                throw invalid("Resource changed between partial responses", TYPE_OPEN);
            if (first) {
                total = full;
                if (full == C.LENGTH_UNSET && spec.length == C.LENGTH_UNSET)
                    throw invalid("Partial response has no full resource length", TYPE_OPEN);
                if (full != C.LENGTH_UNSET && spec.length != C.LENGTH_UNSET && spec.length > full - start)
                    throw invalid("Requested range exceeds the full resource", TYPE_OPEN);
                length = spec.length != C.LENGTH_UNSET ? spec.length : full - start;
            }
            chunkRemaining = Math.min(Math.addExact(end - start, 1), length - read);
        } catch (NumberFormatException | ArithmeticException error) {
            throw invalid("Invalid partial response length", TYPE_OPEN);
        }
    }

    @Override public int read(byte[] buffer, int offset, int count) throws HttpDataSourceException {
        if (count == 0) return 0;
        if (length != C.LENGTH_UNSET && read == length) return C.RESULT_END_OF_INPUT;
        if (partial && chunkRemaining == 0) {
            input.close();
            openChunk(original.subrange(read), false);
        }
        int wanted = (int) Math.min(count, length == C.LENGTH_UNSET ? count : length - read);
        if (chunkRemaining != C.LENGTH_UNSET) wanted = (int) Math.min(wanted, chunkRemaining);
        int received = input.read(buffer, offset, wanted);
        if (received == C.RESULT_END_OF_INPUT) {
            if (length != C.LENGTH_UNSET && read < length) throw invalid("Response ended before the full resource was downloaded", TYPE_READ);
            return received;
        }
        read += received;
        if (chunkRemaining != C.LENGTH_UNSET) chunkRemaining -= received;
        return received;
    }

    private HttpDataSourceException invalid(String reason, int type) {
        return HttpDataSourceException.createForIOException(new EOFException(reason), original, type);
    }

    private String header(String name) {
        for (Map.Entry<String, List<String>> entry : input.getResponseHeaders().entrySet())
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return entry.getValue().get(0).trim();
        return "";
    }

    @Override public Uri getUri() { return input.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return input.getResponseHeaders(); }
    @Override public int getResponseCode() { return input.getResponseCode(); }
    @Override public void close() throws HttpDataSourceException { input.close(); }
    @Override public void addTransferListener(TransferListener listener) { input.addTransferListener(listener); }
    @Override public void setRequestProperty(String name, String value) { input.setRequestProperty(name, value); }
    @Override public void clearRequestProperty(String name) { input.clearRequestProperty(name); }
    @Override public void clearAllRequestProperties() { input.clearAllRequestProperties(); }
}

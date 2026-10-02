package com.fongmi.android.tv.offline;

import android.content.Context;
import androidx.media3.datasource.cache.CacheSpan;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative manual cleanup. Never recursively delete the cache directory or saved XML. */
public final class OfflineCacheMaintenance {
    public static final class Busy extends IOException {}
    public static final class Report {
        public final long bytes;
        public final int files;
        private final Map<String, List<File>> resources;
        private final List<Temporary> temporary;
        private Report(Map<String, List<File>> resources, List<Temporary> temporary) {
            this.resources = resources; this.temporary = temporary;
            long size = 0; int count = 0;
            for (List<File> spans : resources.values()) for (File file : spans) {
                if (file.isFile()) { size += file.length(); count++; }
            }
            for (Temporary file : temporary) { size += file.length; count++; }
            bytes = size; files = count;
        }
    }
    private record Temporary(File file, long length, long modified) {
        boolean unchanged() { return file.isFile() && file.length() == length && file.lastModified() == modified; }
    }
    private final Context context;
    private final OfflineCache cache;
    public OfflineCacheMaintenance(Context context, OfflineCache cache) {
        this.context = context.getApplicationContext(); this.cache = cache;
    }

    /** Worker-thread API; checking alone never removes files. */
    public Report scan() throws IOException {
        return cache.maintain(() -> {
            // Playback may independently save a subtitle for an existing completed task.
            synchronized (OfflineSubtitles.class) { return inspect(); }
        });
    }

    private Report inspect() throws IOException {
        Set<String> owned = new HashSet<>();
        for (var download : cache.list()) owned.add(download.request.id);
        Map<String, List<File>> resources = new LinkedHashMap<>();
        for (String key : cache.storage().getKeys()) {
            int separator = key.indexOf(':');
            // Only the UUID-prefixed resources created by this module can be classified.
            if (separator != 36 || !key.substring(0, separator).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    || owned.contains(key.substring(0, separator))) continue;
            List<File> files = new ArrayList<>();
            for (CacheSpan span : cache.storage().getCachedSpans(key)) if (span.file != null) files.add(span.file);
            if (!files.isEmpty()) resources.put(key, files);
        }
        List<Temporary> temporary = new ArrayList<>();
        collect(new File(context.getFilesDir(), "danmaku_saved"), "sync-", temporary);
        collect(context.getCacheDir(), "offline-subtitle-", temporary);
        return new Report(resources, temporary);
    }

    private void collect(File directory, String prefix, List<Temporary> result) throws IOException {
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot inspect temporary files");
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix) && file.getName().endsWith(".tmp")
                    && file.getCanonicalFile().equals(new File(directory.getCanonicalFile(), file.getName())))
                result.add(new Temporary(file, file.length(), file.lastModified()));
        }
    }

    /** Recheck ownership at confirmation time; delete only items in the approved snapshot. */
    public long clean(Report approved) throws IOException {
        return cache.maintain(() -> {
            synchronized (OfflineSubtitles.class) {
                Report current = inspect();
                long released = 0;
                for (var entry : current.resources.entrySet()) {
                    if (!approved.resources.containsKey(entry.getKey())
                            || !approved.resources.get(entry.getKey()).containsAll(entry.getValue())) continue;
                    List<File> files = entry.getValue();
                    long size = 0; for (File file : files) size += file.length();
                    cache.storage().removeResource(entry.getKey());
                    for (File file : files) if (file.exists()) throw new IOException("Cannot remove cache file");
                    released += size;
                }
                for (Temporary file : approved.temporary) {
                    if (!current.temporary.contains(file) || !file.unchanged()) continue;
                    if (!file.file.delete()) throw new IOException("Cannot remove temporary file");
                    released += file.length;
                }
                return released;
            }
        });
    }
}

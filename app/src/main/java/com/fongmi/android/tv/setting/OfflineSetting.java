package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/** Device-local limits; never part of cache transfer or playback history. */
public final class OfflineSetting {
    public static final String PARALLEL_DOWNLOADS = "offline_parallel_downloads";
    public static final int DEFAULT_PARALLEL_DOWNLOADS = 3;
    public static final int MIN_PARALLEL_DOWNLOADS = 1;
    public static final int MAX_PARALLEL_DOWNLOADS = 6;

    public static int getParallelDownloads() {
        return Math.clamp(Prefers.getInt(PARALLEL_DOWNLOADS, DEFAULT_PARALLEL_DOWNLOADS), MIN_PARALLEL_DOWNLOADS, MAX_PARALLEL_DOWNLOADS);
    }

    public static void putParallelDownloads(int value) {
        Prefers.put(PARALLEL_DOWNLOADS, Math.clamp(value, MIN_PARALLEL_DOWNLOADS, MAX_PARALLEL_DOWNLOADS));
    }
}

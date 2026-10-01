package com.fongmi.android.tv.offline;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

import java.util.regex.Pattern;

/** Uses the original online history as the canonical record, without changing online playback. */
final class OfflineHistory {
    private OfflineHistory() {}

    static History original(OfflineVideo video) {
        try {
            History history = video.history.isEmpty() ? null : History.objectFrom(video.history);
            if (history == null || history.getKey() == null) return null;
            String[] parts = history.getKey().split(Pattern.quote(AppDatabase.SYMBOL), -1);
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
            history.setCid(Integer.parseInt(parts[2]));
            return history;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static History restore(OfflineVideo video) {
        History original = original(video);
        History online = original == null ? null : AppDatabase.get().getHistoryDao().find(original.getCid(), original.getKey());
        History latest = online;
        if (latest == null) latest = original;
        History restored = latest == null ? new History() : latest.copy();
        boolean sameEpisode = latest != null && video.episode.equals(latest.getVodRemarks());
        if (sameEpisode && latest == online && original != null && !original.getEpisodeUrl().isEmpty()) {
            sameEpisode = original.getEpisodeUrl().equals(online.getEpisodeUrl());
        }
        if (!sameEpisode) {
            restored.setPosition(C.TIME_UNSET);
            restored.setDuration(C.TIME_UNSET);
        }
        return restored;
    }

    static void save(OfflineVideo video, History progress, boolean exit) {
        History original = original(video);
        if (original == null) return;
        if (Setting.isIncognito() || progress == null || !progress.canSave()) return;
        // Keep the runtime offline URL out of the online history and its navigation intent.
        History copy = progress.copy();
        copy.setKey(original.getKey());
        copy.setCid(original.getCid());
        copy.setVodFlag(original.getVodFlag());
        copy.setEpisodeUrl(original.getEpisodeUrl());
        copy.setVodRemarks(original.getVodRemarks().isEmpty() ? video.episode : original.getVodRemarks());
        copy.setVodName(original.getVodName() == null ? video.title : original.getVodName());
        copy.setVodPic(original.getVodPic());
        progress.markSaveScheduled();
        Task.executeSerial(() -> {
            History existing = AppDatabase.get().getHistoryDao().find(copy.getCid(), copy.getKey());
            if (existing != null && existing.getCreateTime() > copy.getCreateTime()) return;
            // No name-based merge: an offline task must only update its own original record.
            copy.save();
            if (exit) RefreshEvent.history();
        });
    }
}

package com.fongmi.android.tv.offline;

import android.content.Context;
import com.fongmi.android.tv.R;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import androidx.media3.exoplayer.offline.Download;

/** Refreshes source addresses without changing the player or deleting files before resolution succeeds. */
final class OfflineCacheRepair {
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    static boolean busy(String id) { return pending.contains(id); }

    static void start(Context context, Download download, OfflineCache.Callback callback) {
        if (!pending.add(download.request.id)) { callback.complete(R.string.offline_preparing); return; }
        OfflineCache.Callback result = message -> {
            if (message != R.string.offline_preparing) pending.remove(download.request.id);
            callback.complete(message);
        };
        try {
            Context application = context.getApplicationContext();
            OfflineVideo video = OfflineVideo.decode(download.request.data);
            var history = OfflineHistory.original(video);
            if (history == null) { result.complete(R.string.offline_danmaku_missing); return; }
            var latest = OfflineHistory.restore(video);
            if (OfflineVideo.sameEpisode(history, latest)) history = latest;
            OfflineEpisodeResolver.refresh(application, history,
                    fresh -> OfflineIntegration.get(application).replace(download, fresh, result), result);
        } catch (Exception error) { result.complete(R.string.offline_resolve_error); }
    }
}

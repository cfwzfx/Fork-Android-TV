package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.player.PlayerManager;
import com.github.catvod.net.OkHttp;

import java.util.HashMap;

/**
 * Public host integration: resume(Context), open(Context), showPanel(FragmentActivity),
 * cacheCurrent(Activity, PlayerManager, History), cacheEpisode(Context, History).
 * These are the only calls needed by upstream pages. All host player/network knowledge stays here.
 */
public final class OfflineIntegration {
    private OfflineIntegration() {}

    public static OfflineCache get(Context context) {
        return OfflineCache.get(context, headers -> new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(OkHttp.player())
                .setDefaultRequestProperties(new HashMap<>(headers)));
    }

    public static void resume(Context context) {
        get(context).resumeService();
    }

    public static void open(Context context) {
        Intent intent = new Intent(context, OfflineCacheActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void showPanel(FragmentActivity activity) {
        if (activity.getSupportFragmentManager().isStateSaved()) return;
        if (activity.getSupportFragmentManager().findFragmentByTag("offline_panel") == null) {
            new OfflineCacheDialog().show(activity.getSupportFragmentManager(), "offline_panel");
        }
    }

    public static void play(Context context, String id) {
        Intent intent = new Intent(context, VideoActivity.class);
        intent.putExtra("offline_id", id).putExtra("id", id).putExtra("key", "offline");
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void cacheCurrent(Activity activity, PlayerManager players, History history) {
        if (players.isOffline()) {
            show(activity, R.string.offline_already_cached);
            return;
        }
        if (history == null) { show(activity, R.string.offline_not_ready); return; }
        history = history.copy();
        if (players.getPlayer() == null || players.getPlayer().getPlaybackState() != Player.STATE_READY) {
            cacheEpisode(activity, history);
            return;
        }
        MediaItem item = players.getPlayer().getCurrentMediaItem();
        MediaItem.LocalConfiguration config = item == null ? null : item.localConfiguration;
        if (config == null) { cacheEpisode(activity, history); return; }
        String url = config.uri.toString();
        if (config.drmConfiguration != null || url == null
                || !(url.startsWith("https://") || url.startsWith("http://"))
                || url.contains("***") || players.getPlayer().isCurrentMediaItemLive()) {
            Context application = activity.getApplicationContext();
            get(application).requestEpisode(history, ready -> ready.complete(R.string.offline_unsupported),
                    message -> show(application, message));
            return;
        }
        Format format = selectedFormat(players, C.TRACK_TYPE_VIDEO);
        String identity = OfflineVideo.identity(history);
        OfflineVideo video = new OfflineVideo(identity, history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), url, config.mimeType, players.getHeaders(),
                history.toString(), App.gson().toJson(players.getDanmakus()), sourceWithSubtitles(players));
        TrackSelectionParameters.Builder selection = downloadParameters();
        if (format != null && format.width > 0 && format.height > 0) selection.setMaxVideoSize(format.width, format.height);
        if (format != null && format.bitrate > 0) selection.setMaxVideoBitrate(format.bitrate);
        if (format != null && format.sampleMimeType != null) selection.setPreferredVideoMimeTypes(format.sampleMimeType);
        Format audio = selectedFormat(players, C.TRACK_TYPE_AUDIO);
        if (audio != null && audio.language != null) selection.setPreferredAudioLanguage(audio.language);
        try {
            Context application = activity.getApplicationContext();
            History snapshot = history.copy();
            get(activity).requestEpisode(snapshot, ready -> get(application).add(video, selection.build(), message -> {
                // Keep the episode reservation while refreshing an expired playback URL once.
                if (message == R.string.offline_invalid_content || message == R.string.offline_http_error)
                    OfflineEpisodeResolver.resolve(application, snapshot, ready);
                else ready.complete(message);
            }), message -> show(application, message));
        } catch (RuntimeException e) {
            show(activity, R.string.offline_storage_error);
        }
    }

    public static void cacheEpisode(Context context, History history) {
        if (history == null || history.getEpisodeUrl().isEmpty()) {
            show(context, R.string.offline_not_ready);
            return;
        }
        Context application = context.getApplicationContext();
        History snapshot = history.copy();
        try {
            get(application).requestEpisode(snapshot, ready -> OfflineEpisodeResolver.resolve(application, snapshot, ready),
                    message -> show(application, message));
        } catch (RuntimeException error) { show(application, R.string.offline_storage_error); }
    }

    private static Format selectedFormat(PlayerManager players, int type) {
        for (androidx.media3.common.Tracks.Group group : players.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) return group.getTrackFormat(i);
        }
        return null;
    }

    static TrackSelectionParameters.Builder downloadParameters() {
        return androidx.media3.exoplayer.offline.DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
    }

    private static String sourceWithSubtitles(PlayerManager players) {
        if (players.getSourceResult().isEmpty() && players.getSubtitles().isEmpty()) return "";
        try {
            org.json.JSONObject source = players.getSourceResult().isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(players.getSourceResult());
            source.put("subs", new org.json.JSONArray(App.gson().toJson(players.getSubtitles())));
            return source.toString();
        } catch (Exception error) { return players.getSourceResult(); }
    }

    private static void show(Context context, int message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}

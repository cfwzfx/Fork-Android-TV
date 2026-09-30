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
import com.fongmi.android.tv.player.Players;
import com.github.catvod.net.OkHttp;

import java.util.HashMap;

/**
 * Public host integration: resume(Context), open(Context), showPanel(FragmentActivity),
 * cacheCurrent(Activity, Players, History).
 * These are the only calls needed by upstream pages. All host player/network knowledge stays here.
 */
public final class OfflineIntegration {
    private OfflineIntegration() {}

    public static OfflineCache get(Context context) {
        return OfflineCache.get(context, headers -> new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(OkHttp.client())
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

    public static void cacheCurrent(Activity activity, Players players, History history) {
        if (players.isOffline()) {
            show(activity, R.string.offline_exists);
            return;
        }
        if (history == null || players.get() == null || players.get().getPlaybackState() != Player.STATE_READY) {
            show(activity, R.string.offline_not_ready);
            return;
        }
        MediaItem item = players.get().getCurrentMediaItem();
        MediaItem.LocalConfiguration config = item == null ? null : item.localConfiguration;
        String url = config == null ? null : config.uri.toString();
        if (config == null || config.drmConfiguration != null || url == null
                || !(url.startsWith("https://") || url.startsWith("http://"))
                || url.contains("***") || players.get().isCurrentMediaItemLive()) {
            show(activity, R.string.offline_unsupported);
            return;
        }
        Format format = players.get().getVideoFormat();
        String quality = format == null ? "audio" : format.width + "x" + format.height + ":" + format.bitrate + ":" + format.codecs;
        String identity = new org.json.JSONArray().put(history.getKey()).put(history.getVodFlag())
                .put(history.getEpisodeUrl()).put(quality).toString();
        OfflineVideo video = new OfflineVideo(identity, history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), url, config.mimeType, players.getHeaders(),
                history.toString(), App.gson().toJson(players.getDanmakus()), players.getSourceResult());
        TrackSelectionParameters.Builder selection = downloadParameters();
        if (format != null && format.width > 0 && format.height > 0) selection.setMaxVideoSize(format.width, format.height);
        if (format != null && format.bitrate > 0) selection.setMaxVideoBitrate(format.bitrate);
        if (format != null && format.sampleMimeType != null) selection.setPreferredVideoMimeTypes(format.sampleMimeType);
        Format audio = players.get().getAudioFormat();
        if (audio != null && audio.language != null) selection.setPreferredAudioLanguage(audio.language);
        try {
            Context application = activity.getApplicationContext();
            get(activity).add(video, selection.build(), message -> show(application, message));
        } catch (RuntimeException e) {
            show(activity, R.string.offline_storage_error);
        }
    }

    private static TrackSelectionParameters.Builder downloadParameters() {
        return androidx.media3.exoplayer.offline.DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
    }

    private static void show(Context context, int message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}

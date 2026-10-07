package com.fongmi.android.tv.player.danmaku;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuController;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import okhttp3.OkHttpClient;

/** Keeps the activity's danmaku state while binding the public PlayerView API. */
public final class DanmakuViewAdapter implements AutoCloseable {
    private PlayerView view;
    private OkHttpClient client;
    private DanmakuConfig config = DanmakuConfig.DEFAULT;
    private Uri source;
    private boolean enabled;

    public void bind(PlayerView next) {
        if (view != next && view != null) {
            view.getDanmakuController().setListener(null);
            view.setDanmakuSource(null);
        }
        view = next;
        view.getDanmakuController().setListener(new DanmakuController.Listener() {
            @Override public void onLoadCompleted(Uri uri, int count) {
                App.post(() -> {
                    if (view == next && uri.equals(source))
                        Notify.show(ResUtil.getString(R.string.danmaku_loaded_count, count));
                });
            }
        });
        view.setDanmakuOkHttpClient(client);
        view.setDanmakuConfig(config);
        view.setDanmakuEnabled(enabled);
        view.setDanmakuSource(source);
    }

    public void setOkHttpClient(OkHttpClient value) {
        client = com.fongmi.android.tv.offline.OfflineDanmakuCache.client(value);
        if (view != null) view.setDanmakuOkHttpClient(client);
    }

    public void setConfig(DanmakuConfig value) {
        config = value;
        if (view != null) view.setDanmakuConfig(value);
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (view != null) view.setDanmakuEnabled(value);
    }

    public void setDataSource(@Nullable Uri value) {
        source = value;
        if (view != null) view.setDanmakuSource(value);
    }

    public void sendNow(String text) {
        if (view != null) view.sendDanmaku(text);
    }

    @Override public void close() {
        if (view != null) {
            view.getDanmakuController().setListener(null);
            view.getDanmakuController().release();
        }
        view = null;
        source = null;
    }
}

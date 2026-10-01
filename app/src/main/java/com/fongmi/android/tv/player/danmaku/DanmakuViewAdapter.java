package com.fongmi.android.tv.player.danmaku;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.danmaku.DanmakuConfig;
import okhttp3.OkHttpClient;

/** Keeps the activity's danmaku state while binding the public PlayerView API. */
public final class DanmakuViewAdapter implements AutoCloseable {
    private PlayerView view;
    private OkHttpClient client;
    private DanmakuConfig config = DanmakuConfig.DEFAULT;
    private Uri source;
    private boolean enabled;

    public void bind(PlayerView next) {
        if (view != next && view != null) view.setDanmakuSource(null);
        view = next;
        view.setDanmakuOkHttpClient(client);
        view.setDanmakuConfig(config);
        view.setDanmakuEnabled(enabled);
        view.setDanmakuSource(source);
    }

    public void setOkHttpClient(OkHttpClient value) {
        client = value;
        if (view != null) view.setDanmakuOkHttpClient(value);
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
        if (view != null) view.getDanmakuController().release();
        view = null;
        source = null;
    }
}

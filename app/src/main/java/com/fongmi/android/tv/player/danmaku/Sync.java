package com.fongmi.android.tv.player.danmaku;

import com.fongmi.android.tv.player.Players;

import master.flame.danmaku.danmaku.model.AbsDanmakuSync;

public class Sync extends AbsDanmakuSync {

    private final Players player;
    private long time;
    private final OffsetProvider offsetProvider;

    public Sync(Players player, OffsetProvider offsetProvider) {
        this.player = player;
        this.offsetProvider = offsetProvider;
        this.time = System.currentTimeMillis();
    }

    @Override
    public long getUptimeMillis() {
        return player == null ? 0 : player.getPosition() + offsetProvider.getOffset();
    }

    @Override
    public int getSyncState() {
        if (player == null) return SYNC_STATE_HALT;
        long current = System.currentTimeMillis();
        if (current - time < 1000) return SYNC_STATE_HALT;
        time = current;
        return player.isPlaying() ? SYNC_STATE_PLAYING : SYNC_STATE_HALT;
    }

    @Override
    public long getThresholdTimeMills() {
        return 1000L;
    }

    // todo cf 弹幕
    public interface OffsetProvider {
        int getOffset();
    }
}

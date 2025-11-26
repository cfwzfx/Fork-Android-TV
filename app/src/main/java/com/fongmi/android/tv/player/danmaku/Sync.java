package com.fongmi.android.tv.player.danmaku;

import com.fongmi.android.tv.player.Players;

import master.flame.danmaku.danmaku.model.AbsDanmakuSync;

public class Sync extends AbsDanmakuSync {

    private final Players player;
    private final OffsetProvider offsetProvider;

    public Sync(Players player, OffsetProvider offsetProvider) {
        this.player = player;
        this.offsetProvider = offsetProvider;
    }

    @Override
    public long getUptimeMillis() {
        return player.getPosition() + offsetProvider.getOffset();
    }

    @Override
    public int getSyncState() {
        return player.isPlaying() ? SYNC_STATE_PLAYING : SYNC_STATE_HALT;
    }

    @Override
    public long getThresholdTimeMills() {
        return 1000L;
    }

    @Override
    public boolean isSyncPlayingState() {
        return true;
    }

    // todo cf 弹幕
    public interface OffsetProvider {
        long getOffset();
    }
}

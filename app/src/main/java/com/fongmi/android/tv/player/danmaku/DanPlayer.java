package com.fongmi.android.tv.player.danmaku;

import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.player.Players;
import com.fongmi.android.tv.utils.ResUtil;
import com.orhanobut.logger.Logger;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;

public class DanPlayer implements DrawHandler.Callback, Sync.OffsetProvider {

    private static final String TAG = DanPlayer.class.getSimpleName();
    private final ExecutorService executor;
    private final DanmakuContext context;
    private DanmakuView view;
    private Players player;
    // todo cf 弹幕
    /**
     * 弹幕偏移时间
     * 为了解决有些视频的弹幕和视频不同步的问题
     * 这个值在配置文件中的存储是和history关联的，仅仅针对当前历史记录，不影响其他历史记录
     */
    private long offset = 0;
    // todo cf 弹幕
    /**
     * 弹幕字体大小，这个是作者默认设置的，和[customScaleTextSize]作为区分
     */
    private float scaleTextSize = 0.8f;
    // todo cf 弹幕
    /**
     * 自定义弹幕大小
     * 配置文件中的存储值是0-6，默认值是3
     * 对应关系是
     * 0: 0.7倍
     * 1: 0.8倍
     * 2: 0.9倍
     * 3: 1倍
     * 4: 1.1倍
     * 5: 1.2倍
     * 6: 1.3倍
     * 默认值是1倍
     */
    private float customScaleTextSize = 1f;
    // todo cf 弹幕
    /**
     * 自定义弹幕行间距
     * 配置文件中的存储值是0-6，默认值是4
     * 对应关系是
     * 0: 0dp 紧密型
     * 1: 2dp 紧凑型
     * 2: 4dp 稍紧型
     * 3: 6dp 偏紧型
     * 4: 8dp 适中型
     * 5: 11dp 稍松型
     * 6: 14dp 疏松型
     * 默认值是8dp
     */
    private int lineSpacing = 8;

    /**
     * 原始的弹幕速度1/1.2
     */
    private float scrollSpeed = 1.2f;
    // todo cf 弹幕
    /**
     * 这里显示的数字不是真实倍速，因为这个数字越大滑动的速度越慢，和实际感官相反。
     * 对应关系是
     * 0: 1/1.5=0.66
     * 1: 1/1.3=0.77
     * 2: 1/1.15=0.87
     * 3: 1/1=1
     * 4: 1/0.8=1.25
     * 5: 1/0.6=1.67
     * 6: 1/0.4=2.5
     * 默认值是1/1=1
     */
    private float customScrollSpeed = 1f;

    public DanPlayer() {
        context = DanmakuContext.create();
        executor = Executors.newCachedThreadPool();
        HashMap<Integer, Integer> maxLines = new HashMap<>();
        maxLines.put(BaseDanmaku.TYPE_FIX_TOP, 2);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_RL, 2);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_LR, 2);
        maxLines.put(BaseDanmaku.TYPE_FIX_BOTTOM, 2);
        context.setMaximumLines(maxLines).setScrollSpeedFactor(getRealScrollSpeed()).setDanmakuTransparency(0.8f);
        context.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3).setDanmakuMargin(ResUtil.dp2px(lineSpacing)).setScaleTextSize(getRealScaleTextSize());
    }

    public void setView(DanmakuView view) {
        view.setCallback(this);
        this.view = view;
    }

    public void setPlayer(Players player) {
        context.setDanmakuSync(new Sync(this.player = player, this));
    }

    private boolean isDanmakuPrepared() {
        return view != null && view.isPrepared();
    }

    public void seekTo(long time) {
        executor.execute(() -> {
            if (isDanmakuPrepared()) view.seekTo(time + offset);
            if (isDanmakuPrepared()) view.hide();
        });
    }

    // todo cf 弹幕
    public void onSeekTo(long time) {
        executor.execute(() -> {
            if (isDanmakuPrepared()) view.seekTo(time + offset);
        });
    }

    public void play() {
        executor.execute(() -> {
            if (isDanmakuPrepared()) view.resume();
        });
    }

    public void pause() {
        executor.execute(() -> {
            if (isDanmakuPrepared()) view.pause();
        });
    }

    public void stop() {
        executor.execute(() -> {
            if (isDanmakuPrepared()) view.stop();
        });
    }

    public void release() {
        executor.execute(() -> {
            if (isDanmakuPrepared()) view.release();
        });
    }

    public void setDanmaku(Danmaku item) {
        executor.execute(() -> {
            view.release();
            if (item.isEmpty()) return;
            Logger.t(TAG).d(item.getUrl());
            view.prepare(new Parser().load(new Loader(item).getDataSource()), context);
        });
    }

    public void setTextSize(float size) {
        // todo cf 弹幕
        scaleTextSize = size;
        context.setScaleTextSize(getRealScaleTextSize());
    }

    // todo cf 弹幕
    public void setCustomTextSize(int configSize) {
        customScaleTextSize = convertTextSizeNumber(configSize);
        context.setScaleTextSize(getRealScaleTextSize());
    }

    // todo cf 弹幕
    public void setCustomLineSpacing(int configSize) {
        lineSpacing = convertLineSpacingNumber(configSize);
        context.setDanmakuMargin(ResUtil.dp2px(lineSpacing));
    }

    // todo cf 弹幕
    public void setCustomSpeed(int configSize) {
        customScrollSpeed = convertSpeedNumber(configSize);
        context.setScrollSpeedFactor(getRealScrollSpeed());
    }

    public void check(int state) {
        if (state == Player.STATE_BUFFERING) pause();
        else if (state == Player.STATE_READY) prepared();
    }

    @Override
    public void prepared() {
        App.post(() -> {
            boolean playing = player.isPlaying();
            long position = player.getPosition();
            executor.execute(() -> {
                if (!isDanmakuPrepared()) return;
                if (playing) view.start(position + offset);
                else view.pause();
                view.show();
            });
        });
    }

    @Override
    public void updateTimer(DanmakuTimer danmakuTimer) {
    }

    @Override
    public void danmakuShown(BaseDanmaku baseDanmaku) {
    }

    @Override
    public void drawingFinished() {
    }

    // todo cf 弹幕
    public void setRlMaxLines(int rlCount) {
        HashMap<Integer, Integer> maxLines = new HashMap<>();
        maxLines.put(BaseDanmaku.TYPE_FIX_TOP, 2);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_RL, rlCount);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_LR, 2);
        maxLines.put(BaseDanmaku.TYPE_FIX_BOTTOM, 2);
        context.setMaximumLines(maxLines);
    }

    // todo cf 弹幕
    public void setOffset(long offset) {
        this.offset = offset * 1000;
    }

    // todo cf 弹幕
    public float getRealScaleTextSize() {
        return  scaleTextSize * customScaleTextSize;
    }

    // todo cf 弹幕
    public float getRealScrollSpeed() {
        return  scrollSpeed * customScrollSpeed;
    }

    // todo cf 弹幕
    public static float convertTextSizeNumber(int value) {
        switch (value) {
            case 0: return 0.7f;
            case 1: return 0.8f;
            case 2: return 0.9f;
            case 3: return 1.0f;
            case 4: return 1.1f;
            case 5: return 1.2f;
            case 6: return 1.3f;
            default:
                return 1.0f;
        }
    }

    // todo cf 弹幕
    public static int convertLineSpacingNumber(int value) {
        switch (value) {
            case 0: return 0;
            case 1: return 2;
            case 2: return 4;
            case 3: return 6;
            case 4: return 8;
            case 5: return 11;
            case 6: return 14;
            default:
                return 8;
        }
    }

    // todo cf 弹幕
    public static float convertSpeedNumber(int value) {
        switch (value) {
            case 0: return 1.5f;
            case 1: return 1.3f;
            case 2: return 1.15f;
            case 3: return 1f;
            case 4: return 0.8f;
            case 5: return 0.6f;
            case 6: return 0.4f;
            default:
                return 1f;
        }
    }

    // todo cf 弹幕
    @Override
    public long getOffset() {
        return offset;
    }
}
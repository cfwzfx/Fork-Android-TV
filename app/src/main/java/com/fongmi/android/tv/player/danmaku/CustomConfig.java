package com.fongmi.android.tv.player.danmaku;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

// todo cf 弹幕
public class CustomConfig {
    private static int DEFAULT_MAX_LINES = 2;
    private static long DEFAULT_OFFSET = 0;
    private static int DEFAULT_TEXT_SIZE = 3;
    private static int DEFAULT_LINE_SPACING = 4;
    private static int DEFAULT_SPEED = 3;

    /**
     * 弹幕最大行数
     * 默认是2行
     */
    @SerializedName("danmuMaxLines")
    private int danmuMaxLines;

    /**
     * 弹幕显示字体大小
     * 默认是3
     * 配置参数和实际效果对应关系
     * 0: 0.7倍
     * 1: 0.8倍
     * 2: 0.9倍
     * 3: 1倍
     * 4: 1.1倍
     * 5: 1.2倍
     * 6: 1.3倍
     * 默认值是1倍
     */
    @SerializedName("dnamuTextSize")
    private int dnamuTextSize;

    /**
     * 弹幕行间距
     * 默认是4
     * 配置参数和实际效果对应关系
     * 0: 0dp 紧密型
     * 1: 2dp 紧凑型
     * 2: 4dp 稍紧型
     * 3: 6dp 偏紧型
     * 4: 8dp 适中型
     * 5: 11dp 稍松型
     * 6: 14dp 疏松型
     * 默认值是8dp 适中型，全局生效
     */
    @SerializedName("dnamuLineSpacing")
    private int dnamuLineSpacing;

    @SerializedName("dnamuSpeed")
    private int dnamuSpeed;

    /**
     * 历史记录列表
     * 用于存储每个视频的弹幕偏移时间
     * 这个是针对每个视频的，不影响其他视频
     */
    @SerializedName("history")
    private List<HistoryItem> history;

    public CustomConfig() {
        this.danmuMaxLines = DEFAULT_MAX_LINES;
        this.dnamuTextSize = DEFAULT_TEXT_SIZE;
        this.dnamuLineSpacing = DEFAULT_LINE_SPACING;
        this.dnamuSpeed = DEFAULT_SPEED;
        this.history = new ArrayList<>();
    }

    public int getDanmuMaxLines() {
        return danmuMaxLines;
    }

    public void setDanmuMaxLines(int danmuMaxLines) {
        this.danmuMaxLines = danmuMaxLines;
    }

    public int getDnamuTextSize() {
        return dnamuTextSize;
    }

    public void setDnamuTextSize(int dnamuTextSize) {
        this.dnamuTextSize = dnamuTextSize;
    }

    public int getDnamuLineSpacing() {
        return dnamuLineSpacing;
    }

    public void setDnamuLineSpacing(int dnamuLineSpacing) {
        this.dnamuLineSpacing = dnamuLineSpacing;
    }

    public int getDnamuSpeed() {
        return dnamuSpeed;
    }

    public void setDnamuSpeed(int dnamuSpeed) {
        this.dnamuSpeed = dnamuSpeed;
    }

    public List<HistoryItem> getHistory() {
        return history;
    }

    public void setHistory(List<HistoryItem> history) {
        this.history = history;
    }

    public static class HistoryItem {
        @SerializedName("id")
        private String id;

        @SerializedName("offset")
        private long offset;

        public HistoryItem() {
            this.offset = CustomConfig.DEFAULT_OFFSET;
        }

        public HistoryItem(String id, long offset) {
            this.id = id;
            this.offset = offset;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public long getOffset() {
            return offset;
        }

        public void setOffset(long offset) {
            this.offset = offset;
        }
    }
}


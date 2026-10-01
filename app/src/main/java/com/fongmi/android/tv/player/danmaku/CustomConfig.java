package com.fongmi.android.tv.player.danmaku;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

// todo cf 弹幕
public class CustomConfig {
    private static int DEFAULT_MAX_LINES = 2;
    private static long DEFAULT_OFFSET = 0;

    @SerializedName("danmuMaxLines")
    private int danmuMaxLines;

    @SerializedName("history")
    private List<HistoryItem> history;

    public CustomConfig() {
        this.danmuMaxLines = DEFAULT_MAX_LINES;
        this.history = new ArrayList<>();
    }

    public int getDanmuMaxLines() {
        return danmuMaxLines;
    }

    public void setDanmuMaxLines(int danmuMaxLines) {
        this.danmuMaxLines = danmuMaxLines;
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


package com.fongmi.android.tv.impl;

import java.util.Map;

public interface ParseCallback {

    void onParseSuccess(Map<String, String> headers, String url, String from);

    void onParseError();

    default void onParseDanmaku(java.util.List<com.fongmi.android.tv.bean.Danmaku> items) {}
}

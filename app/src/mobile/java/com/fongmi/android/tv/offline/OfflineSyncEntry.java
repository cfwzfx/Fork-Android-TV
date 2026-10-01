package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.widget.AppCompatImageButton;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.impl.Process;

import java.io.Closeable;

/** Mobile-only feature hook. Leanback implements the same hook without an entry or endpoint. */
public final class OfflineSyncEntry {
    public static Closeable install(Activity activity, View root) {
        com.fongmi.android.tv.server.Server.get().start();
        OfflineSyncDialog sync = new OfflineSyncDialog(activity);
        AppCompatImageButton button = new AppCompatImageButton(activity);
        button.setId(R.id.offline_sync);
        button.setImageResource(R.drawable.offline_sync);
        button.setContentDescription(activity.getString(R.string.offline_sync));
        button.setBackgroundResource(R.drawable.offline_button_secondary);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.offline_text)));
        int size = (int) (44 * activity.getResources().getDisplayMetrics().density);
        int padding = size / 4;
        button.setPadding(padding, padding, padding, padding);
        button.setOnClickListener(v -> sync.show());
        LinearLayout header = (LinearLayout) root.findViewById(R.id.offline_back).getParent();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMarginStart(size / 5);
        header.addView(button, header.getChildCount() - 1, params);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, activity.getString(R.string.offline_sync));
        return sync;
    }

    public static Process process() {
        return new Process() {
            @Override public boolean isRequest(fi.iki.elonen.NanoHTTPD.IHTTPSession session, String url) { return url.startsWith("/offline-sync/"); }
            @Override public fi.iki.elonen.NanoHTTPD.Response doResponse(fi.iki.elonen.NanoHTTPD.IHTTPSession session, String url, java.util.Map<String, String> files) {
                return OfflineCacheSync.get(App.get()).doResponse(session, url, files);
            }
        };
    }
}

package com.fongmi.android.tv.offline;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fongmi.android.tv.R;

/** Shared touch/remote page, backed by the same task list as the playback side sheet. */
public final class OfflineCacheActivity extends AppCompatActivity {
    private OfflineCacheList list;
    private java.io.Closeable sync;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.offline_cache);
        View root = findViewById(R.id.offline_root);
        applyInsets(root);
        findViewById(R.id.offline_back).setOnClickListener(v -> finish());
        findViewById(R.id.offline_settings_button).setOnClickListener(v -> startActivity(new android.content.Intent(this, OfflineCacheSettingsActivity.class)));
        list = new OfflineCacheList(this, root);
        sync = OfflineSyncEntry.install(this, root);
    }

    static void applyInsets(View root) {
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, window) -> {
            Insets insets = window.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(left + insets.left, top + insets.top, right + insets.right, bottom + insets.bottom);
            return window;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        list.start();
    }

    @Override
    protected void onStop() {
        list.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (sync != null) try { sync.close(); } catch (java.io.IOException ignored) {}
        list.close();
        super.onDestroy();
    }
}

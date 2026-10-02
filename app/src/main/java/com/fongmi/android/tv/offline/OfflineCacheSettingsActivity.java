package com.fongmi.android.tv.offline;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.OfflineSetting;

/** Small standalone page shared by the normal cache page and mobile settings. */
public final class OfflineCacheSettingsActivity extends AppCompatActivity {
    private TextView value;
    private TextView cleanupStatus;
    private OfflineCacheMaintenance maintenance;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.offline_cache_settings);
        OfflineCacheActivity.applyInsets(findViewById(R.id.offline_root));
        value = findViewById(R.id.offline_parallel_value);
        cleanupStatus = findViewById(R.id.offline_cleanup_status);
        maintenance = new OfflineCacheMaintenance(this, OfflineIntegration.get(this));
        findViewById(R.id.offline_cleanup).setOnClickListener(view -> inspect());
        findViewById(R.id.offline_back).setOnClickListener(view -> finish());
        findViewById(R.id.offline_parallel).setOnClickListener(view -> select());
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() { value.setText(getString(R.string.offline_parallel_value, OfflineSetting.getParallelDownloads())); }

    private void inspect() {
        cleanupStatus.setText(R.string.offline_cleanup_scanning);
        work(maintenance::scan, report -> {
            if (report.files == 0) { cleanupStatus.setText(R.string.offline_cleanup_empty); return; }
            String message = getString(R.string.offline_cleanup_found, report.files, size(report.bytes));
            cleanupStatus.setText(message);
            new AlertDialog.Builder(this).setTitle(R.string.offline_cleanup).setMessage(message)
                    .setNegativeButton(R.string.offline_cancel, null)
                    .setPositiveButton(R.string.offline_cleanup_action, (dialog, which) ->
                            work(() -> maintenance.clean(report), bytes -> cleanupStatus.setText(getString(R.string.offline_cleanup_done, size(bytes)))))
                    .show();
        });
    }

    private String size(long bytes) { return android.text.format.Formatter.formatShortFileSize(this, bytes); }

    private <T> void work(java.util.concurrent.Callable<T> operation, java.util.function.Consumer<T> success) {
        findViewById(R.id.offline_cleanup).setEnabled(false);
        com.fongmi.android.tv.utils.Task.execute(() -> {
            try {
                T result = operation.call();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    findViewById(R.id.offline_cleanup).setEnabled(true); success.accept(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    findViewById(R.id.offline_cleanup).setEnabled(true);
                    cleanupStatus.setText(error instanceof OfflineCacheMaintenance.Busy ? R.string.offline_cleanup_busy : R.string.offline_cleanup_error);
                });
            }
        });
    }

    private void select() {
        String[] choices = new String[OfflineSetting.MAX_PARALLEL_DOWNLOADS - OfflineSetting.MIN_PARALLEL_DOWNLOADS + 1];
        for (int i = 0; i < choices.length; i++) choices[i] = getString(R.string.offline_parallel_value, i + OfflineSetting.MIN_PARALLEL_DOWNLOADS);
        new AlertDialog.Builder(this).setTitle(R.string.offline_parallel_downloads)
                .setSingleChoiceItems(choices, OfflineSetting.getParallelDownloads() - OfflineSetting.MIN_PARALLEL_DOWNLOADS, (dialog, index) -> {
                    OfflineSetting.putParallelDownloads(index + OfflineSetting.MIN_PARALLEL_DOWNLOADS);
                    refresh(); dialog.dismiss();
                }).setNegativeButton(R.string.offline_cancel, null).show();
    }
}

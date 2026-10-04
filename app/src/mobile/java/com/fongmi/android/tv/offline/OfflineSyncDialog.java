package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.text.format.Formatter;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.media3.exoplayer.offline.Download;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ScanTask;
import com.fongmi.android.tv.utils.Task;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/** Selection and progress UI only. Uses the history-sync scanner, without changing its modes. */
final class OfflineSyncDialog implements Closeable {
    private final Activity activity;
    private AlertDialog dialog;
    private ScanTask scanner;
    private OfflineCacheSync.Send send;
    private boolean closed, loading;

    OfflineSyncDialog(Activity activity) { this.activity = activity; }
    private boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

    void show() {
        if (!alive() || loading || send != null || (dialog != null && dialog.isShowing())) return;
        loading = true;
        Task.execute(() -> {
            try {
                List<Download> completed = OfflineIntegration.get(activity).list().stream()
                        .filter(value -> value.state == Download.STATE_COMPLETED).toList();
                App.post(() -> {
                    loading = false;
                    if (!alive()) return;
                    if (completed.isEmpty()) Notify.show(R.string.offline_sync_empty);
                    else select(completed);
                });
            } catch (Exception error) { App.post(() -> { loading = false; if (alive()) Notify.show(R.string.offline_load_error); }); }
        });
    }

    private void select(List<Download> completed) {
        dialog = OfflineSyncSelection.show(activity, completed, this::devices);
    }

    private void devices(List<Download> selected) {
        android.view.View content = android.view.LayoutInflater.from(activity).inflate(R.layout.offline_sync_devices, null);
        android.widget.ListView list = content.findViewById(R.id.offline_sync_devices);
        TextView status = content.findViewById(R.id.offline_sync_found);
        long bytes = 0;
        for (Download value : selected) bytes += value.getBytesDownloaded();
        ((TextView) content.findViewById(R.id.offline_sync_device_summary)).setText(activity.getString(
                R.string.offline_sync_device_summary, selected.size(), Formatter.formatFileSize(activity, bytes)));
        ArrayAdapter<Device> adapter = new ArrayAdapter<Device>(activity, R.layout.offline_sync_device_row) {
            @Override public android.view.View getView(int position, android.view.View recycled, android.view.ViewGroup parent) {
                android.view.View row = recycled == null ? android.view.LayoutInflater.from(activity)
                        .inflate(R.layout.offline_sync_device_row, parent, false) : recycled;
                Device device = getItem(position);
                ((TextView) row.findViewById(R.id.offline_sync_device_name)).setText(device.getName());
                ((TextView) row.findViewById(R.id.offline_sync_device_address)).setText(device.getIp());
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setEmptyView(content.findViewById(R.id.offline_sync_device_empty));
        // Bound only the list, leaving the heading and actions visible on small screens.
        Runnable update = () -> {
            status.setText(activity.getString(R.string.offline_sync_device_count, adapter.getCount()));
            android.view.ViewGroup.LayoutParams params = list.getLayoutParams();
            int rowHeight = Math.round(88 * activity.getResources().getDisplayMetrics().density);
            params.height = Math.min(rowHeight * adapter.getCount(), activity.getResources().getDisplayMetrics().heightPixels / 3);
            list.setLayoutParams(params);
        };
        AlertDialog current = new AlertDialog.Builder(activity).setView(content).create();
        dialog = current;
        java.util.function.Consumer<Device> add = device -> {
            if (!alive() || !current.isShowing() || !device.isMobile() || device.getUuid().equals(Device.get().getUuid())) return;
            try { OfflineLan.endpoint(device.getIp()); } catch (Exception ignored) { return; }
            for (int i = 0; i < adapter.getCount(); i++) {
                if (adapter.getItem(i).equals(device)) { adapter.remove(adapter.getItem(i)); adapter.insert(device, i); update.run(); return; }
            }
            adapter.add(device);
            update.run();
        };
        list.setOnItemClickListener((parent, row, index, id) -> {
            Device target = adapter.getItem(index);
            current.dismiss();
            start(target, selected);
        });
        content.findViewById(R.id.offline_sync_device_cancel).setOnClickListener(v -> current.dismiss());
        content.findViewById(R.id.offline_sync_device_refresh).setOnClickListener(v -> {
            adapter.clear(); update.run(); scan(add);
        });
        current.setOnDismissListener(ignored -> { if (scanner != null) { scanner.stop(); scanner = null; } });
        update.run();
        current.show();
        if (current.getWindow() != null) {
            current.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            current.getWindow().setLayout(Math.min(Math.round(460 * activity.getResources().getDisplayMetrics().density),
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        for (Device device : Device.getAll()) add.accept(device);
        scan(add);
    }

    private void scan(java.util.function.Consumer<Device> add) {
        if (scanner != null) scanner.stop();
        scanner = new ScanTask(add::accept);
        scanner.start();
    }

    private void start(Device device, List<Download> selected) {
        OfflineSyncStatus status = new OfflineSyncStatus(activity, device, selected.size(), () -> { if (send != null) send.cancel(); });
        AlertDialog current = status.dialog;
        dialog = current;
        send = OfflineCacheSync.get(activity).send(device, selected, new OfflineCacheSync.Listener() {
            @Override public void progress(String title, int index, int count, long received, long total) {
                if (!alive() || !current.isShowing()) return;
                status.progress(title, index, count, received, total);
            }
            @Override public void complete(int sent, int skipped, String error) {
                send = null;
                if (!alive()) return;
                current.dismiss();
                dialog = OfflineSyncStatus.result(activity, device, sent, skipped, error);
            }
        });
    }


    @Override public void close() {
        closed = true;
        if (scanner != null) scanner.stop();
        if (send != null) send.cancel();
        if (dialog != null) dialog.dismiss();
    }
}

package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.text.format.Formatter;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
        String[] names = new String[completed.size()];
        boolean[] checked = new boolean[completed.size()];
        for (int i = 0; i < names.length; i++) {
            OfflineVideo video = OfflineVideo.decode(completed.get(i).request.data);
            names[i] = video.title + " · " + video.episode + " · " + video.line + "\n"
                    + Formatter.formatFileSize(activity, completed.get(i).getBytesDownloaded());
        }
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.offline_sync_select)
                .setMultiChoiceItems(names, checked, (choice, index, enabled) -> {
                    checked[index] = enabled;
                    ((AlertDialog) choice).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any(checked));
                }).setNegativeButton(R.string.offline_cancel, null).setNeutralButton(R.string.offline_sync_all, null)
                .setPositiveButton(R.string.offline_sync_device, (choice, index) -> {
                    List<Download> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(completed.get(i));
                    devices(selected);
                }).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                boolean all = !all(checked);
                for (int i = 0; i < checked.length; i++) { checked[i] = all; dialog.getListView().setItemChecked(i, all); }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(all);
            });
        });
        dialog.show();
    }

    private void devices(List<Download> selected) {
        List<Device> found = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        java.util.function.Consumer<Device> add = device -> {
            if (!alive() || !device.isMobile() || device.getUuid().equals(Device.get().getUuid())) return;
            try { OfflineLan.endpoint(device.getIp()); } catch (Exception ignored) { return; }
            int index = found.indexOf(device);
            if (index >= 0) found.set(index, device);
            else found.add(device);
            adapter.clear();
            for (Device value : found) adapter.add(value.getName() + "\n" + value.getIp());
            adapter.notifyDataSetChanged();
        };
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.offline_sync_search)
                .setAdapter(adapter, (choice, index) -> start(found.get(index), selected))
                .setNegativeButton(R.string.offline_cancel, null).setNeutralButton(R.string.offline_sync_refresh, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            adapter.clear(); found.clear(); scan(add);
        }));
        dialog.setOnDismissListener(ignored -> { if (scanner != null) { scanner.stop(); scanner = null; } });
        dialog.show();
        for (Device device : Device.getAll()) add.accept(device);
        scan(add);
    }

    private void scan(java.util.function.Consumer<Device> add) {
        if (scanner != null) scanner.stop();
        scanner = new ScanTask(add::accept);
        scanner.start();
    }

    private void start(Device device, List<Download> selected) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding / 2);
        TextView status = new TextView(activity);
        status.setText(R.string.offline_sync_preparing);
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(true);
        content.addView(status);
        content.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog current = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.offline_sync_to, device.getName()))
                .setView(content).setNegativeButton(R.string.offline_cancel, (choice, index) -> { if (send != null) send.cancel(); })
                .setCancelable(false).create();
        dialog = current;
        current.show();
        send = OfflineCacheSync.get(activity).send(device, selected, new OfflineCacheSync.Listener() {
            @Override public void progress(String title, int index, int count, long received, long total) {
                if (!alive() || !current.isShowing()) return;
                status.setText(activity.getString(R.string.offline_sync_progress, index, count, title,
                        Formatter.formatFileSize(activity, received), Formatter.formatFileSize(activity, total)));
                progress.setIndeterminate(total == 0);
                if (total > 0) progress.setProgress((int) Math.min(100, received * 100.0 / total));
            }
            @Override public void complete(int sent, int skipped, String error) {
                send = null;
                if (!alive()) return;
                current.dismiss();
                String result = activity.getString(R.string.offline_sync_result, sent, skipped);
                if (error != null) result += "\n" + activity.getString(error.equals("cancelled") ? R.string.offline_sync_cancelled : R.string.offline_sync_error);
                dialog = new AlertDialog.Builder(activity).setTitle(R.string.offline_sync).setMessage(result)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });
    }

    private static boolean any(boolean[] values) { for (boolean value : values) if (value) return true; return false; }
    private static boolean all(boolean[] values) { for (boolean value : values) if (!value) return false; return true; }

    @Override public void close() {
        closed = true;
        if (scanner != null) scanner.stop();
        if (send != null) send.cancel();
        if (dialog != null) dialog.dismiss();
    }
}

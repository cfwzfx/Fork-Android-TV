package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Device;

/** Presentation of the sender callbacks, without owning or changing the transfer. */
final class OfflineSyncStatus {
    final AlertDialog dialog;
    private final Activity activity;
    private final View content;

    OfflineSyncStatus(Activity activity, Device device, int count, Runnable cancel) {
        this.activity = activity;
        content = LayoutInflater.from(activity).inflate(R.layout.offline_sync_status, null);
        text(R.id.offline_sync_status_heading).setText(R.string.offline_sync_transferring);
        text(R.id.offline_sync_status_target).setText(activity.getString(R.string.offline_sync_to, device.getName()));
        text(R.id.offline_sync_status_title).setText(R.string.offline_sync_preparing);
        text(R.id.offline_sync_status_detail).setText(activity.getString(R.string.offline_sync_episode_progress, 0, count));
        text(R.id.offline_sync_status_bytes).setVisibility(View.GONE);
        progress().setIndeterminate(true);
        dialog = new AlertDialog.Builder(activity).setView(content).setCancelable(false).create();
        text(R.id.offline_sync_status_action).setText(R.string.offline_cancel);
        text(R.id.offline_sync_status_action).setOnClickListener(v -> { cancel.run(); dialog.dismiss(); });
        show();
    }

    private OfflineSyncStatus(Activity activity, Device device, int sent, int skipped, String error) {
        this.activity = activity;
        content = LayoutInflater.from(activity).inflate(R.layout.offline_sync_status, null);
        boolean cancelled = "cancelled".equals(error);
        int heading = error == null ? R.string.offline_sync_finished : cancelled ? R.string.offline_sync_cancelled_heading : R.string.offline_sync_failed_heading;
        text(R.id.offline_sync_status_heading).setText(heading);
        text(R.id.offline_sync_status_target).setText(activity.getString(R.string.offline_sync_to, device.getName()));
        ImageView icon = content.findViewById(R.id.offline_sync_status_icon);
        icon.setImageResource(error == null ? R.drawable.offline_select : R.drawable.offline_close);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(error != null && !cancelled ? R.color.offline_danger : R.color.offline_accent)));
        text(R.id.offline_sync_status_title).setText(activity.getString(R.string.offline_sync_sent_count, sent));
        text(R.id.offline_sync_status_detail).setText(activity.getString(R.string.offline_sync_skipped_count, skipped));
        progress().setVisibility(View.GONE);
        text(R.id.offline_sync_status_bytes).setVisibility(View.GONE);
        if (error != null) {
            text(R.id.offline_sync_status_message).setVisibility(View.VISIBLE);
            text(R.id.offline_sync_status_message).setText(cancelled ? R.string.offline_sync_cancelled : R.string.offline_sync_error);
        }
        dialog = new AlertDialog.Builder(activity).setView(content).create();
        TextView action = text(R.id.offline_sync_status_action);
        action.setText(android.R.string.ok);
        action.setBackgroundResource(R.drawable.offline_button_primary);
        action.setTextColor(Color.parseColor("#101B23"));
        action.setOnClickListener(v -> dialog.dismiss());
        show();
    }

    static AlertDialog result(Activity activity, Device device, int sent, int skipped, String error) {
        return new OfflineSyncStatus(activity, device, sent, skipped, error).dialog;
    }

    void progress(String title, int index, int count, long received, long total) {
        text(R.id.offline_sync_status_title).setText(title);
        text(R.id.offline_sync_status_detail).setText(activity.getString(R.string.offline_sync_episode_progress, index, count));
        text(R.id.offline_sync_status_bytes).setVisibility(View.VISIBLE);
        progress().setIndeterminate(total <= 0);
        if (total > 0) {
            int percent = (int) Math.max(0, Math.min(100, received * 100.0 / total));
            progress().setProgress(percent);
            text(R.id.offline_sync_status_bytes).setText(activity.getString(R.string.offline_sync_transfer_bytes,
                    percent, Formatter.formatFileSize(activity, received), Formatter.formatFileSize(activity, total)));
        } else text(R.id.offline_sync_status_bytes).setText(R.string.offline_sync_preparing);
    }

    private TextView text(int id) { return content.findViewById(id); }
    private ProgressBar progress() { return content.findViewById(R.id.offline_sync_status_progress); }
    private void show() {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(Math.min(Math.round(460 * activity.getResources().getDisplayMetrics().density),
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}

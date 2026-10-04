package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.core.widget.CompoundButtonCompat;
import androidx.media3.exoplayer.offline.Download;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** A selection snapshot only; transfer and storage remain owned by the sync service. */
final class OfflineSyncSelection {
    private static final class Group {
        final OfflineVideo video;
        final List<Download> episodes = new ArrayList<>();
        boolean expanded = true;
        Group(OfflineVideo video) { this.video = video; }
    }

    static AlertDialog show(Activity activity, List<Download> completed, Consumer<List<Download>> next) {
        View content = LayoutInflater.from(activity).inflate(R.layout.offline_sync_selection, null);
        RecyclerView list = content.findViewById(R.id.offline_sync_list);
        TextView count = content.findViewById(R.id.offline_sync_count);
        TextView all = content.findViewById(R.id.offline_sync_all_button);
        View confirm = content.findViewById(R.id.offline_sync_confirm);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(content).create();
        LinkedHashMap<String, Group> grouped = new LinkedHashMap<>();
        for (Download download : completed) {
            OfflineVideo video = OfflineVideo.decode(download.request.data);
            grouped.computeIfAbsent(video.groupKey(), key -> new Group(video)).episodes.add(download);
        }
        Set<String> selected = new HashSet<>();
        List<Group> groups = new ArrayList<>(grouped.values());
        Runnable summary = () -> {
            count.setText(activity.getString(R.string.offline_selected_count, selected.size()));
            confirm.setEnabled(!selected.isEmpty());
            confirm.setAlpha(selected.isEmpty() ? 0.45f : 1f);
            all.setText(selected.size() == completed.size() ? R.string.offline_deselect_all : R.string.offline_select_all);
        };
        class Rows extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            final List<Object> entries = new ArrayList<>();
            void refresh() {
                entries.clear();
                for (Group group : groups) { entries.add(group); if (group.expanded) entries.addAll(group.episodes); }
                notifyDataSetChanged();
                summary.run();
            }
            @Override public int getItemCount() { return entries.size(); }
            @Override public int getItemViewType(int position) { return entries.get(position) instanceof Group ? 0 : 1; }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(activity).inflate(type == 0 ? R.layout.offline_group : R.layout.offline_row, parent, false)) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                View row = holder.itemView;
                AppCompatCheckBox check = row.findViewById(R.id.offline_checked);
                check.setVisibility(View.VISIBLE);
                check.setOnCheckedChangeListener(null);
                CompoundButtonCompat.setButtonTintList(check, null);
                check.setButtonTintList(null);
                Object entry = entries.get(position);
                if (entry instanceof Group group) {
                    int chosen = 0;
                    for (Download download : group.episodes) if (selected.contains(download.request.id)) chosen++;
                    check.setChecked(chosen == group.episodes.size());
                    check.setContentDescription(group.video.title);
                    ((TextView) row.findViewById(R.id.offline_group_name)).setText(group.video.title);
                    long bytes = 0;
                    for (Download download : group.episodes) bytes += download.getBytesDownloaded();
                    ((TextView) row.findViewById(R.id.offline_group_detail)).setText(activity.getString(R.string.offline_sync_group_count, chosen, group.episodes.size(), Formatter.formatFileSize(activity, bytes)));
                    row.findViewById(R.id.offline_group_arrow).setRotation(group.expanded ? 180 : 0);
                    row.setOnClickListener(v -> { group.expanded = !group.expanded; refresh(); });
                    check.setOnCheckedChangeListener((button, checked) -> {
                        for (Download download : group.episodes) { if (checked) selected.add(download.request.id); else selected.remove(download.request.id); }
                        refresh();
                    });
                } else {
                    Download download = (Download) entry;
                    OfflineVideo video = OfflineVideo.decode(download.request.data);
                    check.setChecked(selected.contains(download.request.id));
                    check.setContentDescription(video.episode);
                    ((TextView) row.findViewById(R.id.offline_name)).setText(video.episode);
                    ((TextView) row.findViewById(R.id.offline_episode)).setText(video.line);
                    row.findViewById(R.id.offline_status).setVisibility(View.GONE);
                    ((TextView) row.findViewById(R.id.offline_detail)).setText(Formatter.formatFileSize(activity, download.getBytesDownloaded()));
                    row.findViewById(R.id.offline_action).setVisibility(View.GONE);
                    row.findViewById(R.id.offline_delete).setVisibility(View.GONE);
                    row.findViewById(R.id.offline_progress).setVisibility(View.GONE);
                    row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
                    check.setOnCheckedChangeListener((button, checked) -> {
                        if (checked) selected.add(download.request.id); else selected.remove(download.request.id);
                        refresh();
                    });
                }
            }
        }
        Rows rows = new Rows();
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(rows);
        list.setItemAnimator(null);
        all.setOnClickListener(v -> {
            if (selected.size() == completed.size()) selected.clear();
            else for (Download download : completed) selected.add(download.request.id);
            rows.refresh();
        });
        confirm.setOnClickListener(v -> {
            List<Download> values = new ArrayList<>();
            for (Download download : completed) if (selected.contains(download.request.id)) values.add(download);
            if (values.isEmpty()) return;
            dialog.dismiss();
            next.accept(values);
        });
        content.findViewById(R.id.offline_sync_cancel).setOnClickListener(v -> dialog.dismiss());
        rows.refresh();
        dialog.show();
        if (dialog.getWindow() != null) {
            float density = activity.getResources().getDisplayMetrics().density;
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(Math.min((int) (520 * density), (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f)),
                    Math.min((int) (560 * density), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.82f)));
        }
        return dialog;
    }
}

package com.fongmi.android.tv.offline;

import android.content.Intent;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.media3.exoplayer.offline.Download;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns task polling and actions for both the full page and playback side sheet. */
final class OfflineCacheList {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Rows rows;
    private final OfflineCache cache;
    private final TextView empty, summary;
    private final View emptyContainer;
    private boolean visible;
    private boolean querying;
    private boolean closed;
    private final Runnable refresh = this::refresh;

    OfflineCacheList(Activity activity, View root) {
        this.activity = activity;
        rows = new Rows();
        empty = root.findViewById(R.id.offline_empty);
        emptyContainer = root.findViewById(R.id.offline_empty_container);
        summary = root.findViewById(R.id.offline_summary);
        RecyclerView recycler = root.findViewById(R.id.offline_list);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        recycler.setItemAnimator(null);
        recycler.setAdapter(rows);
        cache = OfflineIntegration.get(activity);
        cache.resumeService();
    }

    void start() {
        if (closed) return;
        visible = true;
        refresh();
    }

    void stop() {
        visible = false;
        handler.removeCallbacks(refresh);
    }

    void close() {
        stop();
        closed = true;
        handler.removeCallbacksAndMessages(null);
        reader.shutdownNow();
    }

    private void updateSummary(List<Download> downloads) {
        int completed = 0, pending = 0;
        long bytes = 0;
        for (Download download : downloads) {
            if (download.state == Download.STATE_COMPLETED && !cache.damaged(download.request.id)) completed++;
            else if (download.state != Download.STATE_REMOVING) pending++;
            bytes += download.getBytesDownloaded();
        }
        summary.setText(activity.getString(R.string.offline_summary, completed, pending,
                Formatter.formatFileSize(activity, bytes)));
    }

    private void refresh() {
        if (!visible || querying || closed) return;
        querying = true;
        reader.execute(() -> {
            try {
                List<Download> downloads = cache.list();
                handler.post(() -> {
                    querying = false;
                    if (!visible || closed || activity.isDestroyed()) return;
                    rows.update(downloads);
                    updateSummary(downloads);
                    empty.setText(R.string.offline_empty);
                    emptyContainer.setVisibility(downloads.isEmpty() ? View.VISIBLE : View.GONE);
                    handler.postDelayed(refresh, 1000);
                });
            } catch (IOException e) {
                handler.post(() -> {
                    querying = false;
                    if (!visible || closed || activity.isDestroyed()) return;
                    empty.setText(R.string.offline_load_error);
                    emptyContainer.setVisibility(View.VISIBLE);
                    handler.postDelayed(refresh, 3000);
                });
            }
        });
    }

    private void action(Download download) {
        switch (download.state) {
            case Download.STATE_COMPLETED:
                if (cache.damaged(download.request.id)) recache(download);
                else OfflineIntegration.play(activity, download.request.id);
                break;
            case Download.STATE_FAILED:
            case Download.STATE_STOPPED:
                cache.continueDownload(download);
                break;
            case Download.STATE_QUEUED:
            case Download.STATE_DOWNLOADING:
            case Download.STATE_RESTARTING:
                cache.pause(download.request.id);
                break;
            case Download.STATE_REMOVING:
                break;
        }
    }

    private void more(Download selected) {
        List<Integer> options = new ArrayList<>();
        if (selected.state == Download.STATE_COMPLETED) options.add(R.string.offline_verify);
        if (selected.state == Download.STATE_COMPLETED || selected.state == Download.STATE_FAILED || selected.state == Download.STATE_STOPPED)
            options.add(R.string.offline_recache);
        options.add(R.string.offline_delete);
        String[] labels = options.stream().map(activity::getString).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle(name(selected)).setItems(labels, (dialog, index) -> {
            int option = options.get(index);
            if (option == R.string.offline_recache) recache(selected);
            else if (option == R.string.offline_verify) {
                Notify.show(R.string.offline_verifying);
                reader.execute(() -> {
                    int result = R.string.offline_verified;
                    try { cache.verify(selected); }
                    catch (IOException error) { result = R.string.offline_integrity_error; }
                    int message = result;
                    handler.post(() -> {
                        if (closed || activity.isDestroyed()) return;
                        new AlertDialog.Builder(activity).setMessage(message).setPositiveButton(android.R.string.ok, null).show();
                        refresh();
                    });
                });
            } else new AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.offline_delete_question, name(selected)))
                    .setNegativeButton(R.string.offline_cancel, null)
                    .setPositiveButton(R.string.offline_delete, (confirm, which) -> cache.remove(selected.request.id)).show();
        }).show();
    }

    private void recache(Download selected) {
        new AlertDialog.Builder(activity).setMessage(activity.getString(R.string.offline_recache_question, name(selected)))
                .setNegativeButton(R.string.offline_cancel, null)
                .setPositiveButton(R.string.offline_recache, (dialog, which) ->
                        OfflineCacheRepair.start(activity.getApplicationContext(), selected, Notify::show)).show();
    }

    private String name(Download download) {
        try {
            OfflineVideo video = OfflineVideo.decode(download.request.data);
            return activity.getString(R.string.offline_video_title, video.title, video.episode);
        } catch (IllegalArgumentException e) {
            return activity.getString(R.string.offline_title);
        }
    }

    private static final class Group {
        final String key, title;
        final List<Download> downloads = new ArrayList<>();
        Group(String key, String title) { this.key = key; this.title = title; }
    }

    private static final class Entry {
        final Group group;
        final Download download;
        Entry(Group group, Download download) { this.group = group; this.download = download; }
        String key() { return download == null ? "group:" + group.key : "episode:" + download.request.id; }
    }

    private final class Rows extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final android.content.SharedPreferences preferences = activity.getSharedPreferences("offline_ui", 0);
        private final java.util.Set<String> expanded = new java.util.HashSet<>(preferences.getStringSet("expanded", java.util.Collections.emptySet()));
        private List<Group> groups = new ArrayList<>();
        private List<Entry> items = new ArrayList<>();

        void update(List<Download> value) {
            java.util.Map<String, Group> directories = new java.util.LinkedHashMap<>();
            for (Download download : value) {
                String key = download.request.id, title = name(download);
                try {
                    OfflineVideo video = OfflineVideo.decode(download.request.data);
                    key = video.groupKey();
                    title = video.title;
                } catch (IllegalArgumentException ignored) {}
                Group group = directories.get(key);
                if (group == null) { group = new Group(key, title); directories.put(key, group); }
                group.downloads.add(download);
            }
            groups = new ArrayList<>(directories.values());
            display();
        }

        private void toggle(String key) {
            if (!expanded.remove(key)) expanded.add(key);
            preferences.edit().putStringSet("expanded", new java.util.HashSet<>(expanded)).apply();
            display();
        }

        private void display() {
            List<Entry> next = new ArrayList<>();
            for (Group group : groups) {
                next.add(new Entry(group, null));
                if (expanded.contains(group.key)) for (Download download : group.downloads) next.add(new Entry(group, download));
            }
            List<Entry> previous = items;
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return previous.size(); }
                @Override public int getNewListSize() { return next.size(); }
                @Override public boolean areItemsTheSame(int oldIndex, int newIndex) {
                    return previous.get(oldIndex).key().equals(next.get(newIndex).key());
                }
                @Override public boolean areContentsTheSame(int oldIndex, int newIndex) { return false; }
            });
            items = next;
            diff.dispatchUpdatesTo(this);
        }

        @Override public int getItemViewType(int position) { return items.get(position).download == null ? 0 : 1; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view = LayoutInflater.from(parent.getContext()).inflate(type == 0 ? R.layout.offline_group : R.layout.offline_row, parent, false);
            return type == 0 ? new GroupRow(view) : new Row(view);
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Entry item = items.get(position);
            if (holder instanceof GroupRow) ((GroupRow) holder).bind(item.group);
            else ((Row) holder).bind(item.download);
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private final class GroupRow extends RecyclerView.ViewHolder {
        private final TextView title, detail;
        private final ImageView arrow;
        private Group group;
        GroupRow(View view) {
            super(view);
            title = view.findViewById(R.id.offline_group_name);
            detail = view.findViewById(R.id.offline_group_detail);
            arrow = view.findViewById(R.id.offline_group_arrow);
            view.setOnClickListener(v -> rows.toggle(group.key));
        }
        void bind(Group value) {
            group = value;
            int completed = 0;
            long bytes = 0;
            for (Download download : group.downloads) {
                if (download.state == Download.STATE_COMPLETED && !cache.damaged(download.request.id)) completed++;
                bytes += download.getBytesDownloaded();
            }
            title.setText(group.title);
            detail.setText(activity.getString(R.string.offline_group_summary, group.downloads.size(), completed, Formatter.formatFileSize(activity, bytes)));
            boolean open = rows.expanded.contains(group.key);
            arrow.setRotation(open ? 180 : 0);
            itemView.setContentDescription(activity.getString(open ? R.string.offline_collapse : R.string.offline_expand, group.title) + ", " + detail.getText());
        }
    }

    private final class Row extends RecyclerView.ViewHolder {
        private final TextView title, episode, status, detail;
        private final ImageButton action, delete;
        private final ProgressBar progress;
        private Download download;

        Row(View view) {
            super(view);
            title = view.findViewById(R.id.offline_name);
            episode = view.findViewById(R.id.offline_episode);
            status = view.findViewById(R.id.offline_status);
            detail = view.findViewById(R.id.offline_detail);
            action = view.findViewById(R.id.offline_action);
            delete = view.findViewById(R.id.offline_delete);
            progress = view.findViewById(R.id.offline_progress);
            androidx.appcompat.widget.TooltipCompat.setTooltipText(delete, activity.getString(R.string.offline_more));
            action.setOnClickListener(v -> OfflineCacheList.this.action(download));
            delete.setOnClickListener(v -> more(download));
            view.setOnLongClickListener(v -> { if (!OfflineCacheRepair.busy(download.request.id)) more(download); return true; });
        }

        void bind(Download value) {
            download = value;
            try {
                OfflineVideo video = OfflineVideo.decode(value.request.data);
                title.setText(video.episode.isEmpty() ? video.title : video.episode);
                episode.setText(video.line);
            } catch (IllegalArgumentException e) {
                title.setText(name(value));
                episode.setText("");
            }
            episode.setVisibility(episode.getText().length() == 0 ? View.GONE : View.VISIBLE);
            int state = R.string.offline_waiting, button = R.string.offline_pause;
            switch (value.state) {
                case Download.STATE_QUEUED:
                case Download.STATE_RESTARTING: break;
                case Download.STATE_DOWNLOADING: state = R.string.offline_downloading; break;
                case Download.STATE_COMPLETED: state = R.string.offline_completed; button = R.string.offline_play; break;
                case Download.STATE_STOPPED: state = R.string.offline_paused; button = R.string.offline_continue; break;
                case Download.STATE_FAILED: state = R.string.offline_failed; button = R.string.offline_retry; break;
                case Download.STATE_REMOVING: state = R.string.offline_removing; break;
            }
            boolean damaged = cache.damaged(value.request.id);
            if (damaged && value.state == Download.STATE_COMPLETED) { state = R.string.offline_incomplete; button = R.string.offline_recache; }
            float percent = value.getPercentDownloaded();
            status.setText(state);
            int color = value.state == Download.STATE_COMPLETED && !damaged ? R.color.offline_success
                    : value.state == Download.STATE_FAILED || damaged ? R.color.offline_danger : R.color.offline_accent;
            status.setTextColor(activity.getColor(color));
            String text = Formatter.formatFileSize(activity, value.getBytesDownloaded());
            if (percent >= 0 && value.state != Download.STATE_COMPLETED) text += " · " + (int) percent + "%";
            if (value.state == Download.STATE_FAILED || damaged) text += "\n" + activity.getString(cache.failure(value.request.id));
            detail.setText(text);
            progress.setVisibility(value.state == Download.STATE_COMPLETED ? View.GONE : View.VISIBLE);
            progress.setIndeterminate(percent < 0 && value.state == Download.STATE_DOWNLOADING);
            progress.setProgress(Math.max(0, (int) percent));
            int icon = button == R.string.offline_pause ? R.drawable.offline_pause
                    : button == R.string.offline_retry || button == R.string.offline_recache ? R.drawable.offline_retry : R.drawable.offline_play;
            action.setImageResource(icon);
            action.setContentDescription(activity.getString(button));
            androidx.appcompat.widget.TooltipCompat.setTooltipText(action, activity.getString(button));
            action.setEnabled(value.state != Download.STATE_REMOVING && !OfflineCacheRepair.busy(value.request.id));
            delete.setEnabled(value.state != Download.STATE_REMOVING && !OfflineCacheRepair.busy(value.request.id));
            action.setAlpha(action.isEnabled() ? 1f : 0.5f);
            delete.setAlpha(delete.isEnabled() ? 1f : 0.5f);
        }
    }
}

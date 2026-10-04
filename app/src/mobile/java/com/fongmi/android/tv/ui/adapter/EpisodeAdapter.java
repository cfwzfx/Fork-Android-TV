package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeHoriBinding;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.holder.EpisodeGridHolder;
import com.fongmi.android.tv.ui.holder.EpisodeHoriHolder;

import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<BaseEpisodeHolder> {

    private final OnClickListener listener;
    private final List<Episode> mItems;
    private final int viewType;
    private java.util.function.Consumer<Episode> cache;

    private java.util.Map<String, Integer> cacheStates = java.util.Collections.emptyMap();

    public void cacheStates(java.util.Map<String, Integer> states) {
        if (cacheStates.equals(states)) return;
        cacheStates = new java.util.HashMap<>(states);
        notifyDataSetChanged();
    }

    public EpisodeAdapter cache(java.util.function.Consumer<Episode> callback) {
        cache = callback;
        return this;
    }

    public EpisodeAdapter(OnClickListener listener, int viewType) {
        this(listener, viewType, new ArrayList<>());
    }

    public EpisodeAdapter(OnClickListener listener, int viewType, ArrayList<Episode> items) {
        this.listener = listener;
        this.viewType = viewType;
        this.mItems = items;
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public int getPosition(Episode item) {
        return mItems.indexOf(item);
    }

    public Episode getActivated() {
        return mItems.get(getPosition());
    }

    public Episode getNext() {
        int current = getPosition();
        int max = getItemCount() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    public List<Episode> getItems() {
        return mItems;
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return viewType;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseEpisodeHolder holder, int position) {
        holder.initView(mItems.get(position));
    }

    @NonNull
    @Override
    public BaseEpisodeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (cache != null) {
            com.fongmi.android.tv.databinding.AdapterEpisodeCacheBinding binding =
                    com.fongmi.android.tv.databinding.AdapterEpisodeCacheBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new BaseEpisodeHolder(binding.getRoot()) {
                @Override public void initView(Episode item) {
                    binding.text.setText(item.getDesc().concat(item.getName()));
                    binding.getRoot().setSelected(item.isSelected());
                    binding.text.setSelected(item.isSelected());
                    binding.text.setOnClickListener(v -> listener.onItemClick(item));
                    String key = item.getName().trim().isEmpty() ? item.getUrl() : item.getName();
                    Integer state = cacheStates.get(key);
                    boolean completed = state != null && state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED;
                    boolean busy = state != null && (state == androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING
                            || state == androidx.media3.exoplayer.offline.Download.STATE_QUEUED || state == androidx.media3.exoplayer.offline.Download.STATE_RESTARTING);
                    int label = completed ? com.fongmi.android.tv.R.string.offline_episode_cached : busy ? com.fongmi.android.tv.R.string.offline_downloading
                            : state != null && state == androidx.media3.exoplayer.offline.Download.STATE_STOPPED ? com.fongmi.android.tv.R.string.offline_paused
                            : state != null && state == androidx.media3.exoplayer.offline.Download.STATE_FAILED ? com.fongmi.android.tv.R.string.offline_failed
                            : com.fongmi.android.tv.R.string.offline_episode_download;
                    binding.episodeCache.setImageResource(completed ? com.fongmi.android.tv.R.drawable.offline_select
                            : busy ? com.fongmi.android.tv.R.drawable.offline_sync : com.fongmi.android.tv.R.drawable.offline_download);
                    android.content.res.ColorStateList color = completed ? android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#28865A"))
                            : androidx.appcompat.content.res.AppCompatResources.getColorStateList(binding.getRoot().getContext(), com.fongmi.android.tv.R.color.selector_control);
                    binding.episodeCache.setImageTintList(color);
                    binding.episodeCacheArea.setSelected(item.isSelected());
                    binding.episodeCacheArea.setContentDescription(binding.getRoot().getContext().getString(label) + " · " + item.getName());
                    binding.episodeCacheArea.setOnClickListener(v -> {
                        if (completed || busy) com.fongmi.android.tv.utils.Notify.show(item.getName() + " · " + binding.getRoot().getContext().getString(label));
                        else cache.accept(item);
                    });
                    binding.episodeCacheArea.setEnabled(true);
                    binding.episodeCache.setEnabled(true);
                }
            };
        }
        if (viewType == ViewType.HORI) {
            return new EpisodeHoriHolder(AdapterEpisodeHoriBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        } else {
            return new EpisodeGridHolder(AdapterEpisodeGridBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        }
    }
}
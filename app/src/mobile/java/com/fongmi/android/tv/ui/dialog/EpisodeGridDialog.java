package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.DialogEpisodeGridBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.fragment.EpisodeFragment;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.offline.OfflineIntegration;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public class EpisodeGridDialog extends BaseBottomSheetDialog {

    private final List<String> titles;
    private DialogEpisodeGridBinding binding;
    private List<Episode> episodes;
    private boolean reverse;
    private int spanCount;
    private int itemCount;
    private History cacheHistory;
    private Map<String, Integer> cacheStates = Collections.emptyMap();
    private final Handler cacheHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshCache = this::refreshCacheStates;
    private int refreshGeneration;

    public EpisodeGridDialog() {
        this.titles = new ArrayList<>();
        this.spanCount = 5;
    }

    public static EpisodeGridDialog create() {
        return new EpisodeGridDialog();
    }

    public EpisodeGridDialog reverse(boolean reverse) {
        this.reverse = reverse;
        arguments().putBoolean("reverse", reverse);
        return this;
    }

    public EpisodeGridDialog episodes(List<Episode> episodes) {
        this.episodes = new ArrayList<>(episodes);
        arguments().putParcelableArrayList("episodes", new ArrayList<>(episodes));
        return this;
    }

    public EpisodeGridDialog cacheHistory(History history) {
        cacheHistory = history == null ? null : history.copy();
        arguments().putString("cache_history", history == null ? null : history.toString());
        return this;
    }

    private Bundle arguments() {
        if (getArguments() == null) setArguments(new Bundle());
        return requireArguments();
    }

    public boolean isCacheEnabled() {
        return cacheHistory != null;
    }

    public Map<String, Integer> getCacheStates() {
        return cacheStates;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = arguments();
        if (episodes == null) episodes = args.getParcelableArrayList("episodes");
        if (episodes == null) episodes = Collections.emptyList();
        reverse = args.getBoolean("reverse", reverse);
        String history = args.getString("cache_history");
        if (history != null) cacheHistory = History.objectFrom(history);
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof EpisodeGridDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogEpisodeGridBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setSpanCount();
        setTitles();
        setPager();
    }

    @Override
    protected void initEvent() {
        getChildFragmentManager().setFragmentResultListener("result", this, (requestKey, bundle) -> {
            Episode episode = bundle.getParcelable("episode");
            ((EpisodeAdapter.OnClickListener) requireActivity()).onItemClick(episode);
            dismiss();
        });
    }

    public void cacheEpisode(Episode episode) {
        if (cacheHistory == null || episode == null) return;
        History snapshot = cacheHistory.copy();
        snapshot.setVodRemarks(episode.getName());
        snapshot.setEpisodeUrl(episode.getUrl());
        if (!episode.getUrl().equals(cacheHistory.getEpisodeUrl())) {
            snapshot.setPosition(0);
            snapshot.setDuration(androidx.media3.common.C.TIME_UNSET);
        }
        OfflineIntegration.cacheEpisode(requireContext(), snapshot);
    }

    @Override public void onStart() {
        super.onStart();
        refreshCacheStates();
    }

    @Override public void onStop() {
        stopCacheRefresh();
        super.onStop();
    }

    @Override public void onDestroyView() {
        stopCacheRefresh();
        binding = null;
        super.onDestroyView();
    }

    private void stopCacheRefresh() {
        refreshGeneration++;
        cacheHandler.removeCallbacks(refreshCache);
    }

    private void refreshCacheStates() {
        if (binding == null || cacheHistory == null) return;
        int generation = refreshGeneration;
        android.content.Context context = requireContext().getApplicationContext();
        History history = cacheHistory.copy();
        Task.execute(() -> {
            Map<String, Integer> states = null;
            try { states = OfflineIntegration.episodeStates(context, history); }
            catch (java.io.IOException ignored) {}
            Map<String, Integer> result = states;
            cacheHandler.post(() -> {
                if (binding == null || generation != refreshGeneration) return;
                if (result != null) {
                    cacheStates = result;
                    for (Fragment fragment : getChildFragmentManager().getFragments()) {
                        if (fragment instanceof EpisodeFragment page) page.cacheStates(cacheStates);
                    }
                }
                cacheHandler.postDelayed(refreshCache, 1000);
            });
        });
    }

    private void setSpanCount() {
        if (arguments().containsKey("span_count")) {
            spanCount = arguments().getInt("span_count");
            itemCount = arguments().getInt("item_count");
            return;
        }
        int avg = (int) Math.ceil(episodes.stream().mapToInt(e -> e.getName().length()).average().orElse(0));
        if (avg >= 12) spanCount = 1;
        else if (avg >= 8) spanCount = 2;
        else if (avg >= 4) spanCount = 3;
        else if (avg >= 2) spanCount = 4;
        if (cacheHistory != null) {
            int width = episodes.stream().mapToInt(e -> ResUtil.getTextWidth(e.getDesc().concat(e.getName()), 14)).max().orElse(0);
            // Include the text padding, divider, cache touch area and item margins.
            int cellWidth = width + ResUtil.dp2px(16 + 1 + 44 + 12);
            spanCount = Math.min(spanCount, Math.max(1, (ResUtil.getScreenWidth() - ResUtil.dp2px(20)) / cellWidth));
        }
        itemCount = spanCount * (ResUtil.isLand(requireActivity()) ? 5 : 10);
        arguments().putInt("span_count", spanCount);
        arguments().putInt("item_count", itemCount);
    }

    private void setTitles() {
        titles.clear();
        if (reverse) for (int i = episodes.size(); i > 0; i -= itemCount) titles.add(i + " - " + Math.max(i - itemCount - 1, 1));
        else for (int i = 0; i < episodes.size(); i += itemCount) titles.add((i + 1) + " - " + Math.min(i + itemCount, episodes.size()));
    }

    private void setPager() {
        binding.pager.setAdapter(new PageAdapter(this));
        new TabLayoutMediator(binding.tabs, binding.pager, (tab, position) -> tab.setText(titles.get(position))).attach();
        setCurrentPage();
    }

    private void setCurrentPage() {
        for (int i = 0; i < episodes.size(); i++) {
            if (episodes.get(i).isSelected()) {
                binding.pager.setCurrentItem(i / itemCount);
                break;
            }
        }
    }

    class PageAdapter extends FragmentStateAdapter {

        public PageAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return EpisodeFragment.newInstance(spanCount, episodes.subList(position * itemCount, Math.min(position * itemCount + itemCount, episodes.size())));
        }

        @Override
        public int getItemCount() {
            return titles.size();
        }
    }
}

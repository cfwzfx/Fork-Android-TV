package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.FragmentEpisodeBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.base.ViewType;

import java.util.ArrayList;
import java.util.List;

public class EpisodeFragment extends BaseFragment implements EpisodeAdapter.OnClickListener {

    private FragmentEpisodeBinding mBinding;
    private EpisodeAdapter adapter;

    private int getSpanCount() {
        return getArguments().getInt("spanCount");
    }

    private ArrayList<Episode> getItems() {
        return getArguments().getParcelableArrayList("items");
    }

    public static EpisodeFragment newInstance(int spanCount, List<Episode> items) {
        Bundle args = new Bundle();
        args.putInt("spanCount", spanCount);
        args.putParcelableArrayList("items", new ArrayList<>(items));
        EpisodeFragment fragment = new EpisodeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentEpisodeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(getContext(), getSpanCount()));
        adapter = new EpisodeAdapter(this, ViewType.GRID, getItems()).singleLine();
        if (getParentFragment() instanceof com.fongmi.android.tv.ui.dialog.EpisodeGridDialog dialog) {
            if (dialog.isCacheEnabled()) adapter.cache(dialog::cacheEpisode);
            adapter.cacheStates(dialog.getCacheStates());
        }
        mBinding.recycler.setAdapter(adapter);
        mBinding.recycler.scrollToPosition(adapter.getPosition());
    }

    public void cacheStates(java.util.Map<String, Integer> states) {
        if (adapter != null) adapter.cacheStates(states);
    }

    @Override
    public void onDestroyView() {
        mBinding = null;
        adapter = null;
        super.onDestroyView();
    }

    @Override
    public void onItemClick(Episode item) {
        Bundle result = new Bundle();
        result.putParcelable("episode", item);
        getParentFragmentManager().setFragmentResult("result", result);
    }
}

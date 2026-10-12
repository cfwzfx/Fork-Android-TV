package com.fongmi.android.tv.offline;

import android.app.Dialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.sidesheet.SideSheetDialog;

/** Playback stays in the host activity while the shared cache list is open. */
public final class OfflineCacheDialog extends DialogFragment {
    private OfflineCacheList list;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle state) {
        if (!ResUtil.isLand(requireContext())) {
            BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
            dialog.getBehavior().setDraggable(false);
            dialog.getBehavior().setSkipCollapsed(true);
            return dialog;
        }
        SideSheetDialog dialog = new SideSheetDialog(requireContext());
        dialog.getBehavior().setDraggable(false);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        return inflater.inflate(R.layout.offline_cache, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        OfflineCacheActivity.applyInsets(view, !(requireDialog() instanceof BottomSheetDialog));
        view.findViewById(R.id.offline_settings_button).setVisibility(View.GONE);
        view.findViewById(R.id.offline_back).setOnClickListener(v -> dismiss());
        view.<android.widget.ImageButton>findViewById(R.id.offline_back).setImageResource(R.drawable.offline_close);
        view.findViewById(R.id.offline_back).setContentDescription(getString(R.string.offline_close));
        list = new OfflineCacheList(requireActivity(), view);
        list.restore(state);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (list != null) list.save(state);
    }

    @Override
    public void onStart() {
        super.onStart();
        resizeSheet();
        list.start();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (getView() != null) resizeSheet();
    }

    private void resizeSheet() {
        View sheet = (View) requireView().getParent();
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        boolean portrait = requireDialog() instanceof BottomSheetDialog;
        params.width = portrait ? ViewGroup.LayoutParams.MATCH_PARENT : Math.min((int) (420 * getResources().getDisplayMetrics().density),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.92f));
        params.height = portrait ? (int) (ResUtil.getScreenHeight(requireContext()) * 0.65f) : ViewGroup.LayoutParams.MATCH_PARENT;
        sheet.setLayoutParams(params);
        if (portrait) {
            ((BottomSheetDialog) requireDialog()).getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        } else requireDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    public void onStop() {
        list.stop();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        list.close();
        list = null;
        super.onDestroyView();
    }
}

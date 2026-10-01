package com.fongmi.android.tv.offline;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.fongmi.android.tv.R;
import com.google.android.material.sidesheet.SideSheetDialog;

/** Playback stays in the host activity while the shared cache list is open. */
public final class OfflineCacheDialog extends DialogFragment {
    private OfflineCacheList list;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle state) {
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
        OfflineCacheActivity.applyInsets(view);
        view.findViewById(R.id.offline_back).setOnClickListener(v -> dismiss());
        view.<android.widget.ImageButton>findViewById(R.id.offline_back).setImageResource(R.drawable.offline_close);
        view.findViewById(R.id.offline_back).setContentDescription(getString(R.string.offline_close));
        list = new OfflineCacheList(requireActivity(), view);
    }

    @Override
    public void onStart() {
        super.onStart();
        View sheet = (View) requireView().getParent();
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.width = Math.min((int) (420 * getResources().getDisplayMetrics().density),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.92f));
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        sheet.setLayoutParams(params);
        requireDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        list.start();
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

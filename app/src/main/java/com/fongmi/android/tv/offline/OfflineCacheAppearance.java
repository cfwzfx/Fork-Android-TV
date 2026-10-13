package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

/** Refreshes presentation without replacing views, listeners or task state. */
final class OfflineCacheAppearance {
    static void refresh(Activity activity, View root, int layout) {
        android.view.ContextThemeWrapper theme = new android.view.ContextThemeWrapper(activity, 0);
        theme.getTheme().setTo(activity.getTheme());
        copy(root, LayoutInflater.from(activity).cloneInContext(theme).inflate(layout, null));
    }

    private static void copy(View target, View styled) {
        if (target instanceof MaterialButton button && styled instanceof MaterialButton model) {
            button.setBackgroundTintList(model.getBackgroundTintList());
            button.setStrokeColor(model.getStrokeColor());
            button.setRippleColor(model.getRippleColor());
        } else if (styled.getBackground() != null) {
            int left = target.getPaddingLeft(), top = target.getPaddingTop();
            int right = target.getPaddingRight(), bottom = target.getPaddingBottom();
            target.setBackground(styled.getBackground());
            target.setPadding(left, top, right, bottom);
        }
        if (target instanceof TextView text && styled instanceof TextView model)
            text.setTextColor(model.getTextColors());
        if (target instanceof ImageView image && styled instanceof ImageView model)
            image.setImageTintList(model.getImageTintList());
        if (target instanceof CompoundButton button && styled instanceof CompoundButton model) {
            button.setButtonDrawable(model.getButtonDrawable());
            button.setButtonTintList(model.getButtonTintList());
        }
        if (target instanceof ProgressBar progress && styled instanceof ProgressBar model) {
            progress.setProgressTintList(model.getProgressTintList());
            progress.setProgressBackgroundTintList(model.getProgressBackgroundTintList());
            progress.setIndeterminateTintList(model.getIndeterminateTintList());
        }
        if (target instanceof RecyclerView) return;
        if (target instanceof ViewGroup group && styled instanceof ViewGroup model)
            for (int i = 0; i < Math.min(group.getChildCount(), model.getChildCount()); i++)
                copy(group.getChildAt(i), model.getChildAt(i));
    }
}

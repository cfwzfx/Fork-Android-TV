package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;

/** Shared presentation for cache confirmations and inspection results. */
final class OfflineCachePrompt {
    static AlertDialog show(Activity activity, int title, String message, int positive, Runnable action) {
        boolean danger = positive == R.string.offline_delete;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 20));
        content.setBackgroundResource(R.drawable.offline_card);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(danger ? R.drawable.offline_delete : title == R.string.offline_recache
                ? R.drawable.offline_retry : R.drawable.offline_select);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(
                danger ? R.color.offline_danger : R.color.offline_accent)));
        icon.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)));
        TextView heading = text(activity, activity.getString(title), 18, R.color.offline_text);
        heading.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.setMarginStart(dp(activity, 12));
        header.addView(heading, headingParams);
        content.addView(header);
        ScrollView scroll = new ScrollView(activity);
        TextView description = text(activity, message, 14, R.color.offline_secondary);
        description.setLineSpacing(dp(activity, 4), 1);
        scroll.addView(description);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.topMargin = dp(activity, 18);
        messageParams.bottomMargin = dp(activity, 22);
        content.addView(scroll, messageParams);
        LinearLayout footer = new LinearLayout(activity);
        footer.setGravity(Gravity.END);
        content.addView(footer);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(content).create();
        if (action != null) {
            TextView cancel = button(activity, R.string.offline_cancel, android.R.id.button2, false, false);
            cancel.setOnClickListener(v -> dialog.dismiss());
            footer.addView(cancel, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        }
        TextView confirm = button(activity, positive, android.R.id.button1, true, danger);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, dp(activity, 44), 1);
        if (action != null) confirmParams.setMarginStart(dp(activity, 12));
        footer.addView(confirm, confirmParams);
        confirm.setOnClickListener(v -> { dialog.dismiss(); if (action != null) action.run(); });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(Math.min(dp(activity, 400), (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.9f)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        // Long episode names remain readable without pushing actions off a short landscape screen.
        int limit = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.4f);
        scroll.post(() -> { if (scroll.getHeight() > limit) { scroll.getLayoutParams().height = limit; scroll.requestLayout(); } });
        return dialog;
    }

    private static TextView button(Activity activity, int label, int id, boolean primary, boolean danger) {
        TextView button = text(activity, activity.getString(label), 14,
                primary ? R.color.offline_background : R.color.offline_text);
        button.setId(id);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(null, Typeface.BOLD);
        button.setFocusable(true);
        button.setBackgroundResource(danger ? R.drawable.offline_button_delete : primary
                ? R.drawable.offline_button_primary : R.drawable.offline_button_secondary);
        return button;
    }

    private static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(activity.getColor(color));
        return view;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

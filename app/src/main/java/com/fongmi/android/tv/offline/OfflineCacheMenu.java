package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;

import com.fongmi.android.tv.R;

import java.util.List;
import java.util.function.IntConsumer;

/** Cache actions use the same fixed dark palette as the list, independent of the app theme. */
final class OfflineCacheMenu {
    static void show(Activity activity, String name, List<Integer> options, IntConsumer action) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
        content.setBackgroundResource(R.drawable.offline_card);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text(activity, activity.getString(R.string.offline_actions), 18, R.color.offline_text);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        AppCompatImageButton close = new AppCompatImageButton(activity);
        close.setImageResource(R.drawable.offline_close);
        close.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.offline_secondary)));
        close.setBackgroundResource(R.drawable.offline_button_secondary);
        close.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        close.setContentDescription(activity.getString(R.string.offline_close));
        header.addView(close, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));
        content.addView(header);
        TextView title = text(activity, name, 12, R.color.offline_secondary);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(activity, 8);
        titleParams.bottomMargin = dp(activity, 10);
        content.addView(title, titleParams);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(content).create();
        close.setOnClickListener(v -> dialog.dismiss());
        for (int option : options) {
            boolean delete = option == R.string.offline_delete;
            int color = delete ? R.color.offline_danger : R.color.offline_text;
            int icon = delete ? R.drawable.offline_delete : option == R.string.offline_verify
                    ? R.drawable.offline_select : R.drawable.offline_retry;
            int description = delete ? R.string.offline_delete_hint : option == R.string.offline_verify
                    ? R.string.offline_verify_hint : R.string.offline_recache_hint;
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
            row.setBackgroundResource(R.drawable.offline_button_secondary);
            row.setFocusable(true);
            ImageView image = new ImageView(activity);
            image.setImageResource(icon);
            image.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(delete ? color : R.color.offline_accent)));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(image, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)));
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView label = text(activity, activity.getString(option), 14, color);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            label.setOnClickListener(v -> row.performClick());
            labels.addView(label);
            TextView hint = text(activity, activity.getString(description), 12, R.color.offline_secondary);
            hint.setMaxLines(2);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
            hintParams.topMargin = dp(activity, 3);
            labels.addView(hint, hintParams);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelParams.setMarginStart(dp(activity, 12));
            row.addView(labels, labelParams);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(activity, 8);
            content.addView(row, rowParams);
            row.setOnClickListener(v -> { dialog.dismiss(); action.accept(option); });
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(Math.min(dp(activity, 360), (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.9f)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static TextView text(Activity activity, String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(activity.getColor(color));
        return text;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

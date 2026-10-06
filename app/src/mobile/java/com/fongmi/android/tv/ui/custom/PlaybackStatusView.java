package com.fongmi.android.tv.ui.custom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;

/** Small status readout whose lifetime follows the visible playback controls. */
public class PlaybackStatusView extends LinearLayout {
    private final TextView battery;
    private boolean listening;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateBattery(intent); }
    };

    public PlaybackStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_playback_status, this);
        battery = findViewById(R.id.playback_battery);
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && !listening) {
            Intent current = ContextCompat.registerReceiver(getContext(), receiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
            listening = true;
            updateBattery(current);
        } else if (!visible) stopListening();
    }

    @Override protected void onDetachedFromWindow() {
        stopListening();
        super.onDetachedFromWindow();
    }

    private void stopListening() {
        if (!listening) return;
        getContext().unregisterReceiver(receiver);
        listening = false;
    }

    private void updateBattery(Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        battery.setText(level >= 0 && scale > 0 ? Math.min(100, Math.round(level * 100f / scale)) + "%" : "—");
    }
}

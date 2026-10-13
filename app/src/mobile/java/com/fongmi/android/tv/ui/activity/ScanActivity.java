package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;

/** Retains the scanner entry point without requesting camera access. */
public class ScanActivity extends AppCompatActivity {
    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, ScanActivity.class));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notify.show(R.string.feature_not_available);
        finish();
    }
}

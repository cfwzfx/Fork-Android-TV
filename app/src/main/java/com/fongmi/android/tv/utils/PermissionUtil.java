package com.fongmi.android.tv.utils;

import android.Manifest;
import android.os.Build;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.PermissionCallback;
import com.fongmi.android.tv.R;
import com.permissionx.guolindev.PermissionX;

import java.util.function.Consumer;

public class PermissionUtil {

    public static void requestAudio(FragmentActivity activity, Consumer<Boolean> callback) {
        if (isGranted(activity, Manifest.permission.RECORD_AUDIO)) callback.accept(true);
        else post(activity, () -> PermissionX.init(activity).permissions(Manifest.permission.RECORD_AUDIO).request(new PermissionCallback(callback)));
    }

    public static void requestFile(FragmentActivity activity, Consumer<Boolean> callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            post(activity, () -> Notify.show(R.string.feature_not_available));
            return;
        }
        if (isGranted(activity, storagePermission())) callback.accept(true);
        else post(activity, () -> PermissionX.init(activity).permissions(storagePermission()).request(new PermissionCallback(callback)));
    }

    public static void requestFile(Fragment fragment, Consumer<Boolean> callback) {
        FragmentActivity activity = fragment.requireActivity();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            post(fragment, () -> Notify.show(R.string.feature_not_available));
            return;
        }
        if (isGranted(activity, storagePermission())) callback.accept(true);
        else post(fragment, () -> PermissionX.init(fragment).permissions(storagePermission()).request(new PermissionCallback(callback)));
    }

    public static void requestNotify(FragmentActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (isGranted(activity, PermissionX.permission.POST_NOTIFICATIONS)) return;
        post(activity, () -> PermissionX.init(activity).permissions(PermissionX.permission.POST_NOTIFICATIONS).request(new PermissionCallback()));
    }

    private static String storagePermission() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ? Manifest.permission.WRITE_EXTERNAL_STORAGE : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private static boolean isGranted(FragmentActivity activity, String permission) {
        return PermissionX.isGranted(activity, permission);
    }

    private static void post(FragmentActivity activity, Runnable runnable) {
        post(activity, null, runnable);
    }

    private static void post(Fragment fragment, Runnable runnable) {
        post(fragment.requireActivity(), fragment, runnable);
    }

    private static void post(FragmentActivity activity, Fragment fragment, Runnable runnable) {
        activity.getWindow().getDecorView().post(() -> {
            if (isActive(activity, fragment)) runnable.run();
        });
    }

    private static boolean isActive(FragmentActivity activity, Fragment fragment) {
        return !activity.isFinishing() && !activity.isDestroyed() && (fragment == null || fragment.isAdded());
    }
}

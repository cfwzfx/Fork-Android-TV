package com.fongmi.android.tv.player.compat;

import android.graphics.Bitmap;

/** Uses libass exported by the project's existing libmpv.so. */
public final class NativeAss implements AutoCloseable {
    private static final boolean AVAILABLE;
    static {
        boolean available;
        try {
            System.loadLibrary("player_ass_bridge");
            available = available();
        } catch (LinkageError error) {
            available = false;
        }
        AVAILABLE = available;
    }

    private long handle;
    public NativeAss(String fontConfig, String fontsDirectory, String family) {
        handle = create(fontConfig, fontsDirectory, family);
        if (handle == 0) throw new IllegalStateException("libass initialization failed");
    }
    public static boolean isAvailable() { return AVAILABLE; }
    public void feed(byte[] data) {
        if (handle != 0) feed(handle, data);
    }
    public void configure(int width, int height, double scale, String styleOverrides) {
        if (handle != 0) configure(handle, width, height, scale, styleOverrides);
    }
    public Frame render(long timeMs) { return handle == 0 ? null : render(handle, timeMs); }
    @Override public void close() {
        if (handle != 0) { destroy(handle); handle = 0; }
    }
    public static final class Frame {
        public final Bitmap bitmap;
        public final int x, y, width, height;
        public Frame(Bitmap bitmap, int x, int y, int width, int height) {
            this.bitmap = bitmap; this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }
    private static native boolean available();
    private static native long create(String config, String directory, String family);
    private static native void destroy(long handle);
    private static native void feed(long handle, byte[] data);
    private static native void configure(long handle, int width, int height, double scale, String overrides);
    private static native Frame render(long handle, long timeMs);
}

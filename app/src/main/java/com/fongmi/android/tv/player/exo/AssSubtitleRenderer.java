package com.fongmi.android.tv.player.exo;

import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.text.TextOutput;
import com.fongmi.android.tv.player.compat.NativeAss;
import com.fongmi.android.tv.player.subtitle.AndroidFontConfig;
import com.fongmi.android.tv.player.subtitle.ExternalFont;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A clock-driven libass renderer using the native library already packaged for mpv. */
final class AssSubtitleRenderer extends BaseRenderer {
    record Presentation(int width, int height, double scale, String overrides, float position, boolean secondary) {}
    private static final Pattern END = Pattern.compile("(?m)^Dialogue:[^,]*,[^,]*,(\\d+):(\\d+):(\\d+)[.:](\\d+),");
    private final TextOutput output;
    private final Handler handler;
    private final String fontConfig;
    private final String fontsDirectory;
    private final DecoderInputBuffer buffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    private volatile Presentation presentation;
    private NativeAss ass;
    private Format format;
    private boolean inputEnded;
    private long endUs, positionUs, textOffsetMs;
    private volatile int outputGeneration;

    AssSubtitleRenderer(TextOutput output, Looper looper, boolean secondary) {
        super(C.TRACK_TYPE_TEXT);
        this.output = output; handler = new Handler(looper);
        File config = AndroidFontConfig.prepare();
        fontConfig = config == null ? null : config.getAbsolutePath();
        fontsDirectory = ExternalFont.getDirectory().getAbsolutePath();
        presentation = new Presentation(1280, 720, 1, "", 0, secondary);
    }
    void setPresentation(Presentation value) { presentation = value; }
    @Override public String getName() { return presentation.secondary ? "SecondaryAssRenderer" : "AssRenderer"; }
    @Override public int supportsFormat(Format value) {
        return RendererCapabilities.create(MimeTypes.TEXT_SSA.equals(value.sampleMimeType) && NativeAss.isAvailable() ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_TYPE);
    }
    @Override public boolean isReady() { return true; }
    @Override public boolean isEnded() { return inputEnded && positionUs >= endUs + getStreamOffsetUs() + textOffsetMs * 1000; }
    @Override protected void onStreamChanged(Format[] formats, long start, long offset, MediaPeriodId period) {
        format = formats[0]; resetAss();
    }
    @Override protected void onPositionReset(long position, boolean joining, boolean resetToKeyframe) { resetAss(); }
    @Override protected void onDisabled() { clear(); if (ass != null) ass.close(); ass = null; format = null; }
    @Override public void handleMessage(int type, Object message) throws ExoPlaybackException {
        if (type == MSG_SET_TEXT_OFFSET) textOffsetMs = (Long) message;
        else super.handleMessage(type, message);
    }
    private void resetAss() {
        clear(); if (ass != null) ass.close(); ass = null; inputEnded = false; endUs = 0;
    }
    private void prepare() {
        if (ass != null) return;
        ass = new NativeAss(fontConfig, fontsDirectory, null);
        if (format != null && format.initializationData.size() >= 2) {
            String header = new String(format.initializationData.get(1), StandardCharsets.UTF_8);
            String events = new String(format.initializationData.get(0), StandardCharsets.UTF_8);
            ass.feed((header + "\n[Events]\n" + events + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
    @Override public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        this.positionUs = positionUs;
        try {
            prepare();
            for (int n = 0; n < 64 && !inputEnded; n++) {
                buffer.clear(); FormatHolder holder = getFormatHolder();
                int result = readSource(holder, buffer, 0);
                if (result == C.RESULT_NOTHING_READ) break;
                if (result == C.RESULT_FORMAT_READ) {
                    if (!holder.format.equals(format)) { format = holder.format; resetAss(); prepare(); }
                    continue;
                }
                if (buffer.isEndOfStream()) { inputEnded = true; break; }
                buffer.flip();
                if (buffer.data == null) continue;
                byte[] bytes = new byte[buffer.data.remaining()]; buffer.data.get(bytes);
                if (format != null && format.initializationData.size() >= 2
                        && new String(format.initializationData.get(0), StandardCharsets.UTF_8).startsWith("Format: Start, End, ReadOrder")) {
                    bytes = absolutizeMatroskaSample(bytes, buffer.timeUs - getStreamOffsetUs());
                }
                ass.feed(bytes);
                Matcher matcher = END.matcher(new String(bytes, StandardCharsets.UTF_8));
                if (format.initializationData.size() >= 2 && new String(format.initializationData.get(0), StandardCharsets.UTF_8).startsWith("Format: Start, End, ReadOrder")) {
                    String sample = new String(bytes, StandardCharsets.UTF_8);
                    String[] fields = sample.substring("Dialogue: ".length()).split(",", 3);
                    endUs = Math.max(endUs, parseAssTimeUs(fields[1]));
                }
                while (matcher.find()) {
                    double seconds = Long.parseLong(matcher.group(1)) * 3600.0 + Long.parseLong(matcher.group(2)) * 60.0 + Double.parseDouble(matcher.group(3) + "." + matcher.group(4));
                    endUs = Math.max(endUs, (long) (seconds * 1_000_000));
                }
            }
            Presentation p = presentation;
            ass.configure(p.width, p.height, p.scale, p.overrides);
            NativeAss.Frame frame = ass.render((positionUs - getStreamOffsetUs()) / 1000 - textOffsetMs);
            if (frame == null) return;
            List<Cue> cues = List.of();
            if (frame.bitmap != null) {
                float y = frame.y / (float) frame.height;
                if (p.secondary) y = p.position - frame.bitmap.getHeight() / (2f * frame.height);
                cues = List.of(new Cue.Builder().setBitmap(frame.bitmap).setPosition(frame.x / (float) frame.width).setPositionAnchor(Cue.ANCHOR_TYPE_START).setLine(y, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).setSize(frame.bitmap.getWidth() / (float) frame.width).setBitmapHeight(frame.bitmap.getHeight() / (float) frame.height).build());
            }
            dispatch(new CueGroup(cues, Math.max(0, positionUs - getStreamOffsetUs())));
        } catch (RuntimeException error) {
            throw createRendererException(error, format, androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED);
        }
    }
    /** Matroska extractor times are relative to sample.timeUs, unlike standalone ASS. */
    static byte[] absolutizeMatroskaSample(byte[] data, long sampleTimeUs) {
        String sample = new String(data, StandardCharsets.UTF_8);
        int terminator = sample.indexOf('\0');
        if (terminator >= 0) sample = sample.substring(0, terminator);
        if (!sample.startsWith("Dialogue: ")) return data;
        String[] fields = sample.substring("Dialogue: ".length()).split(",", 3);
        if (fields.length != 3) return data;
        long start = sampleTimeUs + parseAssTimeUs(fields[0]);
        long end = sampleTimeUs + parseAssTimeUs(fields[1]);
        return ("Dialogue: " + formatAssTime(start) + "," + formatAssTime(end) + "," + fields[2] + "\n").getBytes(StandardCharsets.UTF_8);
    }
    private static long parseAssTimeUs(String text) {
        String[] parts = text.trim().split("[:.]");
        if (parts.length != 4) throw new IllegalArgumentException("Invalid ASS time");
        return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1_000_000
                + Long.parseLong(parts[3]) * (long) (1_000_000 / Math.pow(10, parts[3].length()));
    }
    private static String formatAssTime(long timeUs) {
        long cs = Math.max(0, timeUs / 10_000);
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360000, (cs / 6000) % 60, (cs / 100) % 60, cs % 100);
    }

    private void clear() { outputGeneration++; dispatch(CueGroup.EMPTY_TIME_ZERO); }
    private void dispatch(CueGroup cues) {
        int generation = outputGeneration;
        handler.post(() -> { if (generation == outputGeneration) output.onCues(cues); });
    }
}

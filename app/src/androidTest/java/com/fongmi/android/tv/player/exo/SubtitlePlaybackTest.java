package com.fongmi.android.tv.player.exo;

import android.net.Uri;
import androidx.media3.common.*;
import androidx.media3.common.text.CueGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.compat.NativeAssTest;
import org.junit.Test;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class SubtitlePlaybackTest {
    @Test public void nativeAssAndSecondarySrtPlayTogether() throws Exception { play(true); }
    @Test public void standardAssAndSecondarySrtPlayTogether() throws Exception { play(false); }

    @Test public void matroskaSubtitleTimeUsesSampleTimestamp() {
        String header = NativeAssTest.SCRIPT.substring(0, NativeAssTest.SCRIPT.indexOf("[Events]"))
                + "[Events]\nFormat: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";
        byte[] packet = AssSubtitleRenderer.absolutizeMatroskaSample(
                "Dialogue: 0:00:00:00,0:00:01:00,0,0,Default,,0,0,0,,Later subtitle\0".getBytes(StandardCharsets.UTF_8), 2_000_000);
        String config = com.fongmi.android.tv.player.subtitle.AndroidFontConfig.prepare().getAbsolutePath();
        try (var ass = new com.fongmi.android.tv.player.compat.NativeAss(config, null, null)) {
            ass.configure(640, 360, 1, "");
            ass.feed(header.getBytes(StandardCharsets.UTF_8)); ass.feed(packet);
            assertNull(ass.render(1000).bitmap);
            assertNotNull(ass.render(2500).bitmap);
            assertNull(ass.render(3500).bitmap);
        }
    }

    private void play(boolean nativeAss) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        File directory = instrumentation.getTargetContext().getCacheDir();
        File wave = new File(directory, "subtitle-test.wav");
        byte[] pcm = new byte[44100 * 2 * 3];
        ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        buffer.putInt(44100).putInt(88200).putShort((short) 2).putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        Files.write(wave.toPath(), buffer.array());
        File ass = new File(directory, "subtitle-test.ass");
        File srt = new File(directory, "subtitle-test.srt");
        Files.writeString(ass.toPath(), NativeAssTest.SCRIPT);
        Files.writeString(srt.toPath(), "1\n00:00:00,000 --> 00:00:02,000\nSecondary subtitle\n");
        MediaItem item = new MediaItem.Builder().setUri(Uri.fromFile(wave)).setSubtitleConfigurations(List.of(
                new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(ass)).setMimeType(MimeTypes.TEXT_SSA).setLanguage("zh").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build(),
                new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(srt)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("en").build())).build();
        CountDownLatch primary = new CountDownLatch(1), secondary = new CountDownLatch(1);
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        AtomicReference<ExoPlayerSession> session = new AtomicReference<>();
        boolean original = PlayerSetting.isLibass();
        try {
            instrumentation.runOnMainSync(() -> {
                PlayerSetting.putLibass(nativeAss);
                ExoPlayerSession next = new ExoPlayerSession(PlayerEngine.HARD, new Player.Listener() {
                    @Override public void onCues(CueGroup cues) {
                        if (cues.cues.stream().anyMatch(cue -> nativeAss ? cue.bitmap != null : cue.text != null)) primary.countDown();
                    }
                    @Override public void onPlayerError(PlaybackException value) { error.set(value); primary.countDown(); secondary.countDown(); }
                }, null);
                session.set(next);
                next.secondaryTextOutput().setListener(cues -> { if (!cues.cues.isEmpty()) secondary.countDown(); });
                next.player().setTrackSelectionParameters(next.player().getTrackSelectionParameters().buildUpon().setPreferredTextLanguages("zh").build());
                next.trackSelector().setSecondary(null, true);
                next.player().setMediaItem(item);
                next.player().setAudioAttributes(AudioAttributes.DEFAULT, false);
                next.player().setVolume(0);
                next.player().prepare();
                next.player().play();
            });
            assertTrue("Primary subtitle output", primary.await(15, TimeUnit.SECONDS));
            assertNull("Playback error", error.get());
            assertTrue("Secondary subtitle output", secondary.await(15, TimeUnit.SECONDS));
            assertNull("Playback error", error.get());
            instrumentation.runOnMainSync(() -> {
                var snapshot = session.get().trackSelector().snapshot();
                assertNotNull(snapshot.primary()); assertNotNull(snapshot.secondary());
                assertNotEquals(snapshot.primary(), snapshot.secondary());
                session.get().trackSelector().setSecondary(null, false);
            });
        } finally {
            instrumentation.runOnMainSync(() -> { if (session.get() != null) session.get().release(); PlayerSetting.putLibass(original); });
            wave.delete(); ass.delete(); srt.delete();
        }
    }
}

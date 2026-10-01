package com.fongmi.android.tv.player.mpv;

import android.net.Uri;
import androidx.media3.common.*;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.SubtitleSetting;
import is.xyz.mpv.MPVLib;
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

public final class MpvSubtitleTest {
    @Test public void secondarySelectionAndModeReachNativePlayer() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        File directory = instrumentation.getTargetContext().getCacheDir();
        File wave = new File(directory, "mpv-subtitle-test.wav");
        ByteBuffer data = ByteBuffer.allocate(44 + 88200 * 10).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(data.capacity() - 8);
        data.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        data.putInt(44100).putInt(88200).putShort((short) 2).putShort((short) 16);
        data.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data.capacity() - 44);
        Files.write(wave.toPath(), data.array());
        File first = new File(directory, "mpv-first.srt"), second = new File(directory, "mpv-second.srt");
        Files.writeString(first.toPath(), "1\n00:00:00,000 --> 00:00:09,000\nPrimary subtitle\n");
        Files.writeString(second.toPath(), "1\n00:00:00,000 --> 00:00:09,000\nSecondary subtitle\n");
        MediaItem item = new MediaItem.Builder().setUri(Uri.fromFile(wave)).setSubtitleConfigurations(List.of(
                new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(first)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("zh").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build(),
                new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(second)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("en").build())).build();
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<MpvPlayerEngine> engine = new AtomicReference<>();
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        int original = SubtitleSetting.getSecondaryMode();
        try {
            instrumentation.runOnMainSync(() -> {
                SubtitleSetting.putSecondaryMode(SubtitleSetting.SECONDARY_MODE_AUTO);
                MpvPlayerEngine next = new MpvPlayerEngine(PlayerEngine.HARD, new Player.Listener() {
                    @Override public void onTracksChanged(Tracks tracks) {
                        if (tracks.getGroups().stream().filter(group -> group.getType() == C.TRACK_TYPE_TEXT).count() >= 2) loaded.countDown();
                    }
                    @Override public void onPlayerError(PlaybackException value) { error.set(value); loaded.countDown(); }
                });
                engine.set(next);
                MpvPlayer player = (MpvPlayer) next.getPlayer();
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredTextLanguages("zh").build());
                player.setMediaItem(item); player.setVolume(0); player.prepare(); player.play();
            });
            assertTrue("MPV must load both external subtitle tracks", loaded.await(15, TimeUnit.SECONDS));
            assertNull("MPV playback error", error.get());
            AtomicReference<Integer> chosen = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                var state = engine.get().getSecondarySubtitleState();
                assertNotNull(state.primarySelection());
                assertEquals(MPVLib.getPropertyString("sid"), state.primarySelection().mediaTrackGroup.getFormat(state.primarySelection().trackIndices.get(0)).id);
                assertFalse(state.secondaryCandidates().isEmpty());
                var candidate = state.secondaryCandidates().get(0);
                chosen.set(Integer.parseInt(candidate.mediaTrackGroup.getFormat(candidate.trackIndices.get(0)).id));
                engine.get().setSecondarySubtitleSelection(candidate);
            });
            awaitOption("secondary-sid", String.valueOf(chosen.get()));
            instrumentation.runOnMainSync(() -> {
                SubtitleSetting.putSecondaryMode(SubtitleSetting.SECONDARY_MODE_OFF);
                engine.get().applySubtitleStyle();
            });
            awaitOption("secondary-sid", "no");
        } finally {
            instrumentation.runOnMainSync(() -> { if (engine.get() != null) engine.get().release(); SubtitleSetting.putSecondaryMode(original); });
            wave.delete(); first.delete(); second.delete();
        }
    }

    private static void awaitOption(String name, String expected) {
        long end = android.os.SystemClock.elapsedRealtime() + 3000;
        String actual;
        do {
            actual = MPVLib.getPropertyString(name);
            if (expected.equals(actual)) return;
            android.os.SystemClock.sleep(20);
        } while (android.os.SystemClock.elapsedRealtime() < end);
        assertEquals(expected, actual);
    }
}

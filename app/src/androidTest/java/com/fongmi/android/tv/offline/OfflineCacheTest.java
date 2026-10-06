package com.fongmi.android.tv.offline;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.ui.PlayerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.FileUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real Media3 downloads and cache-only playback against small generated local fixtures. */
@RunWith(AndroidJUnit4.class)
public class OfflineCacheTest {
    private Context context;
    private OfflineCache cache;
    private FixtureServer server;
    private Activity screen;
    private final List<String> ids = new ArrayList<>();
    private final List<com.fongmi.android.tv.bean.History> histories = new ArrayList<>();

    private void main(Runnable work) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(work);
    }

    @Test
    public void mobileCachePageOffersManualLanSync() {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.startsWith("mobile"));
        main(() -> {
            int id = context.getResources().getIdentifier("offline_sync", "id", context.getPackageName());
            assertTrue("Cache page must offer manual LAN sync", id != 0);
            assertNotNull(screen.findViewById(id));
            assertEquals(android.view.View.VISIBLE, screen.findViewById(id).getVisibility());
        });
    }

    private OfflineVideo transferable(String path) {
        com.fongmi.android.tv.bean.Config config = com.fongmi.android.tv.bean.Config.find("https://cache-sync.test/config.json", "同步测试源", 0);
        com.fongmi.android.tv.bean.History history = independentHistory();
        history.cid(config.getId());
        history.setEpisodeUrl("https://episode.test/" + UUID.randomUUID());
        histories.add(history);
        OfflineVideo video = new OfflineVideo(OfflineVideo.identity(history), history.getVodName(), history.getVodRemarks(), history.getVodFlag(),
                server.url(path), null, Map.of("Cookie", "alpha"), history.toString(), "[]", "{\"parse\":0}");
        ids.add(video.id);
        return video;
    }

    @Test public void exportIncludesDanmakuAddedAfterDownload() throws Exception {
        OfflineVideo video = transferable("a/sample.mp4");
        add(video);
        waitState(video.id, Download.STATE_COMPLETED);
        String added = "[{\"name\":\"后加弹幕\",\"url\":\"https://comments.test/later.xml\"}]";
        context.getSharedPreferences("offline_playback", 0).edit().putString("danmaku:" + video.id, added).commit();
        OfflineCacheTransfer.Snapshot snapshot = new OfflineCacheTransfer(context, cache).export(video.id, () -> false);
        assertEquals("Later imported or searched comments must be included", added, snapshot.manifest.getJSONObject("video").getString("danmaku"));
    }

    @Test public void hlsCacheIncludesBothIndependentSubtitleLanguages() throws Exception {
        OfflineVideo video = transferable("a/hls/subtitles.m3u8");
        add(video);
        Download completed = waitState(video.id, Download.STATE_COMPLETED);
        assertTrue("Chinese rendition must be downloaded", completed.request.streamKeys.stream().anyMatch(key -> key.groupIndex == 2 && key.streamIndex == 0));
        assertTrue("English rendition must be downloaded", completed.request.streamKeys.stream().anyMatch(key -> key.groupIndex == 2 && key.streamIndex == 1));
    }

    private void subtitleCue(Download download, String language, String expected) throws Exception {
        int requests = server.requests.get();
        server.blocked = true;
        CountDownLatch cue = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<androidx.media3.common.PlaybackException> error = new java.util.concurrent.atomic.AtomicReference<>();
        androidx.media3.exoplayer.ExoPlayer[] player = new androidx.media3.exoplayer.ExoPlayer[1];
        try {
            main(() -> {
                player[0] = new androidx.media3.exoplayer.ExoPlayer.Builder(context)
                        .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context).setDataSourceFactory(cache.playback(download))).build();
                player[0].addListener(new Player.Listener() {
                    @Override public void onCues(androidx.media3.common.text.CueGroup group) {
                        if (group.cues.stream().anyMatch(value -> value.text != null && value.text.toString().contains(expected))) cue.countDown();
                    }
                    @Override public void onPlayerError(androidx.media3.common.PlaybackException value) { error.set(value); cue.countDown(); }
                });
                player[0].setTrackSelectionParameters(player[0].getTrackSelectionParameters().buildUpon().setPreferredTextLanguage(language).build());
                player[0].setMediaItem(OfflineSubtitles.attach(context, download.request.id, download.request.toMediaItem()));
                player[0].setVolume(0);
                player[0].prepare(); player[0].play();
            });
            assertTrue("Cached subtitle must render: " + expected, cue.await(15, TimeUnit.SECONDS));
            assertNull("Subtitle playback failed", error.get());
            assertEquals("Subtitle rendering must not access the source", requests, server.requests.get());
        } finally { main(() -> { if (player[0] != null) player[0].release(); }); }
    }

    @Test public void externalSubtitleSurvivesOriginalFileDeletionAndSync() throws Exception {
        OfflineVideo base = transferable("a/sample.mp4");
        File subtitle = File.createTempFile("cache-subtitle-", ".srt", context.getCacheDir());
        try {
            java.nio.file.Files.writeString(subtitle.toPath(), "1\n00:00:00,000 --> 00:00:03,000\nExternal cached subtitle\n");
            com.fongmi.android.tv.bean.Sub sub = com.fongmi.android.tv.bean.Sub.from("本地字幕.srt", subtitle.toURI().toString(), "en", androidx.media3.common.MimeTypes.APPLICATION_SUBRIP);
            OfflineVideo video = new OfflineVideo(OfflineVideo.identity(OfflineHistory.original(base)), base.title, base.episode, base.line, base.url, base.mimeType, base.headers, base.history, base.danmaku,
                    "{\"parse\":0,\"subs\":[" + sub + "]}");
            add(video);
            Download completed = waitState(video.id, Download.STATE_COMPLETED);
            assertEquals(1, OfflineSubtitles.saved(context, video.id).length());
            assertEquals("Online and cached subtitle selection must use the same track identity",
                    com.fongmi.android.tv.player.media.MediaItemFactory.buildSubConfig(sub).id,
                    OfflineSubtitles.attach(context, video.id, completed.request.toMediaItem()).localConfiguration.subtitleConfigurations.get(0).id);
            assertTrue(subtitle.delete());
            subtitleCue(completed, "en", "External cached subtitle");
            OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
            OfflineCacheTransfer.Snapshot snapshot = transfer.export(video.id, () -> false);
            byte[] content = payload(snapshot);
            String uri = snapshot.manifest.getJSONObject("playback").getJSONArray("subtitles").getJSONObject(0).getString("url");
            removeAndWait(video.id);
            assertFalse("Deleting a task must release its external subtitle", cache.storage().getKeys().contains(video.id + ":" + uri));
            try (OfflineCacheTransfer.Import target = transfer.prepare(snapshot.manifest)) {
                assertNotNull(target);
                completed = target.receive(new java.io.ByteArrayInputStream(content), () -> false, bytes -> {});
            }
            assertEquals(1, OfflineSubtitles.saved(context, video.id).length());
            subtitleCue(completed, "en", "External cached subtitle");
            playOffline(completed, true);
        } finally { subtitle.delete(); }
    }

    @Test public void syncedIndependentHlsSubtitlesRenderInBothLanguagesOffline() throws Exception {
        OfflineVideo video = transferable("a/hls/subtitles.m3u8");
        add(video);
        waitState(video.id, Download.STATE_COMPLETED);
        OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
        OfflineCacheTransfer.Snapshot snapshot = transfer.export(video.id, () -> false);
        byte[] content = payload(snapshot);
        removeAndWait(video.id);
        Download imported;
        try (OfflineCacheTransfer.Import target = transfer.prepare(snapshot.manifest)) {
            assertNotNull(target);
            imported = target.receive(new java.io.ByteArrayInputStream(content), () -> false, bytes -> {});
        }
        subtitleCue(imported, "zh", "缓存字幕");
        subtitleCue(imported, "en", "Cached subtitles");
    }

    @Test public void subtitleImportedDuringOfflinePlaybackIsSavedForReopen() throws Exception {
        OfflineVideo video = transferable("a/sample.mp4");
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        File subtitle = File.createTempFile("selected-subtitle-", ".srt", context.getCacheDir());
        try {
            java.nio.file.Files.writeString(subtitle.toPath(), "1\n00:00:00,000 --> 00:00:03,000\nSelected offline subtitle\n");
            Player player = launchSharedPlayer(download);
            main(() -> {
                player.pause();
                com.fongmi.android.tv.server.Server.get().getService().player().setSub(com.fongmi.android.tv.bean.Sub.from("导入.srt", subtitle.toURI().toString()));
            });
            long deadline = System.currentTimeMillis() + 10000;
            while (OfflineSubtitles.saved(context, video.id).length() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(100);
            assertEquals("Manual offline subtitle selection must be persisted", 1, OfflineSubtitles.saved(context, video.id).length());
            boolean[] attached = {false};
            do {
                main(() -> attached[0] = !player.getCurrentMediaItem().localConfiguration.subtitleConfigurations.isEmpty());
                if (!attached[0]) Thread.sleep(100);
            } while (!attached[0] && System.currentTimeMillis() < deadline);
            assertTrue("Existing offline player must attach the saved subtitle", attached[0]);
            main(() -> screen.finish());
            assertTrue(subtitle.delete());
            subtitleCue(download, "zh", "Selected offline subtitle");
        } finally { subtitle.delete(); }
    }

    @Test public void syncedLocalDanmakuAndItsSelectionSurviveOriginalDeletion() throws Exception {
        OfflineVideo video = transferable("a/sample.mp4");
        add(video);
        waitState(video.id, Download.STATE_COMPLETED);
        File original = File.createTempFile("imported-comments-", ".xml", context.getCacheDir());
        String url = original.toURI().toString();
        File saved = OfflineDanmakuCache.saved(url);
        byte[] xml = "<i><d p=\"1,1,25,16777215,0,0,0,0\">本地同步弹幕</d></i>".getBytes(StandardCharsets.UTF_8);
        try {
            java.nio.file.Files.write(original.toPath(), xml);
            String comments = "[{\"name\":\"线上弹幕\",\"url\":\"https://comments.test/unused.xml\"},{\"name\":\"本地导入\",\"url\":\"" + url + "\"}]";
            context.getSharedPreferences("offline_playback", 0).edit().putString("danmaku:" + video.id, comments).putString("danmakuSelected:" + video.id, url).commit();
            OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
            OfflineCacheTransfer.Snapshot snapshot = transfer.export(video.id, () -> false);
            byte[] content = payload(snapshot);
            assertTrue(original.delete()); assertTrue(saved.delete());
            removeAndWait(video.id);
            Download imported;
            try (OfflineCacheTransfer.Import target = transfer.prepare(snapshot.manifest)) {
                assertNotNull(target);
                imported = target.receive(new java.io.ByteArrayInputStream(content), () -> false, bytes -> {});
            }
            assertArrayEquals(xml, java.nio.file.Files.readAllBytes(saved.toPath()));
            server.blocked = true;
            Player player = launchSharedPlayer(imported);
            main(() -> {
                player.pause();
                assertEquals(android.net.Uri.fromFile(saved), com.fongmi.android.tv.server.Server.get().getService().player().getSelectedDanmakuUri());
            });
        } finally { original.delete(); saved.delete(); }
    }

    @Test public void syncIncludesLatestMoviePlaybackSettings() throws Exception {
        OfflineVideo video = transferable("a/sample.mp4");
        add(video);
        waitState(video.id, Download.STATE_COMPLETED);
        com.fongmi.android.tv.bean.History latest = OfflineHistory.original(video);
        latest.setPosition(1200); latest.setDuration(3000); latest.setOpening(300); latest.setEnding(400);
        latest.setSpeed(1.25f); latest.setScale(2); latest.setCreateTime(System.currentTimeMillis()); latest.save();
        new com.fongmi.android.tv.bean.Track(C.TRACK_TYPE_AUDIO, "English", "saved-audio-format").key(latest.getKey()).toggle().save();
        main(() -> com.fongmi.android.tv.player.danmaku.CustomConfigManager.get().addOrUpdateHistory(latest.getKey(), 5));
        OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
        OfflineCacheTransfer.Snapshot snapshot = transfer.export(video.id, () -> false);
        com.fongmi.android.tv.bean.History exported = com.fongmi.android.tv.bean.History.objectFrom(snapshot.manifest.getJSONObject("video").getString("history"));
        assertEquals(1200, exported.getPosition()); assertEquals(300, exported.getOpening()); assertEquals(400, exported.getEnding());
        assertEquals(1.25f, exported.getSpeed(), 0.001f); assertEquals(2, exported.getScale());
        byte[] content = payload(snapshot);
        removeAndWait(video.id);
        com.fongmi.android.tv.db.AppDatabase.get().getHistoryDao().delete(latest.getCid(), latest.getKey());
        com.fongmi.android.tv.bean.Track.delete(latest.getKey());
        main(() -> com.fongmi.android.tv.player.danmaku.CustomConfigManager.get().deleteHistory(latest.getKey()));
        try (OfflineCacheTransfer.Import target = transfer.prepare(snapshot.manifest)) {
            assertNotNull(target);
            target.receive(new java.io.ByteArrayInputStream(content), () -> false, bytes -> {});
        }
        com.fongmi.android.tv.bean.History restored = OfflineHistory.restore(video);
        assertEquals(1200, restored.getPosition()); assertEquals(300, restored.getOpening()); assertEquals(400, restored.getEnding());
        assertEquals(1.25f, restored.getSpeed(), 0.001f); assertEquals(2, restored.getScale());
        assertEquals("saved-audio-format", com.fongmi.android.tv.bean.Track.find(latest.getKey()).get(0).getFormat());
        main(() -> assertEquals(5, com.fongmi.android.tv.player.danmaku.CustomConfigManager.get().getHistoryOffset(latest.getKey())));
    }

    private void removeAndWait(String id) throws Exception {
        main(() -> cache.remove(id));
        long end = System.currentTimeMillis() + 10000;
        while (cache.find(id) != null && System.currentTimeMillis() < end) Thread.sleep(100);
        assertNull(cache.find(id));
    }

    private byte[] payload(OfflineCacheTransfer.Snapshot snapshot) throws Exception {
        try (InputStream input = snapshot.open(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private void syncRoundTrip(String path) throws Exception {
        OfflineVideo original = transferable(path);
        add(original);
        Download source = waitState(original.id, Download.STATE_COMPLETED);
        OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
        OfflineCacheTransfer.Snapshot snapshot = transfer.export(original.id, () -> false);
        byte[] content = payload(snapshot);
        assertNull("Existing completed task must not be imported twice", transfer.prepare(snapshot.manifest));
        removeAndWait(original.id);
        // Simulate another device's configuration database ID; URLs, site, program, line and episode stay exact.
        com.fongmi.android.tv.bean.History foreign = OfflineHistory.original(original).copy().cid(987654);
        snapshot.manifest.getJSONObject("video").put("history", foreign.toString());
        int requests = server.requests.get();
        server.blocked = true;
        Download imported;
        try (OfflineCacheTransfer.Import target = transfer.prepare(snapshot.manifest)) {
            assertNotNull(target);
            assertEquals(original.id, target.video.id);
            assertNull("Simultaneous import must share normal admission", transfer.prepare(snapshot.manifest));
            AtomicInteger message = new AtomicInteger();
            AtomicInteger resolutions = new AtomicInteger();
            main(() -> cache.requestEpisode(OfflineHistory.original(original), callback -> resolutions.incrementAndGet(), message::set));
            assertEquals(R.string.offline_preparing, message.get());
            assertEquals("Source resolution must be blocked during import", 0, resolutions.get());
            main(() -> cache.add(original, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, message::set));
            assertEquals(R.string.offline_exists, message.get());
            imported = target.receive(new java.io.ByteArrayInputStream(content), () -> false, bytes -> {});
        }
        assertEquals(source.request.uri, imported.request.uri);
        assertEquals(source.request.mimeType, imported.request.mimeType);
        assertEquals(source.request.streamKeys, imported.request.streamKeys);
        assertEquals(source.request.customCacheKey, imported.request.customCacheKey);
        assertEquals(source.getBytesDownloaded(), imported.getBytesDownloaded());
        OfflineVideo restored = OfflineVideo.decode(imported.request.data);
        assertEquals(original.headers, restored.headers);
        assertEquals(original.source, restored.source);
        assertEquals(original.danmaku, restored.danmaku);
        assertEquals(OfflineHistory.original(original).getKey(), OfflineHistory.original(restored).getKey());
        assertEquals(OfflineHistory.original(original).getEpisodeUrl(), OfflineHistory.original(restored).getEpisodeUrl());
        assertEquals(original.id, cache.completedFor(OfflineHistory.original(original)).request.id);
        assertNull(transfer.prepare(snapshot.manifest));
        assertEquals("Import must not request the original source", requests, server.requests.get());
        playOffline(imported, true);
    }

    @Test public void syncedMp4UsesNormalCacheAndSourceIdentity() throws Exception { syncRoundTrip("a/sample.mp4"); }
    @Test public void syncedHlsKeepsTrackSelectionAndAllSegments() throws Exception { syncRoundTrip("a/hls/master.m3u8"); }
    @Test public void syncedDashKeepsTrackSelectionAndAllSegments() throws Exception { syncRoundTrip("a/dash/manifest.mpd"); }

    private void orphanSpan(String key, byte[] data) throws Exception {
        var hole = cache.storage().startReadWriteNonBlocking(key, 0, data.length);
        assertNotNull(hole);
        try {
            File file = cache.storage().startFile(key, 0, data.length);
            java.nio.file.Files.write(file.toPath(), data);
            cache.storage().commitFile(file, data.length);
        } finally { cache.storage().releaseHoleSpan(hole); }
    }

    @Test public void manualCleanupRemovesOnlyOrphansAndTemporaryFiles() throws Exception {
        OfflineVideo healthy = video("a/sample.mp4", "alpha"); add(healthy);
        Download saved = waitState(healthy.id, Download.STATE_COMPLETED);
        String key = java.util.UUID.randomUUID() + ":https://orphan.test/media";
        orphanSpan(key, new byte[80]);
        File dir = new File(context.getFilesDir(), "danmaku_saved"); dir.mkdirs();
        File temp = File.createTempFile("sync-", ".tmp", dir);
        File shared = File.createTempFile("shared-", ".xml", dir);
        File unrelated = File.createTempFile("other-", ".tmp", dir);
        File subtitle = File.createTempFile("offline-subtitle-", ".tmp", context.getCacheDir());
        try {
            java.nio.file.Files.write(temp.toPath(), new byte[31]);
            java.nio.file.Files.write(subtitle.toPath(), new byte[19]);
            java.nio.file.Files.write(shared.toPath(), new byte[10]);
            OfflineCacheMaintenance tool = new OfflineCacheMaintenance(context, cache);
            OfflineCacheMaintenance.Report report = tool.scan();
            assertTrue(cache.storage().getCachedSpans(key).size() > 0);
            assertTrue(temp.exists()); assertTrue(subtitle.exists());
            assertTrue(report.bytes >= 130);
            tool.clean(report);
            assertTrue(cache.storage().getCachedSpans(key).isEmpty());
            assertFalse(temp.exists()); assertFalse(subtitle.exists());
            assertTrue("Keep shared saved comments", shared.exists()); assertTrue(unrelated.exists());
            assertNotNull(cache.find(healthy.id)); cache.verify(saved);
            OfflineCacheMaintenance.Report empty = tool.scan();
            assertEquals(0L, empty.bytes);
        } finally {
            cache.storage().removeResource(key); temp.delete(); shared.delete(); unrelated.delete(); subtitle.delete();
        }
    }

    @Test public void cleanupKeepsPausedAndFailedTaskDataAndRefusesDownloading() throws Exception {
        OfflineVideo video = video("a/slow.mp4", "alpha"); add(video);
        waitState(video.id, Download.STATE_DOWNLOADING);
        OfflineCacheMaintenance tool = new OfflineCacheMaintenance(context, cache);
        try { tool.scan(); fail("Do not inspect while a download is writing"); }
        catch (OfflineCacheMaintenance.Busy expected) {}
        main(() -> cache.pause(video.id)); Download paused = waitState(video.id, Download.STATE_STOPPED);
        String key = video.id + ":https://orphan.test/paused";
        orphanSpan(key, new byte[80]);
        var report = tool.scan(); tool.clean(report);
        assertFalse(cache.storage().getCachedSpans(key).isEmpty());
        Download failed = new Download(paused.request, Download.STATE_FAILED, paused.startTimeMs, paused.updateTimeMs,
                paused.contentLength, 0, Download.FAILURE_REASON_UNKNOWN);
        ((androidx.media3.exoplayer.offline.WritableDownloadIndex) cache.manager().getDownloadIndex()).putDownload(failed);
        report = tool.scan(); tool.clean(report);
        assertFalse("Failed tasks still own their retryable data", cache.storage().getCachedSpans(key).isEmpty());
        assertNotNull(cache.find(video.id));
    }

    @Test public void cleanupWaitsForSubtitleSaveInsteadOfRemovingItsTemporaryFile() throws Exception {
        OfflineVideo video = video("a/sample.mp4", "alpha"); add(video);
        Download saved = waitState(video.id, Download.STATE_COMPLETED);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var saving = workers.submit(() -> OfflineSubtitles.save(context, cache, video,
                    com.fongmi.android.tv.bean.Sub.from("Slow.vtt", server.url("a/slow-subtitle.vtt"), "en", androidx.media3.common.MimeTypes.TEXT_VTT), true));
            long deadline = System.currentTimeMillis() + 5000;
            File active = null;
            do {
                File[] files = context.getCacheDir().listFiles();
                if (files != null) for (File file : files) if (file.getName().startsWith("offline-subtitle-") && file.getName().endsWith(".tmp")) active = file;
                if (active != null) break; Thread.sleep(50);
            } while (System.currentTimeMillis() < deadline);
            assertNotNull("The real subtitle saver is writing a temporary file", active);
            var checking = workers.submit(() -> new OfflineCacheMaintenance(context, cache).scan());
            Thread.sleep(200);
            assertFalse("Do not classify an active subtitle as a dead file", checking.isDone());
            assertTrue(active.exists()); saving.get(15, TimeUnit.SECONDS);
            var report = checking.get(10, TimeUnit.SECONDS);
            assertFalse("The subtitle saver finished and removed its own temporary file", active.exists()); cache.verify(saved);
        } finally { workers.shutdownNow(); }
    }

    private Activity waitForSettingsWindow() throws Exception {
        var visible = new java.util.concurrent.atomic.AtomicReference<Activity>();
        long deadline = System.currentTimeMillis() + 10000;
        do {
            main(() -> {
                for (Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (activity instanceof OfflineCacheSettingsActivity && activity.hasWindowFocus()) visible.set(activity);
                }
            });
            if (visible.get() != null) break; Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        assertNotNull("Wait for the current resumed settings instance after any orientation recreation", visible.get());
        return visible.get();
    }

    @Test public void settingsCleanupIsManualAndRequiresConfirmation() throws Exception {
        String key = java.util.UUID.randomUUID() + ":https://orphan.test/settings";
        orphanSpan(key, new byte[80]);
        InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,
                OfflineCacheSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity page = waitForSettingsWindow();
        try {
            main(() -> assertEquals(context.getString(R.string.offline_cleanup_help),
                    ((android.widget.TextView) page.findViewById(R.id.offline_cleanup_status)).getText().toString()));
            assertFalse("Opening settings must not clean automatically", cache.storage().getCachedSpans(key).isEmpty());
            var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            var info = automation.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            main(() -> page.findViewById(R.id.offline_cleanup).performClick());
            boolean confirmed = false;
            long deadline = System.currentTimeMillis() + 10000;
            do {
                for (var window : automation.getWindows()) {
                    var root = window.getRoot(); if (root == null) continue;
                    for (var node : root.findAccessibilityNodeInfosByText(context.getString(R.string.offline_cleanup_action))) {
                        if (!node.isClickable() || !context.getString(R.string.offline_cleanup_action).contentEquals(node.getText())) continue;
                        assertFalse("Checking must leave files until confirmation", cache.storage().getCachedSpans(key).isEmpty());
                        confirmed = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                        if (confirmed) break;
                    }
                    if (confirmed) break;
                }
                if (!confirmed) Thread.sleep(100);
            } while (!confirmed && System.currentTimeMillis() < deadline);
            assertTrue("The confirmation offers cleanup", confirmed);
            deadline = System.currentTimeMillis() + 5000;
            while (!cache.storage().getCachedSpans(key).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100);
            assertTrue(cache.storage().getCachedSpans(key).isEmpty());
        } finally { main(page::finish); cache.storage().removeResource(key); }
    }

    @Test public void manualCleanupRefusesAnActiveImportAndRechecksOwnership() throws Exception {
        OfflineVideo original = transferable("a/sample.mp4"); add(original);
        Download saved = waitState(original.id, Download.STATE_COMPLETED);
        var transfer = new OfflineCacheTransfer(context, cache);
        var snapshot = transfer.export(original.id, () -> false);
        removeAndWait(original.id);
        String key = original.id + ":" + original.url;
        orphanSpan(key, new byte[80]);
        OfflineCacheMaintenance tool = new OfflineCacheMaintenance(context, cache); OfflineCacheMaintenance.Report report = tool.scan();
        try (var active = transfer.prepare(snapshot.manifest)) {
            assertNotNull(active);
            try { tool.clean(report); fail("Do not clean during an import"); }
            catch (OfflineCacheMaintenance.Busy expected) {}
        }
        // A task registered after the scan must be protected when confirmation arrives.
        orphanSpan(key, new byte[80]);
        ((androidx.media3.exoplayer.offline.WritableDownloadIndex) cache.manager().getDownloadIndex()).putDownload(saved);
        tool.clean(report);
        assertFalse("Recheck ownership rather than deleting a stale scan", cache.storage().getCachedSpans(key).isEmpty());
    }

    @Test public void brokenSyncRollsBackAndAllowsRetry() throws Exception {
        OfflineVideo video = transferable("a/hls/master.m3u8");
        add(video);
        waitState(video.id, Download.STATE_COMPLETED);
        OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
        OfflineCacheTransfer.Snapshot snapshot = transfer.export(video.id, () -> false);
        byte[] content = payload(snapshot);
        removeAndWait(video.id);
        for (int failure = 0; failure < 3; failure++) {
            OfflineCacheTransfer.Import target = transfer.prepare(snapshot.manifest);
            assertNotNull(target);
            byte[] broken = content.clone();
            if (failure == 0) broken[0] ^= 1;
            if (failure == 1) broken = java.util.Arrays.copyOf(content, content.length - 1);
            boolean cancelled = failure == 2;
            try {
                target.receive(new java.io.ByteArrayInputStream(broken), () -> cancelled, bytes -> {});
                fail("Broken or cancelled import must not become completed");
            } catch (java.io.IOException expected) {}
            assertNull(cache.find(video.id));
            for (String key : cache.storage().getKeys()) assertFalse("No orphan spans after failure", key.startsWith(video.id + ":"));
        }
        try (OfflineCacheTransfer.Import retry = transfer.prepare(snapshot.manifest)) {
            assertNotNull(retry);
            retry.receive(new java.io.ByteArrayInputStream(content), () -> false, bytes -> {});
        }
        assertNotNull(cache.completedFor(OfflineHistory.original(video)));
    }

    @Test public void syncRejectsIncompleteDownloadsAndUnsafeManifests() throws Exception {
        OfflineVideo video = transferable("a/slow.mp4");
        add(video);
        waitState(video.id, Download.STATE_DOWNLOADING);
        OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
        try { transfer.export(video.id, () -> false); fail("Incomplete task cannot be synced"); }
        catch (java.io.IOException expected) {}
        main(() -> cache.pause(video.id));
        waitState(video.id, Download.STATE_STOPPED);
        OfflineVideo complete = transferable("a/sample.mp4");
        add(complete);
        waitState(complete.id, Download.STATE_COMPLETED);
        OfflineCacheTransfer.Snapshot snapshot = transfer.export(complete.id, () -> false);
        for (int kind = 0; kind < 3; kind++) {
            org.json.JSONObject invalid = new org.json.JSONObject(snapshot.manifest.toString());
            if (kind == 0) invalid.put("version", 999);
            if (kind == 1) invalid.getJSONArray("spans").getJSONObject(0).put("position", -1);
            if (kind == 2) invalid.getJSONArray("spans").getJSONObject(0).put("comment", "../../outside.xml");
            try { transfer.prepare(invalid); fail("Malformed manifest must be rejected"); }
            catch (java.io.IOException expected) {}
        }
        assertEquals(Download.STATE_COMPLETED, cache.find(complete.id).state);
        for (String external : List.of("http://8.8.8.8:9978", "http://example.com:9978", "https://127.0.0.1:9978", "http://127.0.0.1:9978/path")) {
            try { OfflineLan.endpoint(external); fail("Non-LAN endpoint accepted: " + external); }
            catch (IllegalArgumentException expected) {}
        }
    }

    @Test public void mobileLanProtocolImportsAndSkipsDuplicateWithoutFetchingAgain() throws Exception {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.startsWith("mobile"));
        OfflineVideo video = transferable("a/hls/master.m3u8");
        add(video);
        waitState(video.id, Download.STATE_COMPLETED);
        OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
        OfflineCacheTransfer.Snapshot snapshot = transfer.export(video.id, () -> false);
        byte[] content = payload(snapshot);
        removeAndWait(video.id);
        AtomicInteger pulls = new AtomicInteger();
        fi.iki.elonen.NanoHTTPD sender = new fi.iki.elonen.NanoHTTPD(0) {
            @Override public Response serve(IHTTPSession session) {
                pulls.incrementAndGet();
                return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", new java.io.ByteArrayInputStream(content), content.length);
            }
        };
        com.fongmi.android.tv.server.Nano receiver = new com.fongmi.android.tv.server.Nano(0);
        sender.start(); receiver.start();
        try {
            String endpoint = "http://127.0.0.1:" + receiver.getListeningPort() + "/offline-sync/";
            org.json.JSONObject invitation = new org.json.JSONObject().put("port", sender.getListeningPort())
                    .put("token", UUID.randomUUID()).put("manifest", snapshot.manifest);
            okhttp3.OkHttpClient client = com.github.catvod.net.OkHttp.client();
            okhttp3.Request request = new okhttp3.Request.Builder().url(endpoint + "receive")
                    .post(new okhttp3.FormBody.Builder().add("invitation", invitation.toString()).build()).build();
            String job;
            try (okhttp3.Response response = client.newCall(request).execute()) {
                assertTrue(response.isSuccessful());
                job = new org.json.JSONObject(response.body().string()).getString("job");
            }
            long end = System.currentTimeMillis() + 10000;
            String state = "";
            do {
                try (okhttp3.Response response = client.newCall(new okhttp3.Request.Builder().url(endpoint + "status?job=" + job).build()).execute()) {
                    state = new org.json.JSONObject(response.body().string()).getString("state");
                }
                if (!state.equals("receiving")) break;
                Thread.sleep(100);
            } while (System.currentTimeMillis() < end);
            assertEquals("completed", state);
            assertEquals(1, pulls.get());
            try (okhttp3.Response response = client.newCall(request).execute()) {
                assertEquals("exists", new org.json.JSONObject(response.body().string()).getString("state"));
            }
            assertEquals("Duplicate invitation must not fetch any video", 1, pulls.get());
            assertNotNull(cache.completedFor(OfflineHistory.original(video)));
            server.blocked = true;
            playOffline(cache.find(video.id), true);
        } finally { sender.stop(); receiver.stop(); }
    }

    @Test public void syncedSavedDanmakuUsesItsNormalFileAndUrl() throws Exception {
        OfflineVideo base = transferable("a/sample.mp4");
        String url = "https://comments.test/" + UUID.randomUUID() + ".xml";
        String comments = "[{\"name\":\"测试弹幕\",\"url\":\"" + url + "\"}]";
        OfflineVideo video = new OfflineVideo(OfflineVideo.identity(OfflineHistory.original(base)), base.title, base.episode, base.line,
                base.url, base.mimeType, base.headers, base.history, comments, base.source);
        java.io.File directory = new java.io.File(context.getFilesDir(), "danmaku_saved");
        directory.mkdirs();
        java.io.File file = new java.io.File(directory, UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)) + ".xml");
        byte[] xml = "<i><d p=\"1,1,25,16777215,0,0,0,0\">同步弹幕</d></i>".getBytes(StandardCharsets.UTF_8);
        try {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) { output.write(xml); }
            add(video);
            waitState(video.id, Download.STATE_COMPLETED);
            OfflineCacheTransfer transfer = new OfflineCacheTransfer(context, cache);
            OfflineCacheTransfer.Snapshot snapshot = transfer.export(video.id, () -> false);
            byte[] content = payload(snapshot);
            removeAndWait(video.id);
            assertTrue(file.delete());
            try (OfflineCacheTransfer.Import target = transfer.prepare(snapshot.manifest)) {
                target.receive(new java.io.ByteArrayInputStream(content), () -> false, bytes -> {});
            }
            assertArrayEquals(xml, java.nio.file.Files.readAllBytes(file.toPath()));
            assertEquals(comments, OfflineVideo.decode(cache.find(video.id).request.data).danmaku);
        } finally { file.delete(); }
    }

    @Test public void mobileSenderNegotiatesAndSkipsExistingTask() throws Exception {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.startsWith("mobile"));
        OfflineVideo video = transferable("a/sample.mp4");
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        com.fongmi.android.tv.server.Nano receiver = new com.fongmi.android.tv.server.Nano(0);
        receiver.start();
        try {
            com.fongmi.android.tv.bean.Device phone = com.fongmi.android.tv.bean.Device.get();
            phone.setIp("http://127.0.0.1:" + receiver.getListeningPort());
            CountDownLatch finished = new CountDownLatch(1);
            AtomicInteger sent = new AtomicInteger(-1), skipped = new AtomicInteger(-1);
            java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
            main(() -> OfflineCacheSync.get(context).send(phone, List.of(download), new OfflineCacheSync.Listener() {
                @Override public void progress(String title, int index, int count, long received, long total) {}
                @Override public void complete(int completed, int existing, String error) {
                    sent.set(completed); skipped.set(existing); failure.set(error); finished.countDown();
                }
            }));
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(0, sent.get());
            assertEquals(1, skipped.get());
            assertEquals(Download.STATE_COMPLETED, cache.find(video.id).state);
        } finally { receiver.stop(); }
    }

    @Test public void tvDoesNotRegisterCacheSyncEndpointOrButton() {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.startsWith("leanback"));
        assertNull(OfflineSyncEntry.process());
        main(() -> {
            int id = context.getResources().getIdentifier("offline_sync", "id", context.getPackageName());
            assertNull(screen.findViewById(id));
        });
    }

    @Test public void manualSyncSelectionListsOnlyCompletedAndRequiresASelection() throws Exception {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.startsWith("mobile"));
        OfflineVideo ready = video("a/sample.mp4", "alpha");
        OfflineVideo second = video("a/sample.mp4", "alpha");
        OfflineVideo other = new OfflineVideo(UUID.randomUUID().toString(), "另一部影片", "第1集", "测试线路",
                server.url("a/sample.mp4"), null, Map.of("Cookie", "alpha"));
        ids.add(other.id);
        for (OfflineVideo value : List.of(other, ready, second)) { add(value); waitState(value.id, Download.STATE_COMPLETED); }
        OfflineVideo paused = transferable("a/slow.mp4");
        add(paused);
        waitState(paused.id, Download.STATE_DOWNLOADING);
        main(() -> cache.pause(paused.id));
        waitState(paused.id, Download.STATE_STOPPED);
        java.lang.reflect.Field ownerField = OfflineCacheActivity.class.getDeclaredField("sync");
        ownerField.setAccessible(true);
        Object owner = ownerField.get(screen);
        java.lang.reflect.Field dialogField = owner.getClass().getDeclaredField("dialog");
        dialogField.setAccessible(true);
        main(() -> screen.findViewById(R.id.offline_sync).performClick());
        androidx.appcompat.app.AlertDialog[] choice = new androidx.appcompat.app.AlertDialog[1];
        long end = System.currentTimeMillis() + 5000;
        do {
            main(() -> { try { choice[0] = (androidx.appcompat.app.AlertDialog) dialogField.get(owner); } catch (Exception error) { throw new AssertionError(error); } });
            if (choice[0] != null) break;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < end);
        assertNotNull(choice[0]);
        Thread.sleep(300);
        main(() -> {
            androidx.recyclerview.widget.RecyclerView list = choice[0].findViewById(R.id.offline_sync_list);
            android.view.View confirm = choice[0].findViewById(R.id.offline_sync_confirm);
            assertEquals("Two folders, three completed episodes; paused task excluded", 5, list.getAdapter().getItemCount());
            assertFalse(confirm.isEnabled());
            list.findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.offline_checked).performClick();
            assertEquals(context.getString(R.string.offline_selected_count, 2), ((android.widget.TextView) choice[0].findViewById(R.id.offline_sync_count)).getText().toString());
            assertTrue(confirm.isEnabled());
        });
        Thread.sleep(200);
        main(() -> {
            androidx.recyclerview.widget.RecyclerView list = choice[0].findViewById(R.id.offline_sync_list);
            list.findViewHolderForAdapterPosition(0).itemView.performClick();
            assertEquals("Folder collapses while keeping its selection", 3, list.getAdapter().getItemCount());
            assertEquals(context.getString(R.string.offline_selected_count, 2), ((android.widget.TextView) choice[0].findViewById(R.id.offline_sync_count)).getText().toString());
            choice[0].findViewById(R.id.offline_sync_all_button).performClick();
            assertEquals(context.getString(R.string.offline_selected_count, 3), ((android.widget.TextView) choice[0].findViewById(R.id.offline_sync_count)).getText().toString());
            choice[0].findViewById(R.id.offline_sync_all_button).performClick();
            assertFalse(choice[0].findViewById(R.id.offline_sync_confirm).isEnabled());
            choice[0].findViewById(R.id.offline_sync_cancel).performClick();
        });
        assertFalse(choice[0].isShowing());
        assertEquals(Download.STATE_COMPLETED, cache.find(ready.id).state);
        assertEquals(Download.STATE_STOPPED, cache.find(paused.id).state);

        // Capture the actual handoff, including a selection retained inside a collapsed folder.
        java.util.concurrent.atomic.AtomicReference<List<Download>> handedOff = new java.util.concurrent.atomic.AtomicReference<>();
        java.lang.reflect.Method selection = Class.forName("com.fongmi.android.tv.offline.OfflineSyncSelection")
                .getDeclaredMethod("show", android.app.Activity.class, List.class, java.util.function.Consumer.class);
        selection.setAccessible(true);
        main(() -> {
            try { choice[0] = (androidx.appcompat.app.AlertDialog) selection.invoke(null, screen,
                    List.of(cache.find(ready.id), cache.find(second.id), cache.find(other.id)),
                    (java.util.function.Consumer<List<Download>>) handedOff::set); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        Thread.sleep(200);
        main(() -> {
            androidx.recyclerview.widget.RecyclerView list = choice[0].findViewById(R.id.offline_sync_list);
            list.findViewHolderForAdapterPosition(1).itemView.performClick();
        });
        Thread.sleep(200);
        main(() -> {
            androidx.recyclerview.widget.RecyclerView list = choice[0].findViewById(R.id.offline_sync_list);
            list.findViewHolderForAdapterPosition(0).itemView.performClick();
            choice[0].findViewById(R.id.offline_sync_confirm).performClick();
        });
        assertNotNull(handedOff.get());
        assertEquals(1, handedOff.get().size());
        assertEquals(ready.id, handedOff.get().get(0).request.id);

    }

    @Test public void playbackStatusFollowsControlsInBothLockStatesAndReleasesBatteryReceiver() throws Exception {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.startsWith("mobile"));
        main(() -> {
            android.view.ViewGroup decor = (android.view.ViewGroup) screen.getWindow().getDecorView();
            int layout = context.getResources().getIdentifier("view_control_vod", "layout", context.getPackageName());
            android.view.View controls = android.view.LayoutInflater.from(screen).inflate(layout, decor, false);
            int statusId = context.getResources().getIdentifier("playback_status", "id", context.getPackageName());
            int batteryId = context.getResources().getIdentifier("playback_battery", "id", context.getPackageName());
            android.view.View status = controls.findViewById(statusId);
            try {
                java.lang.reflect.Field listening = status.getClass().getDeclaredField("listening");
                listening.setAccessible(true);
                decor.addView(controls);
                assertTrue(status.isShown());
                assertTrue(listening.getBoolean(status));
                assertTrue(((android.widget.TextView) status.findViewById(batteryId)).getText().toString().matches("[0-9]+%"));
                for (boolean locked : new boolean[] {true, false}) {
                    controls.findViewById(R.id.top).setVisibility(locked ? android.view.View.GONE : android.view.View.VISIBLE);
                    assertTrue(status.isShown());
                    controls.setVisibility(android.view.View.GONE);
                    assertFalse(status.isShown());
                    assertFalse(listening.getBoolean(status));
                    controls.setVisibility(android.view.View.VISIBLE);
                    assertTrue(status.isShown());
                    assertTrue(listening.getBoolean(status));
                }
                decor.removeView(controls);
                assertFalse(listening.getBoolean(status));
            } catch (Exception error) { throw new AssertionError(error); }
            finally { decor.removeView(controls); }
        });
    }

    @Test public void cachePromptsCancelConfirmAndShowInspectionResults() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        main(() -> {
            for (int action : new int[] {R.string.offline_delete, R.string.offline_recache}) {
                androidx.appcompat.app.AlertDialog dialog = OfflineCachePrompt.show(screen, action,
                        context.getString(action == R.string.offline_delete ? R.string.offline_delete_question
                                : R.string.offline_recache_question, "测试影片 · 第01集"), action, actions::incrementAndGet);
                assertTrue(dialog.isShowing());
                dialog.findViewById(android.R.id.button2).performClick();
                assertFalse(dialog.isShowing());
                assertEquals(0, actions.get());
            }
            androidx.appcompat.app.AlertDialog dialog = OfflineCachePrompt.show(screen, R.string.offline_delete,
                    context.getString(R.string.offline_delete_selected_question, 2), R.string.offline_delete, actions::incrementAndGet);
            dialog.findViewById(android.R.id.button1).performClick();
            assertFalse(dialog.isShowing());
            assertEquals(1, actions.get());
            for (int message : new int[] {R.string.offline_verified, R.string.offline_integrity_error}) {
                dialog = OfflineCachePrompt.show(screen, R.string.offline_verify, context.getString(message), android.R.string.ok, null);
                assertFalse(dialog.findViewById(android.R.id.button2).isShown());
                dialog.findViewById(android.R.id.button1).performClick();
                assertFalse(dialog.isShowing());
                assertEquals(1, actions.get());
            }
        });
    }

    @Test public void syncStatusShowsProgressAndAllResultsWithoutChangingTransfers() throws Exception {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.startsWith("mobile"));
        Class<?> type = Class.forName("com.fongmi.android.tv.offline.OfflineSyncStatus");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(android.app.Activity.class,
                com.fongmi.android.tv.bean.Device.class, int.class, Runnable.class);
        java.lang.reflect.Field dialogField = type.getDeclaredField("dialog");
        java.lang.reflect.Method progress = type.getDeclaredMethod("progress", String.class, int.class, int.class, long.class, long.class);
        java.lang.reflect.Method result = type.getDeclaredMethod("result", android.app.Activity.class,
                com.fongmi.android.tv.bean.Device.class, int.class, int.class, String.class);
        constructor.setAccessible(true); dialogField.setAccessible(true); progress.setAccessible(true); result.setAccessible(true);
        com.fongmi.android.tv.bean.Device target = new com.fongmi.android.tv.bean.Device();
        target.setName("测试接收设备");
        AtomicInteger cancelled = new AtomicInteger();
        main(() -> {
            try {
                Object status = constructor.newInstance(screen, target, 3, (Runnable) cancelled::incrementAndGet);
                androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog) dialogField.get(status);
                android.widget.ProgressBar bar = dialog.findViewById(R.id.offline_sync_status_progress);
                assertTrue(bar.isIndeterminate());
                progress.invoke(status, "第2集", 2, 3, 50L, 100L);
                assertFalse(bar.isIndeterminate());
                assertEquals(50, bar.getProgress());
                assertEquals("第2集", ((android.widget.TextView) dialog.findViewById(R.id.offline_sync_status_title)).getText().toString());
                assertEquals(context.getString(R.string.offline_sync_episode_progress, 2, 3),
                        ((android.widget.TextView) dialog.findViewById(R.id.offline_sync_status_detail)).getText().toString());
                progress.invoke(status, "第3集", 3, 3, 0L, 0L);
                assertTrue(bar.isIndeterminate());
                dialog.findViewById(R.id.offline_sync_status_action).performClick();
                assertEquals(1, cancelled.get());
                assertFalse(dialog.isShowing());
                for (String error : new String[] {null, "cancelled", "network"}) {
                    dialog = (androidx.appcompat.app.AlertDialog) result.invoke(null, screen, target, 1, 2, error);
                    assertEquals(context.getString(R.string.offline_sync_sent_count, 1),
                            ((android.widget.TextView) dialog.findViewById(R.id.offline_sync_status_title)).getText().toString());
                    assertEquals(context.getString(R.string.offline_sync_skipped_count, 2),
                            ((android.widget.TextView) dialog.findViewById(R.id.offline_sync_status_detail)).getText().toString());
                    assertEquals(android.view.View.GONE, dialog.findViewById(R.id.offline_sync_status_progress).getVisibility());
                    assertEquals(error == null ? android.view.View.GONE : android.view.View.VISIBLE,
                            dialog.findViewById(R.id.offline_sync_status_message).getVisibility());
                    int heading = error == null ? R.string.offline_sync_finished : error.equals("cancelled")
                            ? R.string.offline_sync_cancelled_heading : R.string.offline_sync_failed_heading;
                    assertEquals(context.getString(heading), ((android.widget.TextView) dialog.findViewById(R.id.offline_sync_status_heading)).getText().toString());
                    dialog.findViewById(R.id.offline_sync_status_action).performClick();
                    assertFalse(dialog.isShowing());
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test public void episodeSelectionStatesMatchSourceLineAndNameDespiteUrlChanges() throws Exception {
        OfflineVideo ready = transferable("a/sample.mp4");
        add(ready);
        waitState(ready.id, Download.STATE_COMPLETED);
        com.fongmi.android.tv.bean.History show = OfflineHistory.original(ready).copy();
        com.fongmi.android.tv.bean.History another = show.copy();
        another.setVodRemarks("另一集");
        another.setEpisodeUrl("https://episode.test/" + UUID.randomUUID());
        OfflineVideo pending = new OfflineVideo(OfflineVideo.identity(another), ready.title, another.getVodRemarks(), ready.line,
                server.url("a/slow.mp4"), null, Map.of("Cookie", "alpha"), another.toString(), "[]", "{\"parse\":0}");
        ids.add(pending.id);
        add(pending);
        waitState(pending.id, Download.STATE_DOWNLOADING);
        main(() -> cache.pause(pending.id));
        waitState(pending.id, Download.STATE_STOPPED);
        show.setEpisodeUrl("https://episode.test/rotated-link");
        Map<String, Integer> states = OfflineIntegration.episodeStates(context, show);
        assertEquals(Integer.valueOf(Download.STATE_COMPLETED), states.get(ready.episode));
        assertEquals(Integer.valueOf(Download.STATE_STOPPED), states.get("另一集"));
        assertEquals(2, states.size());
        com.fongmi.android.tv.bean.History different = show.copy();
        different.setVodFlag("不同线路");
        assertTrue(OfflineIntegration.episodeStates(context, different).isEmpty());
        different = show.copy();
        different.cid(show.getCid() + 10000);
        assertTrue(OfflineIntegration.episodeStates(context, different).isEmpty());
    }

    @Before
    public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        wakeTestScreen();
        context.getSharedPreferences("offline_playback", 0).edit().clear().commit();
        context.getSharedPreferences("offline_ui", 0).edit().clear().commit();
        server = new FixtureServer(InstrumentationRegistry.getInstrumentation().getContext());
        screen = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, OfflineCacheActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            cache = OfflineIntegration.get(context);
            // Fixtures use loopback and do not depend on Android validating external Internet.
            cache.manager().setRequirements(new androidx.media3.exoplayer.scheduler.Requirements(0));
        });
        if (BuildConfig.FLAVOR.startsWith("leanback")) {
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, screen.getResources().getConfiguration().orientation);
        }
    }

    @After
    public void teardown() throws Exception {
        if (screen != null) main(() -> screen.finish());
        for (String id : ids) main(() -> cache.remove(id));
        for (String id : ids) {
            long end = System.currentTimeMillis() + 10000;
            while (cache.find(id) != null && System.currentTimeMillis() < end) Thread.sleep(100);
        }
        CountDownLatch saved = new CountDownLatch(1);
        com.fongmi.android.tv.utils.Task.executeSerial(saved::countDown);
        assertTrue(saved.await(5, TimeUnit.SECONDS));
        for (com.fongmi.android.tv.bean.History history : histories) {
            com.fongmi.android.tv.db.AppDatabase.get().getHistoryDao().delete(history.getCid(), history.getKey());
            com.fongmi.android.tv.bean.Track.delete(history.getKey());
            main(() -> com.fongmi.android.tv.player.danmaku.CustomConfigManager.get().deleteHistory(history.getKey()));
        }
        if (server != null) server.close();
    }

    private OfflineVideo video(String path, String cookie) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", cookie);
        OfflineVideo value = new OfflineVideo(UUID.randomUUID().toString(), "离线测试", path, "测试线路",
                server.url(path), null, headers);
        ids.add(value.id);
        return value;
    }

    private void add(OfflineVideo video) throws Exception {
        CountDownLatch prepared = new CountDownLatch(1);
        AtomicInteger message = new AtomicInteger();
        main(() -> cache.add(video, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(), result -> {
            if (result != R.string.offline_preparing) { message.set(result); prepared.countDown(); }
        }));
        assertTrue("Media preparation timed out", prepared.await(50, TimeUnit.SECONDS));
        assertEquals(context.getString(message.get()), R.string.offline_added, message.get());
    }

    private Download waitState(String id, int state) throws Exception {
        long end = System.currentTimeMillis() + 30000;
        Download latest;
        do {
            latest = cache.find(id);
            if (latest != null && latest.state == state) return latest;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < end);
        fail("Expected state " + state + ", got " + (latest == null ? "missing" : latest.state));
        return null;
    }

    private void playOffline(Download download, boolean expectAudio) throws Exception {
        int requests = server.requests.get();
        server.blocked = true;
        main(() -> screen.finish());
        screen = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, com.fongmi.android.tv.ui.activity.VideoActivity.class).putExtra("offline_id", download.request.id).putExtra("id", download.request.id).putExtra("key", "offline")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Player[] player = new Player[1];
        int[] state = {Player.STATE_IDLE};
        long end = System.currentTimeMillis() + 15000;
        do {
            main(() -> {
                player[0] = ((PlayerView) screen.findViewById(R.id.player)).getPlayer();
                if (player[0] != null) state[0] = player[0].getPlaybackState();
            });
            if (state[0] == Player.STATE_READY || state[0] == Player.STATE_ENDED) break;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < end);
        assertNotNull("Offline player was not created", player[0]);
        assertEquals("Offline playback must prepare from cached data", Player.STATE_READY, state[0]);
        main(() -> {
            assertTrue(player[0].getDuration() > 2500);
            assertTrue(player[0].getCurrentTracks().containsType(C.TRACK_TYPE_VIDEO));
            if (expectAudio) assertTrue("Separate audio was not cached", player[0].getCurrentTracks().containsType(C.TRACK_TYPE_AUDIO));
            player[0].seekTo(player[0].getDuration() - 500);
            player[0].play();
        });
        end = System.currentTimeMillis() + 10000;
        do {
            main(() -> state[0] = player[0].getPlaybackState());
            if (state[0] == Player.STATE_ENDED) break;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < end);
        assertEquals("Seeking to the end must play cached final segments", Player.STATE_ENDED, state[0]);
        assertEquals("Offline player must never request the network", requests, server.requests.get());
    }

    @Test
    public void multiSelectDeletesOnlyConfirmedTasksAndKeepsCollapsedSelections() throws Exception {
        OfflineVideo complete = video("a/sample.mp4", "alpha"); add(complete); waitState(complete.id, Download.STATE_COMPLETED);
        OfflineVideo paused = video("a/slow.mp4", "alpha"); add(paused); waitState(paused.id, Download.STATE_DOWNLOADING);
        main(() -> cache.pause(paused.id)); waitState(paused.id, Download.STATE_STOPPED);
        OfflineVideo kept = new OfflineVideo(UUID.randomUUID().toString(), "保留剧集", "第1集", "测试线路",
                server.url("b/sample.mp4"), null, Map.of("Cookie", "beta"));
        ids.add(kept.id); add(kept); waitState(kept.id, Download.STATE_COMPLETED);
        boolean[] ready = {false}; long end = System.currentTimeMillis() + 10000;
        do {
            main(() -> ready[0] = ((androidx.recyclerview.widget.RecyclerView) screen.findViewById(R.id.offline_list)).getChildCount() == 2);
            if (!ready[0]) Thread.sleep(100);
        } while (!ready[0] && System.currentTimeMillis() < end);
        assertTrue(ready[0]);
        main(() -> {
            android.view.View back = screen.findViewById(R.id.offline_back);
            assertEquals(back, ((android.view.ViewGroup) back.getParent()).getChildAt(0));
            screen.findViewById(R.id.offline_select).performClick();
            assertFalse(screen.findViewById(R.id.offline_delete_selected).isEnabled());
            screen.findViewById(R.id.offline_select_all).performClick();
            assertEquals(context.getString(R.string.offline_selected_count, 3),
                    ((android.widget.TextView) screen.findViewById(R.id.offline_selection_count)).getText().toString());
            screen.findViewById(R.id.offline_select_all).performClick();
            androidx.recyclerview.widget.RecyclerView list = screen.findViewById(R.id.offline_list);
            for (int i = 0; i < list.getChildCount(); i++) {
                android.view.View row = list.getChildAt(i);
                if (((android.widget.TextView) row.findViewById(R.id.offline_group_name)).getText().toString().equals("离线测试"))
                    row.findViewById(R.id.offline_checked).performClick();
            }
        });
        Thread.sleep(1200);
        main(() -> {
            assertEquals(context.getString(R.string.offline_selected_count, 2),
                    ((android.widget.TextView) screen.findViewById(R.id.offline_selection_count)).getText().toString());
            screen.findViewById(R.id.offline_delete_selected).performClick();
        });
        clickCacheSelectionDialog(false);
        assertNotNull(cache.find(complete.id)); assertNotNull(cache.find(paused.id));
        main(() -> screen.findViewById(R.id.offline_delete_selected).performClick());
        clickCacheSelectionDialog(true);
        end = System.currentTimeMillis() + 10000;
        while ((cache.find(complete.id) != null || cache.find(paused.id) != null) && System.currentTimeMillis() < end) Thread.sleep(100);
        assertNull(cache.find(complete.id)); assertNull(cache.find(paused.id));
        assertEquals(Download.STATE_COMPLETED, cache.find(kept.id).state);
        main(() -> assertEquals(android.view.View.GONE, screen.findViewById(R.id.offline_selection_bar).getVisibility()));
    }

    private void clickCacheSelectionDialog(boolean confirm) throws Exception {
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        var info = automation.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        long end = System.currentTimeMillis() + 5000;
        do {
            for (var window : automation.getWindows()) {
                var root = window.getRoot(); if (root == null) continue;
                for (var button : root.findAccessibilityNodeInfosByViewId(confirm ? "android:id/button1" : "android:id/button2"))
                    if (button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < end);
        fail("Cache deletion confirmation was not available");
    }

    @Test
    public void sidePanelShowsCompletedAndPausedTasksAndDeletesFromTheList() throws Exception {
        OfflineVideo completed = video("a/sample.mp4", "alpha");
        add(completed);
        waitState(completed.id, Download.STATE_COMPLETED);
        OfflineVideo paused = video("a/slow.mp4", "alpha");
        add(paused);
        waitState(paused.id, Download.STATE_DOWNLOADING);
        main(() -> cache.pause(paused.id));
        waitState(paused.id, Download.STATE_STOPPED);

        main(() -> screen.finish());
        screen = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, com.fongmi.android.tv.ui.activity.VideoActivity.class).putExtra("offline_id", completed.id).putExtra("id", completed.id).putExtra("key", "offline")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Player[] playing = new Player[1];
        long playerDeadline = System.currentTimeMillis() + 10000;
        do {
            main(() -> playing[0] = ((PlayerView) screen.findViewById(R.id.player)).getPlayer());
            if (playing[0] == null) Thread.sleep(100);
        } while (playing[0] == null && System.currentTimeMillis() < playerDeadline);
        assertNotNull(playing[0]);
        main(() -> {
            playing[0].setRepeatMode(Player.REPEAT_MODE_ONE);
            playing[0].play();
        });
        androidx.fragment.app.FragmentActivity host = (androidx.fragment.app.FragmentActivity) screen;
        OfflineCacheDialog[] panel = new OfflineCacheDialog[1];
        main(() -> {
            OfflineIntegration.showPanel(host);
            host.getSupportFragmentManager().executePendingTransactions();
            panel[0] = (OfflineCacheDialog) host.getSupportFragmentManager().findFragmentByTag("offline_panel");
            assertNotNull(panel[0]);
            assertEquals(android.view.View.GONE, panel[0].requireView().findViewById(R.id.offline_settings_button).getVisibility());
            android.view.View back = panel[0].requireView().findViewById(R.id.offline_back);
            android.view.ViewGroup header = (android.view.ViewGroup) back.getParent();
            assertEquals(back, header.getChildAt(header.getChildCount() - 1));
        });
        boolean[] populated = {false};
        long deadline = System.currentTimeMillis() + 10000;
        do {
            main(() -> {
                androidx.recyclerview.widget.RecyclerView list = panel[0].requireView().findViewById(R.id.offline_list);
                android.view.View root = panel[0].requireView();
                int[] location = new int[2];
                root.getLocationOnScreen(location);
                populated[0] = list.getChildCount() == 1
                        && ((com.google.android.material.sidesheet.SideSheetDialog) panel[0].requireDialog())
                                .getBehavior().getState() == com.google.android.material.sidesheet.SideSheetBehavior.STATE_EXPANDED
                        && location[0] + root.getWidth() <= screen.getResources().getDisplayMetrics().widthPixels;
            });
            if (!populated[0]) Thread.sleep(100);
        } while (!populated[0] && System.currentTimeMillis() < deadline);
        assertTrue("Episodes from one show must start in a single collapsed folder", populated[0]);
        main(() -> {
            androidx.recyclerview.widget.RecyclerView list = panel[0].requireView().findViewById(R.id.offline_list);
            assertEquals("离线测试", ((android.widget.TextView) list.getChildAt(0).findViewById(R.id.offline_group_name)).getText().toString());
            assertTrue(list.getChildAt(0).getContentDescription().toString().contains("2"));
            list.getChildAt(0).performClick();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> {
            androidx.recyclerview.widget.RecyclerView list = panel[0].requireView().findViewById(R.id.offline_list);
            assertEquals(3, list.getAdapter().getItemCount());
            list.getChildAt(0).performClick();
        });
        Thread.sleep(1200);
        main(() -> {
            androidx.recyclerview.widget.RecyclerView list = panel[0].requireView().findViewById(R.id.offline_list);
            assertEquals("Polling must preserve a folded folder", 1, list.getAdapter().getItemCount());
            list.getChildAt(0).performClick();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> {
            android.view.View root = panel[0].requireView();
            androidx.recyclerview.widget.RecyclerView list = root.findViewById(R.id.offline_list);
            assertEquals(context.getString(R.string.offline_paused),
                    ((android.widget.TextView) list.getChildAt(1).findViewById(R.id.offline_status)).getText().toString());
            assertEquals(context.getString(R.string.offline_completed),
                    ((android.widget.TextView) list.getChildAt(2).findViewById(R.id.offline_status)).getText().toString());
            assertEquals(android.view.View.GONE, root.findViewById(R.id.offline_empty_container).getVisibility());
            int[] position = new int[2];
            root.getLocationOnScreen(position);
            assertTrue("Panel must leave playback visible on the left", position[0] > 0);
            assertTrue("Panel width must retain the original 420dp limit", root.getWidth() <= 420 * screen.getResources().getDisplayMetrics().density + 1);
            assertEquals(context.getString(R.string.offline_play), list.getChildAt(2).findViewById(R.id.offline_action).getContentDescription());
            assertTrue("A completed cache row must stay below 80dp tall",
                    list.getChildAt(2).getHeight() <= 80 * screen.getResources().getDisplayMetrics().density);
            assertTrue("Opening the panel must keep the video playing", playing[0].isPlaying());
        });
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 3000);
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (screenshot != null) {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                    new java.io.File(context.getExternalFilesDir(null), "offline-panel-preview.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
            screenshot.recycle();
            try (java.io.InputStream copied = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                            "cp " + new java.io.File(context.getExternalFilesDir(null), "offline-panel-preview.png").getAbsolutePath()
                                    + " /data/local/tmp/android-tv-cache-preview.png"))) {
                copied.readAllBytes();
            }
        }
        main(() -> {
            androidx.recyclerview.widget.RecyclerView list = panel[0].requireView().findViewById(R.id.offline_list);
            list.getChildAt(2).findViewById(R.id.offline_delete).performClick();
        });
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 3000);
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(serviceInfo);
        boolean menuClicked = false;
        long menuDeadline = System.currentTimeMillis() + 5000;
        do {
            for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                var root = window.getRoot(); if (root == null) continue;
                for (var node : root.findAccessibilityNodeInfosByText(context.getString(R.string.offline_delete))) {
                    menuClicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                    if (!menuClicked && node.getParent() != null)
                        menuClicked = node.getParent().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                    if (menuClicked) break;
                }
                if (menuClicked) break;
            }
            if (!menuClicked) Thread.sleep(100);
        } while (!menuClicked && System.currentTimeMillis() < menuDeadline);
        assertTrue("More actions must retain deletion", menuClicked);
        automation.waitForIdle(500, 3000);
        boolean clicked = false;
        long confirmationDeadline = System.currentTimeMillis() + 5000;
        do {
            for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                for (android.view.accessibility.AccessibilityNodeInfo node :
                        root.findAccessibilityNodeInfosByViewId("android:id/button1")) {
                    clicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                    if (clicked) break;
                }
                if (clicked) break;
            }
            if (!clicked) Thread.sleep(100);
        } while (!clicked && System.currentTimeMillis() < confirmationDeadline);
        assertTrue("Delete confirmation must be clickable", clicked);
        deadline = System.currentTimeMillis() + 10000;
        while (cache.find(completed.id) != null && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertNull("Confirmed deletion must remove the task and media", cache.find(completed.id));
        assertEquals("Other tasks must be retained", Download.STATE_STOPPED, cache.find(paused.id).state);
        main(() -> panel[0].dismissNow());
    }

    private Player launchSharedPlayer(Download download) throws Exception {
        main(() -> screen.finish());
        screen = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, com.fongmi.android.tv.ui.activity.VideoActivity.class)
                        .putExtra("offline_id", download.request.id).putExtra("id", download.request.id)
                        .putExtra("key", "offline").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Player[] player = new Player[1];
        boolean[] ready = {false};
        long end = System.currentTimeMillis() + 10000;
        do {
            main(() -> {
                player[0] = ((PlayerView) screen.findViewById(R.id.player)).getPlayer();
                ready[0] = player[0] != null && player[0].getPlaybackState() == Player.STATE_READY;
            });
            if (!ready[0]) Thread.sleep(100);
        } while (!ready[0] && System.currentTimeMillis() < end);
        assertTrue("The regular playback page must prepare cached video", ready[0]);
        return player[0];
    }

    @Test
    public void sharedControlsKeepCacheOnlyPlaybackAcrossDecodeAndRotation() throws Exception {
        com.fongmi.android.tv.bean.History original = new com.fongmi.android.tv.bean.History();
        original.setKey("controls-fixture@@@" + UUID.randomUUID() + "@@@733");
        original.setCid(733);
        original.setVodName("离线测试");
        original.setVodPic("");
        original.setVodFlag("测试线路");
        original.setVodRemarks("第1集");
        original.setEpisodeUrl("https://catalog.example/episode/1");
        histories.add(original);
        OfflineVideo video = new OfflineVideo(UUID.randomUUID().toString(), original.getVodName(), original.getVodRemarks(),
                original.getVodFlag(), server.url("a/sample.mp4"), null, java.util.Collections.singletonMap("Cookie", "alpha"),
                original.toString(), "[]");
        ids.add(video.id);
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        server.blocked = true;
        int requests = server.requests.get();
        Player player = launchSharedPlayer(download);
        main(() -> {
            assertTrue(com.fongmi.android.tv.server.Server.get().getService().player().isOffline());
            com.fongmi.android.tv.player.PlayerManager shared = com.fongmi.android.tv.server.Server.get().getService().player();
            com.fongmi.android.tv.bean.Danmaku comment = com.fongmi.android.tv.bean.Danmaku.from(new java.io.File(context.getFilesDir(), "disabled-comments.xml").getAbsolutePath());
            shared.setDanmaku(comment);
            shared.toggleDanmaku(comment);
            player.pause();
            player.seekTo(500);
            screen.findViewById(R.id.control).findViewById(R.id.speed).performClick();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> {
            for (androidx.fragment.app.Fragment fragment : ((androidx.fragment.app.FragmentActivity) screen).getSupportFragmentManager().getFragments()) {
                if (fragment.getView() == null || fragment.getView().findViewById(R.id.preset05) == null) continue;
                fragment.getView().findViewById(R.id.preset05).performClick();
                ((androidx.fragment.app.DialogFragment) fragment).dismiss();
            }
            screen.findViewById(R.id.control).findViewById(R.id.decode).performClick();
        });
        Player[] rebuilt = new Player[1];
        boolean[] ready = {false};
        long end = System.currentTimeMillis() + 10000;
        do {
            main(() -> {
                rebuilt[0] = ((PlayerView) screen.findViewById(R.id.player)).getPlayer();
                ready[0] = rebuilt[0] != null && rebuilt[0].getPlaybackState() == Player.STATE_READY;
            });
            if (!ready[0]) Thread.sleep(100);
        } while (!ready[0] && System.currentTimeMillis() < end);
        assertTrue(ready[0]);
        main(() -> {
            assertSame("Current decoder selection retains the shared player", player, rebuilt[0]);
            assertEquals(1.5f, rebuilt[0].getPlaybackParameters().speed, 0.01f);
            assertFalse("Decode must retain the pause state", rebuilt[0].getPlayWhenReady());
            assertFalse("Decode must keep a disabled danmaku item disabled",
                    com.fongmi.android.tv.server.Server.get().getService().player().getDanmakus().get(0).isSelected());
            rebuilt[0].seekTo(500);
            screen.findViewById(R.id.control).findViewById(R.id.opening).performClick();
            rebuilt[0].seekTo(3000);
            screen.findViewById(R.id.control).findViewById(R.id.ending).performClick();
            screen.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> {
            assertSame("Rotation must retain the normal player", rebuilt[0], ((PlayerView) screen.findViewById(R.id.player)).getPlayer());
            com.fongmi.android.tv.ui.activity.VideoActivity page = (com.fongmi.android.tv.ui.activity.VideoActivity) screen;
            page.onTimeChanged(System.currentTimeMillis());
            screen.finish();
        });
        waitForDestroyedScreen();
        assertEquals("Video playback and decode must never request the source", requests, server.requests.get());
        CountDownLatch saved = new CountDownLatch(1);
        com.fongmi.android.tv.utils.Task.executeSerial(saved::countDown);
        assertTrue(saved.await(5, TimeUnit.SECONDS));
        com.fongmi.android.tv.bean.History history = com.fongmi.android.tv.db.AppDatabase.get().getHistoryDao().find(733, original.getKey());
        assertNotNull("Playback settings must be saved to online history", history);
        assertTrue("Opening marker must survive", history.getOpening() > 0);
        assertTrue("Ending marker must survive", history.getEnding() > 0);
        assertEquals(1.5f, com.fongmi.android.tv.setting.SpeedSetting.getPlayback(), 0.01f);
    }

    private android.widget.TextView findText(android.view.View view, String label) {
        if (view instanceof android.widget.TextView && label.contentEquals(((android.widget.TextView) view).getText()))
            return (android.widget.TextView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = findText(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void waitForDestroyedScreen() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        boolean[] destroyed = {false};
        do {
            main(() -> destroyed[0] = screen.isDestroyed());
            if (!destroyed[0]) Thread.sleep(50);
        } while (!destroyed[0] && System.currentTimeMillis() < deadline);
        assertTrue("Exit must finish saving playback history", destroyed[0]);
    }

    @Test
    public void sharedPlayerResumesOnlineHistoryAndWritesProgressBack() throws Exception {
        com.fongmi.android.tv.bean.History original = new com.fongmi.android.tv.bean.History();
        original.setKey("resume-fixture@@@" + UUID.randomUUID() + "@@@732");
        original.setCid(732);
        original.setVodName("共享播放记录测试");
        original.setVodRemarks("第1集");
        original.setVodFlag("在线线路");
        original.setEpisodeUrl("https://catalog.example/episode/1");
        original.setDuration(5000);
        original.setPosition(100);
        OfflineVideo video = new OfflineVideo(UUID.randomUUID().toString(), original.getVodName(), original.getVodRemarks(),
                original.getVodFlag(), server.url("a/sample.mp4"), null, java.util.Collections.singletonMap("Cookie", "alpha"),
                original.toString(), "[]");
        ids.add(video.id);
        try {
            original.setPosition(1200);
            original.setCreateTime(System.currentTimeMillis());
            original.save();
            add(video);
            Download download = waitState(video.id, Download.STATE_COMPLETED);
            server.blocked = true;
            int requests = server.requests.get();
            Player player = launchSharedPlayer(download);
            main(() -> {
                player.pause();
                assertTrue("Cached playback must resume the current online position", player.getCurrentPosition() >= 1200);
                player.seekTo(2200);
                ((com.fongmi.android.tv.ui.activity.VideoActivity) screen).onTimeChanged(System.currentTimeMillis());
                screen.finish();
            });
            waitForDestroyedScreen();
            CountDownLatch written = new CountDownLatch(1);
            com.fongmi.android.tv.utils.Task.executeSerial(written::countDown);
            assertTrue(written.await(5, TimeUnit.SECONDS));
            com.fongmi.android.tv.bean.History saved = com.fongmi.android.tv.db.AppDatabase.get().getHistoryDao().find(732, original.getKey());
            assertTrue("Cached playback must update online progress", saved.getPosition() >= 2200);
            assertEquals(original.getEpisodeUrl(), saved.getEpisodeUrl());
            assertEquals(original.getVodFlag(), saved.getVodFlag());
            assertFalse("Valid tasks use one canonical history", context.getSharedPreferences("offline_playback", 0).contains("history:" + original.getKey()));
            saved.setPosition(3200);
            saved.setCreateTime(System.currentTimeMillis() + 1);
            saved.save();
            Player resumed = launchSharedPlayer(download);
            main(() -> {
                resumed.pause();
                assertTrue("Later online progress must be read on the next cache launch", resumed.getCurrentPosition() >= 3200);
            });
            assertEquals(requests, server.requests.get());
        } finally {
            if (screen != null) main(() -> screen.finish());
            waitForDestroyedScreen();
            CountDownLatch done = new CountDownLatch(1);
            com.fongmi.android.tv.utils.Task.executeSerial(done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            com.fongmi.android.tv.db.AppDatabase.get().getHistoryDao().delete(732, original.getKey());
        }
    }

    @Test
    public void cloudDanmakuButtonUsesOriginalParametersWithoutReplacingCachedVideo() throws Exception {
        cloudDanmakuButton(false);
    }

    @Test
    public void cloudDanmakuRestoresStoredConfigurationAndDoesNotRequireANameKeyword() throws Exception {
        cloudDanmakuButton(true);
    }

    private void cloudDanmakuButton(boolean coldStart) throws Exception {
        String label = coldStart ? "云搜" : "弹幕云搜";
        String originalUrl = "https://catalog.example/episode/7";
        String source = "{\"key\":\"fixture-source\",\"flag\":\"original-line\",\"url\":\"" + originalUrl + "\"}";
        OfflineVideo video = new OfflineVideo(UUID.randomUUID().toString(), "云搜测试", "第7集", "测试线路",
                server.url("a/sample.mp4"), null, java.util.Collections.singletonMap("Cookie", "alpha"), "", "[]", source);
        ids.add(video.id);
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        com.fongmi.android.tv.bean.Parse search = com.fongmi.android.tv.bean.Parse.get(1, server.url("cloud?url="));
        search.setName(label);
        java.lang.reflect.Field parses = com.fongmi.android.tv.api.config.VodConfig.class.getDeclaredField("parses");
        parses.setAccessible(true);
        Object previousParses = parses.get(com.fongmi.android.tv.api.config.VodConfig.get());
        java.lang.reflect.Field configField = com.fongmi.android.tv.api.config.VodConfig.class.getSuperclass().getDeclaredField("config");
        configField.setAccessible(true);
        Object previousConfig = configField.get(com.fongmi.android.tv.api.config.VodConfig.get());
        if (coldStart) {
            com.fongmi.android.tv.bean.Config stored = com.fongmi.android.tv.bean.Config.create(0)
                    .json("{\"parses\":[" + com.fongmi.android.tv.App.gson().toJson(search) + "]}");
            configField.set(com.fongmi.android.tv.api.config.VodConfig.get(), stored);
            parses.set(com.fongmi.android.tv.api.config.VodConfig.get(), null);
        } else parses.set(com.fongmi.android.tv.api.config.VodConfig.get(), new ArrayList<>(java.util.Collections.singletonList(search)));
        try {
            server.blocked = true;
            server.allowComments = true;
            int requests = server.requests.get();
            Player player = launchSharedPlayer(download);
            main(() -> {
                player.pause();
                if (BuildConfig.FLAVOR.startsWith("mobile")) ((com.fongmi.android.tv.ui.activity.VideoActivity) screen).onDoubleTap();
                else screen.findViewById(R.id.video).performClick();
            });
            boolean[] clicked = {false};
            long deadline = System.currentTimeMillis() + 10000;
            do {
                main(() -> {
                    if (!screen.findViewById(R.id.control).isShown())
                        ((com.fongmi.android.tv.ui.activity.VideoActivity) screen).onSingleTap();
                    android.view.View row = screen.findViewById(R.id.control).findViewById(R.id.parse);
                    if (row.isShown()) row.performClick();
                    for (androidx.fragment.app.Fragment fragment : ((androidx.fragment.app.FragmentActivity) screen).getSupportFragmentManager().getFragments()) {
                        if (!(fragment instanceof com.fongmi.android.tv.ui.dialog.ParseDialog)) continue;
                        android.widget.TextView button = findText(fragment.getView(), label);
                        if (button != null) clicked[0] = button.performClick();
                    }
                });
                if (!clicked[0]) Thread.sleep(100);
            } while (!clicked[0] && System.currentTimeMillis() < deadline);
            assertTrue("The existing dynamic bottom row must expose cloud search", clicked[0]);
            boolean[] selected = {false};
            deadline = System.currentTimeMillis() + 10000;
            do {
                main(() -> selected[0] = com.fongmi.android.tv.server.Server.get().getService().player().haveDanmaku());
                if (!selected[0]) Thread.sleep(100);
            } while (!selected[0] && System.currentTimeMillis() < deadline);
            assertTrue("Cloud search must select returned danmaku", selected[0]);
            assertEquals(originalUrl, server.lastCloudInput);
            main(() -> {
                assertSame(player, ((PlayerView) screen.findViewById(R.id.player)).getPlayer());
                assertFalse(player.getPlayWhenReady());
                assertTrue(com.fongmi.android.tv.server.Server.get().getService().player().isOffline());
            });
            assertEquals("Cloud search must not request the original video", requests, server.requests.get());
            assertTrue(context.getSharedPreferences("offline_playback", 0).getString("danmaku:" + video.id, "").contains("云搜结果"));
        } finally {
            parses.set(com.fongmi.android.tv.api.config.VodConfig.get(), previousParses);
            configField.set(com.fongmi.android.tv.api.config.VodConfig.get(), previousConfig);
        }
    }

    @Test
    public void savedDanmakuAndLocalImportRemainReadableWithoutNetwork() throws Exception {
        com.fongmi.android.tv.bean.Danmaku comments = com.fongmi.android.tv.bean.Danmaku.from(server.url("comments/comments.xml"));
        okhttp3.OkHttpClient client = OfflineDanmakuCache.client(com.github.catvod.net.OkHttp.client());
        okhttp3.Request request = new okhttp3.Request.Builder().url(comments.getUrl()).build();
        byte[] expected;
        try (okhttp3.Response response = client.newCall(request).execute()) { expected = response.body().bytes(); }
        assertTrue(new String(expected, StandardCharsets.UTF_8).contains("offline comment"));
        server.blocked = true;
        try (okhttp3.Response response = client.newCall(request).execute()) { assertArrayEquals(expected, response.body().bytes()); }
        java.io.File imported = new java.io.File(context.getFilesDir(), "test-comments.xml");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(imported)) { output.write(expected); }
        try (InputStream input = new java.io.FileInputStream(imported)) { assertArrayEquals(expected, input.readAllBytes()); }
        imported.delete();
    }

    @Test
    public void mp4SurvivesGeneralCacheCleanupAndPlaysOffline() throws Exception {
        OfflineVideo video = video("a/sample.mp4", "alpha");
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        assertTrue(download.getBytesDownloaded() > 0);
        CountDownLatch cleared = new CountDownLatch(1);
        FileUtil.clearCache(new Callback() { @Override public void success() { cleared.countDown(); } });
        assertTrue(cleared.await(10, TimeUnit.SECONDS));
        assertEquals(Download.STATE_COMPLETED, cache.find(video.id).state);
        playOffline(download, true);
    }

    @Test public void cloudPartialResponseMustNotBeTreatedAsAWholeFile() throws Exception {
        OfflineVideo video = video("a/capped.mp4", "alpha");
        long expected;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("offline/sample.mp4")) {
            expected = input.readAllBytes().length;
        }
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        assertEquals("Content-Range total, not the first response length, is the full video", expected, download.getBytesDownloaded());
        playOffline(download, true);
    }

    @Test public void missingHlsTailCannotBeHiddenByOtherCachedResources() throws Exception {
        OfflineVideo video = transferable("a/hls/master.m3u8");
        add(video); waitState(video.id, Download.STATE_COMPLETED);
        File subtitle = File.createTempFile("verify-padding-", ".srt", context.getCacheDir());
        try {
            java.nio.file.Files.writeString(subtitle.toPath(), "1\n00:00:00,000 --> 00:00:03,000\n" + "Subtitle text ".repeat(8192));
            OfflineSubtitles.save(context, cache, video, com.fongmi.android.tv.bean.Sub.from("字幕.srt", subtitle.toURI().toString()), false);
        } finally { subtitle.delete(); }
        String tail = video.id + ":" + server.url("a/hls/audio4.ts");
        assertFalse(cache.storage().getCachedSpans(tail).isEmpty());
        cache.storage().removeResource(tail);
        int requests = server.requests.get();
        server.blocked = true;
        assertNull("All selected segments must be present; total byte count is insufficient", cache.completedFor(OfflineHistory.original(video)));
        assertEquals("Integrity detection must never redownload media", requests, server.requests.get());
    }

    @Test public void oldHalfMp4WithIncorrectEofMetadataFailsManualVerification() throws Exception {
        OfflineVideo video = video("a/sample.mp4", "alpha");
        add(video);
        Download completed = waitState(video.id, Download.STATE_COMPLETED);
        String key = video.id + ":" + video.url;
        byte[] full = java.nio.file.Files.readAllBytes(cache.storage().getCachedSpans(key).first().file.toPath());
        int half = full.length / 2;
        cache.storage().removeResource(key);
        var hole = cache.storage().startReadWriteNonBlocking(key, 0, half);
        try {
            File file = cache.storage().startFile(key, 0, half);
            try (var output = new java.io.FileOutputStream(file)) { output.write(full, 0, half); }
            cache.storage().commitFile(file, half);
            cache.storage().applyContentMetadataMutations(key, androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(
                    new androidx.media3.datasource.cache.ContentMetadataMutations(), half));
        } finally { cache.storage().releaseHoleSpan(hole); }
        int requests = server.requests.get(); server.blocked = true;
        try {
            cache.verify(completed);
            fail("MP4's declared media box extends beyond the incorrectly recorded cached EOF");
        } catch (java.io.IOException expected) {}
        assertEquals(requests, server.requests.get());
    }

    private String shellOutput(String command) throws Exception {
        try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void wakeTestScreen() throws Exception {
        shellOutput("input keyevent 224");
        var power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        var keyguard = (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        long deadline = System.currentTimeMillis() + 5000;
        while (!power.isInteractive() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        do {
            shellOutput("wm dismiss-keyguard");
            if (!keyguard.isKeyguardLocked()) return;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        assertFalse("UI tests need an unlocked screen after the screen-off test", keyguard.isKeyguardLocked());
    }

    private boolean downloadCpuLockHeld() throws Exception {
        // dumpsys also includes released locks in its history; inspect only active locks.
        for (String line : shellOutput("dumpsys power").split("\\n")) {
            if (line.trim().startsWith("PARTIAL_WAKE_LOCK") && line.contains("'Android-TV:OfflineCache'")) return true;
        }
        return false;
    }

    @Test public void screenOffDownloadHoldsCpuLockAndPauseReleasesIt() throws Exception {
        OfflineVideo video = video("a/slow.mp4", "alpha"); add(video);
        waitState(video.id, Download.STATE_DOWNLOADING);
        try {
            shellOutput("input keyevent 223");
            long deadline = System.currentTimeMillis() + 3000;
            boolean held;
            do {
                held = downloadCpuLockHeld();
                if (held) break; Thread.sleep(100);
            } while (System.currentTimeMillis() < deadline);
            assertTrue("An active screen-off download needs its own bounded CPU wake lock", held);
            assertFalse(((android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE)).isInteractive());
            main(() -> cache.pause(video.id)); waitState(video.id, Download.STATE_STOPPED);
            deadline = System.currentTimeMillis() + 3000;
            do {
                held = downloadCpuLockHeld();
                if (!held) break; Thread.sleep(100);
            } while (System.currentTimeMillis() < deadline);
            assertFalse("A paused download must not keep the CPU awake", held);
            main(() -> cache.continueDownload(cacheFind(video.id)));
            waitState(video.id, Download.STATE_COMPLETED);
            deadline = System.currentTimeMillis() + 3000;
            do {
                held = downloadCpuLockHeld();
                if (!held) break; Thread.sleep(100);
            } while (System.currentTimeMillis() < deadline);
            assertFalse("Completed downloads must release their wake lock", held);
        } finally { wakeTestScreen(); }
    }

    private Download cacheFind(String id) {
        try { return cache.find(id); } catch (java.io.IOException error) { throw new RuntimeException(error); }
    }

    @Test public void concurrentCacheAndOnlineResolutionKeepEachEpisodeMedia() throws Exception {
        var config = com.fongmi.android.tv.api.config.VodConfig.get();
        var sitesField = config.getClass().getDeclaredField("sites"); sitesField.setAccessible(true);
        Object previousSites = sitesField.get(config);
        CountDownLatch firstEntered = new CountDownLatch(1), anotherEntered = new CountDownLatch(1);
        var plugin = new com.github.catvod.crawler.Spider() {
            volatile String selected;
            @Override public String playerContent(String flag, String id, List<String> flags) throws Exception {
                selected = id;
                if (id.equals("10")) { firstEntered.countDown(); anotherEntered.await(800, TimeUnit.MILLISECONDS); }
                else anotherEntered.countDown();
                Thread.sleep(100);
                return "{\"parse\":0,\"url\":\"" + server.url("comments/episode-" + selected + ".mp4") + "\"}";
            }
        };
        var site = new com.fongmi.android.tv.bean.Site() {
            @Override public String getKey() { return "race-source"; }
            @Override public Integer getType() { return 3; }
            @Override public com.fongmi.android.tv.bean.Site recent() { return this; }
            @Override public com.github.catvod.crawler.Spider spider() { return plugin; }
        };
        List<com.fongmi.android.tv.bean.History> episodes = new ArrayList<>();
        for (int number : new int[]{10, 11, 12}) {
            var history = independentHistory(); history.setKey("race-source@@@race-show@@@" + history.getCid());
            history.setVodRemarks("第" + number + "集"); history.setEpisodeUrl(Integer.toString(number));
            episodes.add(history); ids.add(independentId(history)); histories.add(history);
        }
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            sitesField.set(config, new ArrayList<>(java.util.Collections.singletonList(site)));
            CountDownLatch added = new CountDownLatch(3); AtomicInteger errors = new AtomicInteger();
            main(() -> OfflineEpisodeResolver.resolve(context, episodes.get(0), message -> {
                if (message != R.string.offline_preparing) { if (message != R.string.offline_added) errors.incrementAndGet(); added.countDown(); }
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var online = workers.submit(() -> com.fongmi.android.tv.api.SiteApi.playerContent("race-source", "直连", "20"));
            main(() -> {
                for (var history : episodes.subList(1, 3)) OfflineEpisodeResolver.resolve(context, history, message -> {
                    if (message != R.string.offline_preparing) { if (message != R.string.offline_added) errors.incrementAndGet(); added.countDown(); }
                });
            });
            assertTrue(added.await(30, TimeUnit.SECONDS)); assertEquals(0, errors.get());
            assertEquals(server.url("comments/episode-20.mp4"), online.get(5, TimeUnit.SECONDS).getUrl().v());
            for (int i = 0; i < 3; i++) {
                Download saved = waitState(independentId(episodes.get(i)), Download.STATE_COMPLETED);
                assertEquals("An episode's label must describe its actual downloaded media", server.url("comments/episode-" + (10 + i) + ".mp4"), saved.request.uri.toString());
                String key = saved.request.id + ":" + saved.request.uri;
                byte[] bytes = java.nio.file.Files.readAllBytes(cache.storage().getCachedSpans(key).first().file.toPath());
                assertEquals("Actual media differs per episode", 10 + i, java.nio.ByteBuffer.wrap(bytes, bytes.length - 4, 4).getInt());
            }
        } finally { workers.shutdownNow(); sitesField.set(config, previousSites); }
    }

    @Test public void cacheSettingsApplyImmediatelyAndPersistAfterReopening() throws Exception {
        var preferences = com.github.catvod.utils.Prefers.getPrefers();
        Object previous = preferences.getAll().get("offline_parallel_downloads");
        Activity settings = null;
        try {
            int button = context.getResources().getIdentifier("offline_settings_button", "id", context.getPackageName());
            assertTrue("The cache page needs an entry to its settings page", button != 0);
            var monitor = new android.app.Instrumentation.ActivityMonitor("com.fongmi.android.tv.offline.OfflineCacheSettingsActivity", null, false);
            InstrumentationRegistry.getInstrumentation().addMonitor(monitor);
            try {
                main(() -> screen.findViewById(button).performClick());
                settings = monitor.waitForActivityWithTimeout(5000); assertNotNull(settings);
            } finally { InstrumentationRegistry.getInstrumentation().removeMonitor(monitor); }
            settings = waitForSettingsWindow();
            Activity page = settings;
            int selector = context.getResources().getIdentifier("offline_parallel", "id", context.getPackageName());
            main(() -> page.findViewById(selector).performClick());
            var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            var serviceInfo = automation.getServiceInfo();
            serviceInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(serviceInfo);
            automation.waitForIdle(500, 3000);
            boolean clicked = false;
            long choiceDeadline = System.currentTimeMillis() + 5000;
            do {
                for (var window : automation.getWindows()) {
                    var root = window.getRoot(); if (root == null) continue;
                    for (var node : root.findAccessibilityNodeInfosByText(context.getString(R.string.offline_parallel_value, 4))) {
                        android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                        if (bounds.isEmpty()) continue;
                        long time = android.os.SystemClock.uptimeMillis();
                        var down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
                        var up = android.view.MotionEvent.obtain(time, time + 50, android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
                        try { clicked = automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true); }
                        finally { down.recycle(); up.recycle(); }
                        if (clicked) break;
                    }
                    if (clicked) break;
                }
                if (!clicked) Thread.sleep(100);
            } while (!clicked && System.currentTimeMillis() < choiceDeadline);
            assertTrue("The selected limit is editable", clicked);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            main(() -> cache.manager().pauseDownloads());
            List<OfflineVideo> videos = new ArrayList<>();
            for (int i = 0; i < 4; i++) { OfflineVideo video = video("a/slow.mp4", "alpha"); videos.add(video); add(video); waitState(video.id, Download.STATE_QUEUED); }
            main(() -> cache.manager().resumeDownloads());
            long deadline = System.currentTimeMillis() + 4000; int running;
            do {
                running = 0; for (var video : videos) if (cache.find(video.id).state == Download.STATE_DOWNLOADING) running++;
                if (running == 4) break; Thread.sleep(50);
            } while (System.currentTimeMillis() < deadline);
            assertEquals("The running manager must use the setting without restarting the app", 4, running);
            main(page::finish);
            settings = InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,
                    Class.forName("com.fongmi.android.tv.offline.OfflineCacheSettingsActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity reopened = settings;
            int valueId = context.getResources().getIdentifier("offline_parallel_value", "id", context.getPackageName());
            main(() -> assertEquals(context.getString(R.string.offline_parallel_value, 4), ((android.widget.TextView) reopened.findViewById(valueId)).getText().toString()));
        } finally {
            if (settings != null) { Activity page = settings; main(page::finish); }
            var editor = preferences.edit(); if (previous instanceof Integer value) editor.putInt("offline_parallel_downloads", value); else editor.remove("offline_parallel_downloads"); editor.commit();
            main(() -> cache.manager().resumeDownloads());
        }
    }

    @Test public void differentEpisodesCannotSilentlyReuseTheSameResolvedAddress() throws Exception {
        OfflineVideo first = transferable("a/sample.mp4"); add(first); waitState(first.id, Download.STATE_COMPLETED);
        var history = OfflineHistory.original(first); history.setVodRemarks("第3集"); history.setEpisodeUrl("https://episode.test/third");
        OfflineVideo another = new OfflineVideo(OfflineVideo.identity(history), first.title, "第3集", first.line,
                first.url, first.mimeType, first.headers, history.toString(), first.danmaku, first.source);
        ids.add(another.id); AtomicInteger message = new AtomicInteger(); CountDownLatch done = new CountDownLatch(1);
        main(() -> cache.add(another, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, result -> {
            if (result != R.string.offline_preparing) { message.set(result); done.countDown(); }
        }));
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertEquals(R.string.offline_episode_conflict, message.get());
        assertNull("Do not create a correctly labeled task with another episode's media", cache.find(another.id));
    }

    @Test public void defaultDownloadLimitStartsThreeTasksAndQueuesTheFourth() throws Exception {
        main(() -> cache.manager().pauseDownloads());
        List<OfflineVideo> videos = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) { OfflineVideo video = video("a/slow.mp4", "alpha"); videos.add(video); add(video); waitState(video.id, Download.STATE_QUEUED); }
            main(() -> cache.manager().resumeDownloads());
            long deadline = System.currentTimeMillis() + 4000; int downloading;
            do {
                downloading = 0;
                for (OfflineVideo video : videos) if (cache.find(video.id).state == Download.STATE_DOWNLOADING) downloading++;
                if (downloading == 3) break;
                Thread.sleep(50);
            } while (System.currentTimeMillis() < deadline);
            assertEquals("Default concurrency must start three complete episode downloads", 3, downloading);
            assertEquals(1, videos.stream().filter(video -> {
                try { return cache.find(video.id).state == Download.STATE_QUEUED; }
                catch (Exception error) { throw new RuntimeException(error); }
            }).count());
        } finally { main(() -> cache.manager().resumeDownloads()); }
    }

    @Test public void wrongCloudRangeCannotCompleteTheDownload() throws Exception {
        OfflineVideo video = video("a/badrange.mp4", "alpha"); add(video);
        Download failed = waitState(video.id, Download.STATE_FAILED);
        assertTrue("A repeated first chunk must never be accepted as the remaining media", failed.getBytesDownloaded() < 51131);
        assertEquals(R.string.offline_integrity_error, cache.failure(video.id));
    }

    @Test public void manualRecacheRefreshesTheExactEpisodeThroughTheSourceApi() throws Exception {
        OfflineVideo base = transferable("a/sample.mp4");
        var history = OfflineHistory.original(base);
        var config = com.fongmi.android.tv.api.config.VodConfig.get();
        var configField = config.getClass().getSuperclass().getDeclaredField("config"); configField.setAccessible(true);
        var sitesField = config.getClass().getDeclaredField("sites"); sitesField.setAccessible(true);
        Object previousConfig = configField.get(config), previousSites = sitesField.get(config);
        history.setKey("repair_test@@@refresh-show@@@" + history.getCid());
        history.setVodFlag("测试线路"); history.setVodRemarks("第2集");
        OfflineVideo video = new OfflineVideo(OfflineVideo.identity(history), base.title, history.getVodRemarks(), history.getVodFlag(),
                base.url, base.mimeType, base.headers, history.toString(), base.danmaku, base.source);
        ids.add(video.id); histories.add(history);
        try {
            configField.set(config, com.fongmi.android.tv.bean.Config.find(history.getCid()));
            var site = com.fongmi.android.tv.App.gson().fromJson("{\"key\":\"repair_test\",\"name\":\"Repair\",\"type\":1,\"api\":\"" + server.url("repair-api") + "\"}", com.fongmi.android.tv.bean.Site.class);
            sitesField.set(config, new ArrayList<>(java.util.Collections.singletonList(site)));
            add(video); Download old = waitState(video.id, Download.STATE_COMPLETED);
            CountDownLatch done = new CountDownLatch(1); AtomicInteger message = new AtomicInteger();
            main(() -> OfflineCacheRepair.start(context, old, result -> {
                if (result != R.string.offline_preparing) { message.set(result); done.countDown(); }
            }));
            assertTrue(done.await(50, TimeUnit.SECONDS)); assertEquals(R.string.offline_added, message.get());
            Download replaced = waitState(video.id, Download.STATE_COMPLETED);
            assertEquals("Use the current exact episode address, not the saved expired address", server.url("comments/sample.mp4"), replaced.request.uri.toString());
            assertFalse(OfflineCacheRepair.busy(video.id));
            assertTrue(OfflineVideo.sameEpisode(history, OfflineHistory.original(OfflineVideo.decode(replaced.request.data))));
            cache.verify(replaced);
        } finally {
            configField.set(config, previousConfig); sitesField.set(config, previousSites);
        }
    }

    @Test public void invalidFreshAddressLeavesCompletedCacheUsableAndAllowsRetry() throws Exception {
        OfflineVideo video = transferable("a/sample.mp4"); add(video);
        Download old = waitState(video.id, Download.STATE_COMPLETED);
        OfflineVideo expired = new OfflineVideo(OfflineVideo.identity(OfflineHistory.original(video)), video.title, video.episode, video.line,
                server.url("a/expired.m3u8"), null, video.headers, video.history, video.danmaku, video.source);
        CountDownLatch failed = new CountDownLatch(1); AtomicInteger message = new AtomicInteger();
        main(() -> cache.replace(old, expired, result -> {
            if (result != R.string.offline_preparing) { message.set(result); failed.countDown(); }
        }));
        assertTrue(failed.await(30, TimeUnit.SECONDS));
        assertNotEquals(R.string.offline_added, message.get());
        assertEquals(old.request.uri, cache.find(video.id).request.uri);
        cache.verify(cache.find(video.id));
        assertFalse(cache.storage().getCachedSpans(video.id + ":" + video.url).isEmpty());
        CountDownLatch retried = new CountDownLatch(1);
        main(() -> cache.replace(old, video, result -> {
            if (result != R.string.offline_preparing) { message.set(result); retried.countDown(); }
        }));
        assertTrue(retried.await(30, TimeUnit.SECONDS));
        assertEquals("Failed resolution must release its reservation", R.string.offline_added, message.get());
        waitState(video.id, Download.STATE_COMPLETED);
    }

    @Test public void manualRecacheUsesFreshMediaAndPreservesOtherTasksAndSubtitles() throws Exception {
        OfflineVideo base = transferable("a/sample.mp4");
        OfflineVideo video = new OfflineVideo("old-task-" + UUID.randomUUID(), base.title, base.episode, base.line,
                base.url, base.mimeType, base.headers, base.history, base.danmaku, base.source);
        ids.add(video.id); add(video);
        Download old = waitState(video.id, Download.STATE_COMPLETED);
        OfflineVideo other = video("shared/sample.mp4", "alpha"); add(other); waitState(other.id, Download.STATE_COMPLETED);
        File subtitle = File.createTempFile("recache-subtitle-", ".srt", context.getCacheDir());
        try {
            java.nio.file.Files.writeString(subtitle.toPath(), "1\n00:00:00,000 --> 00:00:03,000\nRepaired subtitle\n");
            OfflineSubtitles.save(context, cache, video, com.fongmi.android.tv.bean.Sub.from("重下字幕.srt", subtitle.toURI().toString(), "en", androidx.media3.common.MimeTypes.APPLICATION_SUBRIP), false);
            String comments = "[{\"name\":\"后加弹幕\",\"url\":\"https://comments.test/retained.xml\"}]";
            context.getSharedPreferences("offline_playback", 0).edit().putString("danmaku:" + video.id, comments).commit();
            OfflineVideo fresh = new OfflineVideo(OfflineVideo.identity(OfflineHistory.original(video)), video.title, video.episode, video.line,
                    server.url("b/sample.mp4"), video.mimeType, Map.of("Cookie", "beta"), video.history, video.danmaku, video.source);
            CountDownLatch done = new CountDownLatch(1); AtomicInteger message = new AtomicInteger();
            main(() -> cache.replace(old, fresh, result -> {
                if (result != R.string.offline_preparing) { message.set(result); done.countDown(); }
            }));
            assertTrue(done.await(50, TimeUnit.SECONDS));
            assertEquals(R.string.offline_added, message.get());
            Download replaced = waitState(old.request.id, Download.STATE_COMPLETED);
            assertEquals("The old task identity remains usable by cache-page links", old.request.id, replaced.request.id);
            assertEquals(fresh.url, replaced.request.uri.toString());
            assertTrue("The old media must be removed", cache.storage().getCachedSpans(video.id + ":" + video.url).isEmpty());
            assertEquals(Download.STATE_COMPLETED, cache.find(other.id).state);
            assertEquals(comments, context.getSharedPreferences("offline_playback", 0).getString("danmaku:" + video.id, ""));
            assertEquals(1, OfflineSubtitles.saved(context, video.id).length());
            assertTrue(subtitle.delete());
            subtitleCue(replaced, "en", "Repaired subtitle");
            playOffline(replaced, true);
        } finally { subtitle.delete(); }
    }

    @Test
    public void hlsCachesSeparateAudioAndAllVideoSegments() throws Exception {
        OfflineVideo video = video("a/hls/master.m3u8", "alpha");
        add(video);
        playOffline(waitState(video.id, Download.STATE_COMPLETED), true);
    }

    @Test
    public void dashCachesAudioAndVideoForOfflineSeeking() throws Exception {
        OfflineVideo video = video("a/dash/manifest.mpd", "alpha");
        add(video);
        playOffline(waitState(video.id, Download.STATE_COMPLETED), true);
    }

    @Test
    public void parallelRequestsKeepHeadersAndCacheNamespacesIndependent() throws Exception {
        OfflineVideo first = video("a/sample.mp4", "alpha");
        OfflineVideo second = video("b/sample.mp4", "beta");
        add(first);
        add(second);
        waitState(first.id, Download.STATE_COMPLETED);
        Download retained = waitState(second.id, Download.STATE_COMPLETED);
        CountDownLatch duplicate = new CountDownLatch(1);
        main(() -> cache.add(first, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, message -> {
            assertEquals(R.string.offline_already_cached, message);
            duplicate.countDown();
        }));
        assertTrue(duplicate.await(5, TimeUnit.SECONDS));
        main(() -> cache.remove(first.id));
        long end = System.currentTimeMillis() + 10000;
        while (cache.find(first.id) != null && System.currentTimeMillis() < end) Thread.sleep(100);
        assertNull(cache.find(first.id));
        assertEquals(0, server.rejected.get());
        playOffline(retained, true);
    }

    @Test
    public void pausedTaskRetainsProgressAcrossServiceResume() throws Exception {
        OfflineVideo video = video("a/slow.mp4", "alpha");
        add(video);
        waitState(video.id, Download.STATE_DOWNLOADING);
        main(() -> cache.pause(video.id));
        Download paused = waitState(video.id, Download.STATE_STOPPED);
        assertEquals(1, paused.stopReason);
        Download persisted = new DefaultDownloadIndex(new StandaloneDatabaseProvider(context), "offline").getDownload(video.id);
        assertNotNull(persisted);
        assertEquals(1, persisted.stopReason);
        assertEquals(video.headers, OfflineVideo.decode(persisted.request.data).headers);
        main(() -> cache.resumeService());
        Thread.sleep(500);
        assertEquals("Service restart must preserve explicit pause", 1, cache.find(video.id).stopReason);
        main(() -> cache.continueDownload(paused));
        waitState(video.id, Download.STATE_COMPLETED);
    }

    @Test
    public void deletingOneTaskCannotRemoveAnotherTasksIdenticalMediaUrl() throws Exception {
        OfflineVideo first = video("shared/sample.mp4", "alpha");
        OfflineVideo second = video("shared/sample.mp4", "beta");
        assertEquals(first.url, second.url);
        add(first);
        add(second);
        waitState(first.id, Download.STATE_COMPLETED);
        Download retained = waitState(second.id, Download.STATE_COMPLETED);
        main(() -> cache.remove(first.id));
        long end = System.currentTimeMillis() + 10000;
        while (cache.find(first.id) != null && System.currentTimeMillis() < end) Thread.sleep(100);
        assertNull(cache.find(first.id));
        playOffline(retained, true);
    }

    @Test
    public void interruptedDownloadCanFailAndRetryWithoutLosingItsTask() throws Exception {
        OfflineVideo video = video("a/slow.mp4", "alpha");
        add(video);
        waitState(video.id, Download.STATE_DOWNLOADING);
        server.blocked = true;
        Download failed = waitState(video.id, Download.STATE_FAILED);
        assertEquals(video.id, OfflineVideo.decode(failed.request.data).id);
        server.blocked = false;
        main(() -> cache.continueDownload(failed));
        waitState(video.id, Download.STATE_COMPLETED);
    }

    @Test
    public void liveHlsIsRejectedBeforeCreatingADownloadTask() throws Exception {
        OfflineVideo video = video("a/hls/live.m3u8", "alpha");
        CountDownLatch prepared = new CountDownLatch(1);
        AtomicInteger message = new AtomicInteger();
        main(() -> cache.add(video, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, result -> {
            if (result != R.string.offline_preparing) { message.set(result); prepared.countDown(); }
        }));
        assertTrue(prepared.await(50, TimeUnit.SECONDS));
        assertEquals(R.string.offline_unsupported, message.get());
        assertNull(cache.find(video.id));
    }

    @Test
    public void extensionlessHlsIsDetectedAndDownloadsSegments() throws Exception {
        OfflineVideo video = video("a/hls/getM3u8", "alpha");
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        assertEquals("application/x-mpegURL", download.request.mimeType);
        server.blocked = true;
        playOffline(download, true);
    }

    @Test
    public void largeFrontMp4HeaderIsCacheableAndPlaysOffline() throws Exception {
        OfflineVideo video = video("a/large-header.mp4", "alpha");
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        server.blocked = true;
        playOffline(download, true);
    }

    @Test
    public void oversizedHeaderStopsProbeWithoutClaimingTheVideoExpired() throws Exception {
        OfflineVideo video = video("a/oversized-header.mp4", "alpha");
        CountDownLatch prepared = new CountDownLatch(1);
        AtomicInteger message = new AtomicInteger();
        main(() -> cache.add(video, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, result -> {
            if (result != R.string.offline_preparing) { message.set(result); prepared.countDown(); }
        }));
        assertTrue(prepared.await(50, TimeUnit.SECONDS));
        assertEquals(R.string.offline_prepare_error, message.get());
        assertNull(cache.find(video.id));
    }

    @Test
    public void expiredHtmlWithHttp200IsRejectedBeforeAddingTask() throws Exception {
        OfflineVideo video = video("a/expired.m3u8", "alpha");
        CountDownLatch prepared = new CountDownLatch(1);
        AtomicInteger message = new AtomicInteger();
        main(() -> cache.add(video, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, result -> {
            if (result != R.string.offline_preparing) { message.set(result); prepared.countDown(); }
        }));
        assertTrue(prepared.await(50, TimeUnit.SECONDS));
        assertEquals(R.string.offline_invalid_content, message.get());
        assertNull(cache.find(video.id));
    }

    @Test
    public void unplayedEpisodeResolvesAndKeepsOriginalHistoryIdentity() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        main(() -> OfflineIntegration.cacheEpisode(screen, history));
        Download download = waitState(independentId(history), Download.STATE_COMPLETED);
        OfflineVideo video = OfflineVideo.decode(download.request.data);
        assertEquals(history.getEpisodeUrl(), com.fongmi.android.tv.bean.History.objectFrom(video.history).getEpisodeUrl());
        assertEquals(history.getKey(), com.fongmi.android.tv.bean.History.objectFrom(video.history).getKey());
        assertFalse(video.source.isEmpty());
        assertEquals("application/x-mpegURL", download.request.mimeType);
        server.blocked = true;
        playOffline(download, true);
    }

    @Test
    public void bufferingPlayerCanRequestCacheWithoutWaitingForReady() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        main(() -> {
            com.fongmi.android.tv.player.PlayerManager.Callback callback =
                    (com.fongmi.android.tv.player.PlayerManager.Callback) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class[]{com.fongmi.android.tv.player.PlayerManager.Callback.class},
                            (proxy, method, args) -> null);
            Player buffering = (Player) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{Player.class}, (proxy, method, args) -> method.getName().equals("getPlaybackState") ? Player.STATE_BUFFERING : null);
            com.fongmi.android.tv.player.PlayerManager players = new com.fongmi.android.tv.player.PlayerManager(callback) {
                @Override public Player getPlayer() { return buffering; }
            };
            try { OfflineIntegration.cacheCurrent(screen, players, history); }
            finally { players.release(); }
        });
        waitState(independentId(history), Download.STATE_COMPLETED);
    }

    @Test
    public void episodeSheetCacheActionDoesNotSelectOrDismissEpisode() throws Exception {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.startsWith("mobile"));
        AtomicInteger clicks = new AtomicInteger();
        com.fongmi.android.tv.bean.Episode episode = com.fongmi.android.tv.bean.Episode.create("第2集", "https://example.com/episode2");
        Object[] dialog = new Object[1];
        main(() -> {
            try {
                Class<?> type = Class.forName("com.fongmi.android.tv.ui.dialog.EpisodeListDialog");
                dialog[0] = type.getMethod("create").invoke(null);
                type.getMethod("episodes", List.class).invoke(dialog[0], java.util.Collections.singletonList(episode));
                java.util.function.Consumer<com.fongmi.android.tv.bean.Episode> action = item -> {
                    assertEquals(episode, item);
                    clicks.incrementAndGet();
                };
                type.getMethod("cache", java.util.function.Consumer.class).invoke(dialog[0], action);
                type.getMethod("show", androidx.fragment.app.FragmentActivity.class).invoke(dialog[0], screen);
                ((androidx.fragment.app.FragmentActivity) screen).getSupportFragmentManager().executePendingTransactions();
            } catch (Exception error) { throw new AssertionError(error); }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> {
            androidx.fragment.app.DialogFragment sheet = (androidx.fragment.app.DialogFragment) dialog[0];
            int id = context.getResources().getIdentifier("episode_cache", "id", context.getPackageName());
            android.view.View button = sheet.requireDialog().findViewById(id);
            assertNotNull("Each episode needs a cache action", button);
            assertTrue(button.performClick());
            assertEquals(1, clicks.get());
            assertFalse(episode.isSelected());
            assertTrue(sheet.requireDialog().isShowing());
            sheet.dismissNow();
        });
    }

    private com.fongmi.android.tv.bean.History independentHistory() {
        com.fongmi.android.tv.bean.History history = new com.fongmi.android.tv.bean.History();
        history.setKey("push_agent@@@" + UUID.randomUUID() + "@@@0");
        history.setCid(0);
        history.setVodName("未播放缓存测试");
        history.setVodFlag("直连");
        history.setVodRemarks("第2集");
        history.setEpisodeUrl(server.url("comments/hls/getM3u8"));
        history.setPosition(0);
        history.setDuration(C.TIME_UNSET);
        ids.add(independentId(history));
        return history;
    }

    private String independentId(com.fongmi.android.tv.bean.History history) {
        String identity = OfflineVideo.identity(history);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Test
    public void refreshedEpisodeUrlStillFindsCacheAndCannotCreateAnotherDownload() throws Exception {
        OfflineVideo video = transferable("a/sample.mp4");
        add(video);
        Download completed = waitState(video.id, Download.STATE_COMPLETED);
        com.fongmi.android.tv.bean.History refreshed = OfflineHistory.original(video).copy();
        refreshed.setEpisodeUrl("https://episode.test/refreshed?token=new&time=999999");
        assertNotNull("Same source, show, line and exact episode name must match after URL refresh", cache.completedFor(refreshed));
        assertEquals(completed.request.id, cache.completedFor(refreshed).request.id);
        AtomicInteger resolutions = new AtomicInteger(), message = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        main(() -> cache.requestEpisode(refreshed, ready -> {
            resolutions.incrementAndGet(); ready.complete(R.string.offline_unsupported);
        }, result -> { if (result != R.string.offline_preparing) { message.set(result); done.countDown(); } }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("Do not resolve a new signed URL for an existing cache", 0, resolutions.get());
        assertEquals(R.string.offline_already_cached, message.get());
        OfflineVideo duplicate = new OfflineVideo(OfflineVideo.identity(refreshed), video.title, video.episode, video.line,
                server.url("a/slow.mp4"), video.mimeType, video.headers, refreshed.toString(), video.danmaku, video.source);
        ids.add(duplicate.id);
        int requests = server.requests.get();
        main(() -> cache.add(duplicate, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, message::set));
        assertEquals(R.string.offline_already_cached, message.get());
        assertEquals("Duplicate cache must not contact the source", requests, server.requests.get());
        assertEquals("Refreshed URLs must keep the same new task identity", video.id, duplicate.id);
        assertEquals("A duplicate request must leave the completed media unchanged", completed.request.uri, cache.find(video.id).request.uri);
    }

    @Test public void refreshedEpisodeUrlSharesThePreparationReservation() throws Exception {
        com.fongmi.android.tv.bean.History first = independentHistory();
        com.fongmi.android.tv.bean.History refreshed = first.copy();
        refreshed.setEpisodeUrl("https://episode.test/changed?token=2");
        AtomicInteger resolutions = new AtomicInteger(), message = new AtomicInteger();
        CountDownLatch reserved = new CountDownLatch(1);
        OfflineCache.Callback[] finish = new OfflineCache.Callback[1];
        try {
            main(() -> cache.requestEpisode(first, ready -> {
                resolutions.incrementAndGet(); finish[0] = ready; reserved.countDown();
            }, result -> {}));
            assertTrue(reserved.await(10, TimeUnit.SECONDS));
            main(() -> cache.requestEpisode(refreshed, ready -> {
                resolutions.incrementAndGet(); ready.complete(R.string.offline_unsupported);
            }, message::set));
            assertEquals(R.string.offline_preparing, message.get());
            assertEquals("Signed URL changes must not start simultaneous preparation", 1, resolutions.get());
        } finally { main(() -> { if (finish[0] != null) finish[0].complete(R.string.offline_unsupported); }); }
    }

    @Test public void unnamedEpisodesStillRequireExactOriginalUrls() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        history.setVodRemarks("");
        OfflineVideo video = new OfflineVideo(OfflineVideo.identity(history), history.getVodName(), "", history.getVodFlag(),
                server.url("a/sample.mp4"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        ids.add(video.id); add(video); waitState(video.id, Download.STATE_COMPLETED);
        assertNotNull(cache.completedFor(history));
        com.fongmi.android.tv.bean.History other = history.copy();
        other.setEpisodeUrl("https://episode.test/another-unnamed");
        assertNull("Do not collapse all unnamed episodes into one task", cache.completedFor(other));
    }

    @Test
    public void cachePreferenceMatchesExactIdentityAndRejectsMissingFiles() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        OfflineVideo video = new OfflineVideo(UUID.randomUUID().toString(), history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), server.url("a/hls/master.m3u8"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        ids.add(video.id);
        add(video);
        Download completed = waitState(video.id, Download.STATE_COMPLETED);
        assertEquals(completed.request.id, cache.completedFor(history).request.id);
        com.fongmi.android.tv.bean.History other = history.copy();
        other.setVodFlag("another line");
        assertNull(cache.completedFor(other));
        other = history.copy();
        other.setVodRemarks("第3集");
        assertNull(cache.completedFor(other));
        other = history.copy();
        other.setVodRemarks("第02集");
        assertNull("Episode names are exact, not number-based", cache.completedFor(other));
        other = history.copy();
        other.setKey("another@@@show@@@0");
        assertNull(cache.completedFor(other));
        other = history.copy();
        other.setKey("push_agent@@@another-show@@@0");
        assertNull("Same source and title cannot substitute another show ID", cache.completedFor(other));
        other = history.copy();
        other.cid(1);
        assertNull("Same source and show in another configuration cannot match", cache.completedFor(other));
        java.lang.reflect.Field field = OfflineCache.class.getDeclaredField("cache");
        field.setAccessible(true);
        androidx.media3.datasource.cache.SimpleCache storage = (androidx.media3.datasource.cache.SimpleCache) field.get(cache);
        for (String key : storage.getKeys()) {
            if (key.startsWith(video.id + ":")) {
                androidx.media3.datasource.cache.CacheSpan span = storage.getCachedSpans(key).first();
                assertTrue(span.file.delete());
                break;
            }
        }
        assertNull("A completed index record alone is insufficient", cache.completedFor(history));
    }

    @Test
    public void incompleteEpisodeIsNotChosenAsAnOnlineReplacement() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        OfflineVideo video = new OfflineVideo(UUID.randomUUID().toString(), history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), server.url("a/slow.mp4"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        ids.add(video.id);
        add(video);
        waitState(video.id, Download.STATE_DOWNLOADING);
        main(() -> cache.pause(video.id));
        waitState(video.id, Download.STATE_STOPPED);
        assertNull(cache.completedFor(history));
    }

    @Test
    public void onlinePageCacheSelectionResumesWithoutAnyMediaNetworkRequest() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        OfflineVideo video = new OfflineVideo(UUID.randomUUID().toString(), history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), server.url("a/hls/master.m3u8"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        ids.add(video.id);
        add(video);
        waitState(video.id, Download.STATE_COMPLETED);
        history.setEpisodeUrl("https://catalog.example/refreshed/episode?token=2");
        int requests = server.requests.get();
        server.blocked = true;
        com.fongmi.android.tv.player.PlayerManager[] players = new com.fongmi.android.tv.player.PlayerManager[1];
        CachedVodPlayback[] cached = new CachedVodPlayback[1];
        AtomicInteger result = new AtomicInteger();
        try {
            main(() -> {
                com.fongmi.android.tv.player.PlayerManager.Callback callback =
                        (com.fongmi.android.tv.player.PlayerManager.Callback) java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(), new Class[]{com.fongmi.android.tv.player.PlayerManager.Callback.class},
                                (proxy, method, args) -> null);
                players[0] = new com.fongmi.android.tv.player.PlayerManager(callback);
                cached[0] = new CachedVodPlayback((androidx.fragment.app.FragmentActivity) screen);
                cached[0].start(history, players[0], 1300, androidx.media3.common.MediaMetadata.EMPTY,
                        history.getKey(), () -> true, found -> result.set(found ? 1 : -1));
            });
            long end = System.currentTimeMillis() + 20000;
            AtomicInteger state = new AtomicInteger();
            do {
                main(() -> state.set(players[0].getPlayer().getPlaybackState()));
                if (state.get() == Player.STATE_READY) break;
                Thread.sleep(100);
            } while (System.currentTimeMillis() < end);
            assertEquals(1, result.get());
            assertEquals(Player.STATE_READY, state.get());
            main(() -> {
                assertTrue(players[0].isOffline());
                assertEquals(history.getKey(), players[0].getPlayer().getCurrentMediaItem().mediaId);
                assertTrue(players[0].getPlayer().getCurrentPosition() >= 1200);
                com.fongmi.android.tv.bean.Result source = com.fongmi.android.tv.bean.Result.objectFrom(players[0].getSourceResult());
                assertEquals(history.getEpisodeUrl(), source.getUrl().v());
                assertEquals(history.getVodFlag(), source.getFlag());
            });
            assertEquals(requests, server.requests.get());
            assertEquals("push_agent", history.getKey().split("@@@", -1)[0]);
        } finally {
            main(() -> {
                if (cached[0] != null) cached[0].close();
                if (players[0] != null) players[0].release();
            });
        }
    }

    @Test public void cachedOnlineEpisodeInitializesSpiderCloudEntryWithoutStartingItsVideo() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        OfflineVideo video = new OfflineVideo(UUID.randomUUID().toString(), history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), server.url("a/sample.mp4"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        ids.add(video.id); add(video); waitState(video.id, Download.STATE_COMPLETED);
        com.fongmi.android.tv.api.config.VodConfig config = com.fongmi.android.tv.api.config.VodConfig.get();
        java.lang.reflect.Field sites = config.getClass().getDeclaredField("sites"), parses = config.getClass().getDeclaredField("parses");
        sites.setAccessible(true); parses.setAccessible(true);
        Object oldSites = sites.get(config), oldParses = parses.get(config);
        CountDownLatch metadata = new CountDownLatch(1);
        com.github.catvod.crawler.Spider spider = new com.github.catvod.crawler.Spider() {
            @Override public String playerContent(String flag, String id, List<String> flags) {
                assertEquals(history.getVodFlag(), flag);
                assertEquals(history.getEpisodeUrl(), id);
                com.fongmi.android.tv.bean.Parse cloud = com.fongmi.android.tv.bean.Parse.get(1, server.url("cloud?url="));
                cloud.setName("弹幕云搜");
                try { parses.set(config, new ArrayList<>(List.of(cloud))); } catch (Exception error) { throw new AssertionError(error); }
                metadata.countDown();
                // This media is deliberately unavailable. The cached player must never try to open it.
                return "{\"parse\":0,\"url\":\"" + server.url("must-not-play.mp4") + "\"}";
            }
        };
        com.fongmi.android.tv.bean.Site site = new com.fongmi.android.tv.bean.Site() {
            @Override public String getKey() { return history.getSiteKey(); }
            @Override public Integer getType() { return 3; }
            @Override public com.github.catvod.crawler.Spider spider() { return spider; }
            @Override public com.fongmi.android.tv.bean.Site recent() { return this; }
        };
        sites.set(config, new ArrayList<>(List.of(site))); parses.set(config, new ArrayList<>());
        com.fongmi.android.tv.player.PlayerManager[] players = new com.fongmi.android.tv.player.PlayerManager[1];
        CachedVodPlayback[] cached = new CachedVodPlayback[1];
        int requests = server.requests.get(); server.blocked = true;
        try {
            main(() -> {
                com.fongmi.android.tv.player.PlayerManager.Callback callback =
                        (com.fongmi.android.tv.player.PlayerManager.Callback) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class[]{com.fongmi.android.tv.player.PlayerManager.Callback.class}, (proxy, method, args) -> null);
                players[0] = new com.fongmi.android.tv.player.PlayerManager(callback);
                cached[0] = new CachedVodPlayback((androidx.fragment.app.FragmentActivity) screen);
                cached[0].start(history, players[0], 0, androidx.media3.common.MediaMetadata.EMPTY, history.getKey(), () -> true,
                        found -> assertTrue("Completed cache must remain the media source", found));
            });
            assertTrue("Cache-first entry must still run the spider's dynamic entry initialization", metadata.await(10, TimeUnit.SECONDS));
            assertTrue(config.getParses().stream().anyMatch(item -> item.getName().equals("弹幕云搜")));
            AtomicInteger state = new AtomicInteger();
            long end = System.currentTimeMillis() + 10000;
            do { main(() -> state.set(players[0].getPlayer().getPlaybackState())); if (state.get() == Player.STATE_READY) break; Thread.sleep(100); }
            while (System.currentTimeMillis() < end);
            assertEquals(Player.STATE_READY, state.get());
            main(() -> assertTrue(players[0].isOffline()));
            assertEquals("Metadata initialization cannot fetch the returned video", requests, server.requests.get());
        } finally {
            main(() -> { if (cached[0] != null) cached[0].close(); if (players[0] != null) players[0].release(); });
            sites.set(config, oldSites); parses.set(config, oldParses);
        }
    }

    @Test
    public void differentQualityIdentitiesCannotDownloadTheSameCompletedEpisodeAgain() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        OfflineVideo original = new OfflineVideo("quality-1080-" + UUID.randomUUID(), history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), server.url("a/sample.mp4"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        ids.add(original.id);
        add(original);
        waitState(original.id, Download.STATE_COMPLETED);
        int requests = server.requests.get();
        server.blocked = true;
        OfflineVideo duplicate = new OfflineVideo("default-" + UUID.randomUUID(), history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), server.url("a/expired.m3u8"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        ids.add(duplicate.id);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger message = new AtomicInteger();
        main(() -> cache.add(duplicate, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, result -> {
            if (result != R.string.offline_preparing) { message.set(result); done.countDown(); }
        }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(R.string.offline_already_cached, message.get());
        assertEquals(requests, server.requests.get());
        assertNull(cache.find(duplicate.id));
        CountDownLatch checked = new CountDownLatch(1);
        AtomicInteger resolved = new AtomicInteger();
        main(() -> cache.requestEpisode(history, ready -> resolved.incrementAndGet(), result -> {
            if (result != R.string.offline_preparing) { message.set(result); checked.countDown(); }
        }));
        assertTrue(checked.await(5, TimeUnit.SECONDS));
        assertEquals(R.string.offline_already_cached, message.get());
        assertEquals("Existing cache must be found before resolving a source", 0, resolved.get());
        assertEquals(requests, server.requests.get());
    }

    @Test
    public void rapidMixedQualityRequestsShareOnePreparingTask() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        OfflineVideo first = new OfflineVideo(UUID.randomUUID().toString(), history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), server.url("a/slow.mp4"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        OfflineVideo duplicate = new OfflineVideo(UUID.randomUUID().toString(), history.getVodName(), history.getVodRemarks(),
                history.getVodFlag(), server.url("a/slow.mp4"), null, Map.of("Cookie", "alpha"), history.toString(), "[]");
        ids.add(first.id);
        ids.add(duplicate.id);
        CountDownLatch added = new CountDownLatch(1);
        AtomicInteger second = new AtomicInteger(), firstMessage = new AtomicInteger();
        main(() -> {
            cache.add(first, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, message -> {
                if (message != R.string.offline_preparing) { firstMessage.set(message); added.countDown(); }
            });
            cache.add(duplicate, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, second::set);
        });
        assertEquals(R.string.offline_exists, second.get());
        assertTrue(added.await(15, TimeUnit.SECONDS));
        assertEquals(R.string.offline_added, firstMessage.get());
        waitState(first.id, Download.STATE_DOWNLOADING);
        main(() -> cache.pause(first.id));
        waitState(first.id, Download.STATE_STOPPED);
        int requests = server.requests.get();
        main(() -> cache.add(duplicate, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, second::set));
        assertEquals(R.string.offline_exists, second.get());
        assertEquals(requests, server.requests.get());
        assertNull(cache.find(duplicate.id));
        server.blocked = true;
        main(() -> {
            try { cache.continueDownload(cache.find(first.id)); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        waitState(first.id, Download.STATE_FAILED);
        requests = server.requests.get();
        main(() -> cache.add(duplicate, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, second::set));
        assertEquals(R.string.offline_exists, second.get());
        assertEquals(requests, server.requests.get());
        assertNull(cache.find(duplicate.id));
    }

    @Test public void eachEpisodeRequestNotifiesPreparingOnceAcrossAllStages() throws Exception {
        List<OfflineVideo> videos = List.of(transferable("a/sample.mp4"), transferable("a/sample.mp4"));
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger[] preparing = {new AtomicInteger(), new AtomicInteger()};
        AtomicInteger[] outcomes = {new AtomicInteger(), new AtomicInteger()};
        main(() -> {
            for (int i = 0; i < videos.size(); i++) {
                int index = i; OfflineVideo video = videos.get(i);
                cache.requestEpisode(OfflineHistory.original(video), ready -> {
                    // The resolver reports progress before the download probe reports progress.
                    ready.complete(R.string.offline_preparing);
                    cache.add(video, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, ready);
                }, message -> {
                    if (message == R.string.offline_preparing) preparing[index].incrementAndGet();
                    else { outcomes[index].set(message); finished.countDown(); }
                });
            }
        });
        assertTrue(finished.await(15, TimeUnit.SECONDS));
        for (int i = 0; i < videos.size(); i++) {
            assertEquals("One preparing message per click, independent of other episodes", 1, preparing[i].get());
            assertEquals(R.string.offline_added, outcomes[i].get());
            waitState(videos.get(i).id, Download.STATE_COMPLETED);
        }
    }

    @Test
    public void sourceAdmissionIsSharedAndReleasedAfterPreparationFailure() throws Exception {
        com.fongmi.android.tv.bean.History history = independentHistory();
        java.util.concurrent.atomic.AtomicReference<OfflineCache.Callback> owner = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger(), duplicateMessage = new AtomicInteger();
        main(() -> {
            cache.requestEpisode(history, ready -> { admitted.incrementAndGet(); owner.set(ready); first.countDown(); }, message -> {});
            cache.requestEpisode(history, ready -> admitted.incrementAndGet(), duplicateMessage::set);
        });
        assertTrue(first.await(5, TimeUnit.SECONDS));
        assertEquals(1, admitted.get());
        assertEquals(R.string.offline_preparing, duplicateMessage.get());
        CountDownLatch retried = new CountDownLatch(1);
        main(() -> {
            owner.get().complete(R.string.offline_prepare_error);
            cache.requestEpisode(history, ready -> {
                admitted.incrementAndGet();
                ready.complete(R.string.offline_unsupported);
                retried.countDown();
            }, message -> {});
        });
        assertTrue(retried.await(5, TimeUnit.SECONDS));
        assertEquals(2, admitted.get());
    }

    private static final class FixtureServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final ExecutorService workers = Executors.newCachedThreadPool();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final Context assets;
        volatile boolean blocked;
        volatile boolean allowComments;
        volatile String lastCloudInput;
        volatile boolean closed;

        FixtureServer(Context assets) throws Exception {
            this.assets = assets;
            workers.execute(() -> {
                while (!closed) {
                    try { Socket socket = server.accept(); workers.execute(() -> serve(socket)); }
                    catch (Exception e) { if (!closed) throw new RuntimeException(e); }
                }
            });
        }

        String url(String path) { return "http://127.0.0.1:" + server.getLocalPort() + "/" + path; }

        void serve(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(10000);
                BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                String request = input.readLine();
                if (request == null) return;
                String path = request.split(" ")[1];
                String cookie = "", range = "";
                for (String line; (line = input.readLine()) != null && !line.isEmpty();) {
                    if (line.toLowerCase().startsWith("cookie:")) cookie = line.substring(7).trim();
                    if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
                }
                OutputStream output = connection.getOutputStream();
                if (path.startsWith("/repair-api?")) {
                    byte[] body = ("{\"list\":[{\"vod_id\":\"refresh-show\",\"vod_name\":\"Repair\",\"vod_play_from\":\"测试线路\",\"vod_play_url\":\"第1集$" + url("comments/other.mp4") + "#第2集$" + url("comments/sample.mp4") + "\"}]}").getBytes(StandardCharsets.UTF_8);
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(body); return;
                }
                if (path.startsWith("/cloud?url=")) {
                    lastCloudInput = path.substring("/cloud?url=".length());
                    byte[] body = ("{\"danmaku\":[{\"name\":\"云搜结果\",\"url\":\"" + url("comments/comments.xml") + "\"}]}" )
                            .getBytes(StandardCharsets.UTF_8);
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(body);
                    return;
                }
                if (!path.startsWith("/comments/")) requests.incrementAndGet();
                if ((blocked && !(allowComments && path.startsWith("/comments/"))) || !(path.startsWith("/comments/") || path.startsWith("/a/") && cookie.equals("alpha") || path.startsWith("/b/") && cookie.equals("beta")
                        || path.startsWith("/shared/") && (cookie.equals("alpha") || cookie.equals("beta")))) {
                    rejected.incrementAndGet();
                    output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                if (path.equals("/a/expired.m3u8")) {
                    byte[] body = "<pre>链接失效了，请重新获取</pre>".getBytes(StandardCharsets.UTF_8);
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(body);
                    return;
                }
                String file = path.substring(path.indexOf('/', 1) + 1);
                if (file.equals("hls/getM3u8")) file = "hls/master.m3u8";
                boolean slowSubtitle = file.equals("slow-subtitle.vtt");
                boolean slow = file.equals("slow.mp4") || slowSubtitle, capped = file.equals("capped.mp4") || file.equals("badrange.mp4");
                boolean largeHeader = file.equals("large-header.mp4") || file.equals("oversized-header.mp4");
                boolean numbered = file.matches("episode-[0-9]+\\.mp4");
                byte[] data;
                try (InputStream asset = assets.getAssets().open("offline/" + (slowSubtitle ? "hls/en.vtt" : slow || capped || numbered || largeHeader ? "sample.mp4" : file))) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    for (int count; (count = asset.read(chunk)) >= 0;) buffer.write(chunk, 0, count);
                    if (numbered) {
                        buffer.write(java.nio.ByteBuffer.allocate(12).putInt(12).putInt(0x66726565).putInt(Integer.parseInt(file.substring(8, file.length() - 4))).array());
                    }
                    if (slowSubtitle) {
                        byte[] padding = new byte[2097152]; java.util.Arrays.fill(padding, (byte) ' '); buffer.write(padding);
                    }
                    if (slow && !slowSubtitle) {
                        // A valid ISO BMFF free box makes the download long enough to pause.
                        buffer.write(new byte[]{0, 32, 0, 0, 'f', 'r', 'e', 'e'});
                        buffer.write(new byte[2097152 - 8]);
                    }
                    data = buffer.toByteArray();
                    if (largeHeader) data = enlargeFrontMoov(data, file.equals("oversized-header.mp4") ? 16777216 : 2621440);
                }
                int offset = range.isEmpty() ? 0 : Integer.parseInt(range.substring(6).split("-")[0]);
                if (file.equals("badrange.mp4")) offset = 0;
                int end = data.length - 1;
                if (!range.isEmpty() && !range.endsWith("-")) end = Math.min(end, Integer.parseInt(range.split("-")[1]));
                if (capped) end = Math.min(end, offset + data.length / 2 - 1);
                int length = end - offset + 1;
                String mime = file.endsWith("m3u8") ? "application/vnd.apple.mpegurl" : file.endsWith("mpd") ? "application/dash+xml" : "application/octet-stream";
                String headers = "HTTP/1.1 " + (range.isEmpty() && !capped ? "200 OK" : "206 Partial Content") + "\r\nContent-Type: " + mime
                        + "\r\nContent-Length: " + length + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n";
                if (!range.isEmpty() || capped) headers += "Content-Range: bytes " + offset + "-" + end + "/" + data.length + "\r\n";
                output.write((headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
                for (int at = offset; at <= end; at += 8192) {
                    if (blocked && !(allowComments && path.startsWith("/comments/"))) return;
                    output.write(data, at, Math.min(8192, end - at + 1));
                    output.flush();
                    if (slow) Thread.sleep(25);
                }
            } catch (Exception ignored) { /* Player cancellation closes sockets. */ }
        }

        private static byte[] enlargeFrontMoov(byte[] original, int padding) {
            // Insert a valid free box as the first moov child, and move chunk offsets.
            java.nio.ByteBuffer source = java.nio.ByteBuffer.wrap(original);
            int moov = source.getInt(0); // This fixture starts with ftyp, then moov.
            int moovSize = source.getInt(moov);
            byte[] expanded = new byte[original.length + padding];
            System.arraycopy(original, 0, expanded, 0, moov + 8);
            System.arraycopy(original, moov + 8, expanded, moov + 8 + padding, original.length - moov - 8);
            java.nio.ByteBuffer target = java.nio.ByteBuffer.wrap(expanded);
            target.putInt(moov, moovSize + padding);
            target.putInt(moov + 8, padding);
            target.putInt(moov + 12, 0x66726565); // free
            moveChunkOffsets(target, moov + 8 + padding, moov + moovSize + padding, padding);
            return expanded;
        }

        private static void moveChunkOffsets(java.nio.ByteBuffer data, int start, int end, int delta) {
            for (int at = start; at < end;) {
                int size = data.getInt(at), type = data.getInt(at + 4);
                if (size < 8 || at + size > end) throw new IllegalArgumentException("Invalid fixture box");
                if (type == 0x7374636f) { // stco
                    int count = data.getInt(at + 12);
                    for (int i = 0; i < count; i++) {
                        int offset = at + 16 + i * 4;
                        data.putInt(offset, data.getInt(offset) + delta);
                    }
                } else if (type == 0x7472616b || type == 0x6d646961 || type == 0x6d696e66 || type == 0x7374626c) {
                    moveChunkOffsets(data, at + 8, at + size, delta);
                }
                at += size;
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            server.close();
            workers.shutdownNow();
        }
    }
}

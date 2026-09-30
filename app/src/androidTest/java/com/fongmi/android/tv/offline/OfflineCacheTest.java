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

    private void main(Runnable work) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(work);
    }

    @Before
    public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
                player[0] = ((PlayerView) screen.findViewById(R.id.exo)).getPlayer();
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
            main(() -> playing[0] = ((PlayerView) screen.findViewById(R.id.exo)).getPlayer());
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
                player[0] = ((PlayerView) screen.findViewById(R.id.exo)).getPlayer();
                ready[0] = player[0] != null && player[0].getPlaybackState() == Player.STATE_READY;
            });
            if (!ready[0]) Thread.sleep(100);
        } while (!ready[0] && System.currentTimeMillis() < end);
        assertTrue("The regular playback page must prepare cached video", ready[0]);
        return player[0];
    }

    @Test
    public void sharedControlsKeepCacheOnlyPlaybackAcrossDecodeAndRotation() throws Exception {
        OfflineVideo video = video("a/sample.mp4", "alpha");
        add(video);
        Download download = waitState(video.id, Download.STATE_COMPLETED);
        server.blocked = true;
        int requests = server.requests.get();
        Player player = launchSharedPlayer(download);
        main(() -> {
            assertTrue(com.fongmi.android.tv.server.Server.get().getPlayer().isOffline());
            com.fongmi.android.tv.player.Players shared = com.fongmi.android.tv.server.Server.get().getPlayer();
            com.fongmi.android.tv.bean.Danmaku comment = com.fongmi.android.tv.bean.Danmaku.from(new java.io.File(context.getFilesDir(), "disabled-comments.xml").getAbsolutePath());
            shared.setDanmaku(comment);
            shared.setDanmaku(com.fongmi.android.tv.bean.Danmaku.empty());
            player.pause();
            player.seekTo(500);
            screen.findViewById(R.id.control).findViewById(R.id.speed).performClick();
            screen.findViewById(R.id.control).findViewById(R.id.speed).performClick();
            screen.findViewById(R.id.control).findViewById(R.id.decode).performClick();
        });
        Player[] rebuilt = new Player[1];
        boolean[] ready = {false};
        long end = System.currentTimeMillis() + 10000;
        do {
            main(() -> {
                rebuilt[0] = ((PlayerView) screen.findViewById(R.id.exo)).getPlayer();
                ready[0] = rebuilt[0] != null && rebuilt[0].getPlaybackState() == Player.STATE_READY;
            });
            if (!ready[0]) Thread.sleep(100);
        } while (!ready[0] && System.currentTimeMillis() < end);
        assertTrue(ready[0]);
        main(() -> {
            assertNotSame("Decode must recreate the shared player", player, rebuilt[0]);
            assertEquals(1.5f, rebuilt[0].getPlaybackParameters().speed, 0.01f);
            assertFalse("Decode must retain the pause state", rebuilt[0].getPlayWhenReady());
            assertFalse("Decode must keep a disabled danmaku item disabled",
                    com.fongmi.android.tv.server.Server.get().getPlayer().getDanmakus().get(0).isSelected());
            rebuilt[0].seekTo(500);
            screen.findViewById(R.id.control).findViewById(R.id.opening).performClick();
            rebuilt[0].seekTo(3000);
            screen.findViewById(R.id.control).findViewById(R.id.ending).performClick();
            screen.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> {
            assertSame("Rotation must retain the normal player", rebuilt[0], ((PlayerView) screen.findViewById(R.id.exo)).getPlayer());
            com.fongmi.android.tv.ui.activity.VideoActivity page = (com.fongmi.android.tv.ui.activity.VideoActivity) screen;
            page.onTimeChanged();
            screen.finish();
        });
        assertEquals("Video playback and decode must never request the source", requests, server.requests.get());
        String saved = context.getSharedPreferences("offline_playback", 0).getAll().values().stream()
                .filter(value -> value instanceof String && ((String) value).contains("opening"))
                .map(Object::toString).findFirst().orElse("");
        assertFalse("Offline playback settings must be saved", saved.isEmpty());
        com.fongmi.android.tv.bean.History history = com.fongmi.android.tv.bean.History.objectFrom(saved);
        assertTrue("Opening marker must survive", history.getOpening() > 0);
        assertTrue("Ending marker must survive", history.getEnding() > 0);
        assertEquals(1.5f, history.getSpeed(), 0.01f);
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
        java.lang.reflect.Field configField = com.fongmi.android.tv.api.config.VodConfig.class.getDeclaredField("config");
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
                if (BuildConfig.FLAVOR.startsWith("mobile")) screen.findViewById(context.getResources().getIdentifier("full", "id", context.getPackageName())).performClick();
                else screen.findViewById(R.id.video).performClick();
            });
            boolean[] clicked = {false};
            long deadline = System.currentTimeMillis() + 10000;
            do {
                main(() -> {
                    if (!screen.findViewById(R.id.control).isShown())
                        ((com.fongmi.android.tv.ui.activity.VideoActivity) screen).onSingleTap();
                    android.view.View row = screen.findViewById(R.id.control).findViewById(R.id.parse);
                    android.widget.TextView button = findText(row, label);
                    if (row.isShown() && button != null) clicked[0] = button.performClick();
                });
                if (!clicked[0]) Thread.sleep(100);
            } while (!clicked[0] && System.currentTimeMillis() < deadline);
            assertTrue("The existing dynamic bottom row must expose cloud search", clicked[0]);
            boolean[] selected = {false};
            deadline = System.currentTimeMillis() + 10000;
            do {
                main(() -> selected[0] = com.fongmi.android.tv.server.Server.get().getPlayer().haveDanmaku());
                if (!selected[0]) Thread.sleep(100);
            } while (!selected[0] && System.currentTimeMillis() < deadline);
            assertTrue("Cloud search must select returned danmaku", selected[0]);
            assertEquals(originalUrl, server.lastCloudInput);
            main(() -> {
                assertSame(player, ((PlayerView) screen.findViewById(R.id.exo)).getPlayer());
                assertFalse(player.getPlayWhenReady());
                assertTrue(com.fongmi.android.tv.server.Server.get().getPlayer().isOffline());
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
        com.fongmi.android.tv.player.danmaku.Loader online = new com.fongmi.android.tv.player.danmaku.Loader(comments);
        assertNotNull(online.getDataSource());
        byte[] expected;
        try (InputStream input = online.getDataSource().data()) { expected = input.readAllBytes(); }
        assertTrue(new String(expected, StandardCharsets.UTF_8).contains("offline comment"));
        server.blocked = true;
        com.fongmi.android.tv.player.danmaku.Loader offline = new com.fongmi.android.tv.player.danmaku.Loader(comments);
        assertNotNull(offline.getDataSource());
        try (InputStream input = offline.getDataSource().data()) { assertArrayEquals(expected, input.readAllBytes()); }
        java.io.File imported = new java.io.File(context.getFilesDir(), "test-comments.xml");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(imported)) { output.write(expected); }
        com.fongmi.android.tv.player.danmaku.Loader local = new com.fongmi.android.tv.player.danmaku.Loader(
                com.fongmi.android.tv.bean.Danmaku.from(imported.getAbsolutePath()));
        assertNotNull(local.getDataSource());
        try (InputStream input = local.getDataSource().data()) { assertArrayEquals(expected, input.readAllBytes()); }
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
            assertEquals(R.string.offline_exists, message);
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
                String file = path.substring(path.indexOf('/', 1) + 1);
                boolean slow = file.equals("slow.mp4");
                byte[] data;
                try (InputStream asset = assets.getAssets().open("offline/" + (slow ? "sample.mp4" : file))) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    for (int count; (count = asset.read(chunk)) >= 0;) buffer.write(chunk, 0, count);
                    if (slow) {
                        // A valid ISO BMFF free box makes the download long enough to pause.
                        buffer.write(new byte[]{0, 32, 0, 0, 'f', 'r', 'e', 'e'});
                        buffer.write(new byte[2097152 - 8]);
                    }
                    data = buffer.toByteArray();
                }
                int offset = range.isEmpty() ? 0 : Integer.parseInt(range.substring(6).split("-")[0]);
                int end = data.length - 1;
                if (!range.isEmpty() && !range.endsWith("-")) end = Math.min(end, Integer.parseInt(range.split("-")[1]));
                int length = end - offset + 1;
                String mime = file.endsWith("m3u8") ? "application/vnd.apple.mpegurl" : file.endsWith("mpd") ? "application/dash+xml" : "application/octet-stream";
                String headers = "HTTP/1.1 " + (range.isEmpty() ? "200 OK" : "206 Partial Content") + "\r\nContent-Type: " + mime
                        + "\r\nContent-Length: " + length + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n";
                if (!range.isEmpty()) headers += "Content-Range: bytes " + offset + "-" + end + "/" + data.length + "\r\n";
                output.write((headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
                for (int at = offset; at <= end; at += 8192) {
                    if (blocked && !(allowComments && path.startsWith("/comments/"))) return;
                    output.write(data, at, Math.min(8192, end - at + 1));
                    output.flush();
                    if (slow) Thread.sleep(25);
                }
            } catch (Exception ignored) { /* Player cancellation closes sockets. */ }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            server.close();
            workers.shutdownNow();
        }
    }
}

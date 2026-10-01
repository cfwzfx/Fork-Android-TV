package com.fongmi.android.tv.offline;

import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OfflineHistoryTest {
    private History original;
    private OfflineVideo video;
    private boolean incognito;

    @Before public void setup() {
        incognito = Setting.isIncognito();
        Setting.putIncognito(false);
        original = new History();
        original.setKey("history-fixture@@@" + UUID.randomUUID() + "@@@731");
        original.setCid(731);
        original.setVodName("历史同步测试");
        original.setVodPic("https://example.test/poster.jpg");
        original.setVodFlag("原始线路");
        original.setVodRemarks("第1集");
        original.setEpisodeUrl("https://example.test/episode/1");
        original.setPosition(1000);
        original.setDuration(10000);
        original.setCreateTime(100);
        original.setOpening(500);
        original.setEnding(600);
        video = new OfflineVideo(UUID.randomUUID().toString(), original.getVodName(), original.getVodRemarks(),
                original.getVodFlag(), "https://example.test/media.mp4", null, Collections.emptyMap(), original.toString(), "[]");
    }

    private void flush() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Task.executeSerial(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @After public void teardown() throws Exception {
        flush();
        AppDatabase.get().getHistoryDao().delete(original.getCid(), original.getKey());
        Setting.putIncognito(incognito);
    }

    private History online() { return AppDatabase.get().getHistoryDao().find(original.getCid(), original.getKey()); }

    private History progress(long time, long position) {
        History history = original.copy();
        history.setKey("offline:" + video.id);
        history.setVodFlag("缓存");
        history.setEpisodeUrl("offline:" + video.id);
        history.setCreateTime(time);
        history.setPosition(position);
        return history;
    }

    @Test public void restoreUsesLatestOnlinePositionInTheOriginalConfiguration() {
        History latest = original.copy();
        latest.setPosition(3200);
        latest.setCreateTime(300);
        latest.save();
        History restored = OfflineHistory.restore(video);
        assertEquals(3200, restored.getPosition());
        assertEquals(731, restored.getCid());
        assertEquals(500, restored.getOpening());
        assertEquals(600, restored.getEnding());
    }

    @Test public void offlineProgressUpdatesOnlineRecordWithoutWritingOfflineUrls() throws Exception {
        original.save();
        History progress = progress(400, 4200);
        progress.setOpening(700);
        progress.setEnding(800);
        progress.setScale(2);
        OfflineHistory.save(video, progress, true);
        flush();
        History saved = online();
        assertEquals(4200, saved.getPosition());
        assertEquals(original.getKey(), saved.getKey());
        assertEquals(original.getEpisodeUrl(), saved.getEpisodeUrl());
        assertEquals(original.getVodFlag(), saved.getVodFlag());
        assertEquals(original.getVodPic(), saved.getVodPic());
        assertEquals(700, saved.getOpening());
        assertEquals(800, saved.getEnding());
        assertEquals(2, saved.getScale());
        assertNull(AppDatabase.get().getHistoryDao().find(731, progress.getKey()));
        History onlineLater = saved.copy();
        onlineLater.setPosition(5600);
        onlineLater.setCreateTime(600);
        onlineLater.save();
        assertEquals(5600, OfflineHistory.restore(video).getPosition());
    }

    @Test public void progressFromAnotherEpisodeOrLineIsNotApplied() {
        History other = original.copy();
        other.setEpisodeUrl("https://example.test/episode/2");
        other.setVodRemarks("第2集");
        other.setPosition(4200);
        other.setCreateTime(400);
        other.save();
        assertEquals(C.TIME_UNSET, OfflineHistory.restore(video).getPosition());
        other.setVodRemarks("第1集");
        other.setVodFlag("另一条线路");
        other.save();
        assertEquals(C.TIME_UNSET, OfflineHistory.restore(video).getPosition());
    }

    @Test public void refreshedEpisodeUrlKeepsLatestProgressForTheSameNamedEpisode() throws Exception {
        History latest = original.copy();
        latest.setEpisodeUrl("https://example.test/refreshed/1?token=new");
        latest.setPosition(4200); latest.setCreateTime(400); latest.save();
        assertEquals("URL changes must not reset progress for the same exact episode", 4200, OfflineHistory.restore(video).getPosition());
        OfflineHistory.save(video, progress(500, 5600), false);
        flush();
        assertEquals("Offline progress must preserve the current online episode URL", latest.getEpisodeUrl(), online().getEpisodeUrl());
        assertEquals(5600, online().getPosition());
    }

    @Test public void staleOfflineSaveCannotOverwriteNewerOnlineProgress() throws Exception {
        History latest = original.copy();
        latest.setPosition(6000);
        latest.setCreateTime(500);
        latest.save();
        OfflineHistory.save(video, progress(400, 4200), false);
        flush();
        assertEquals(6000, online().getPosition());
    }

    @Test public void incognitoAndUnpreparedPlaybackDoNotOverwriteOnlineHistory() throws Exception {
        original.save();
        History empty = progress(400, C.TIME_UNSET);
        OfflineHistory.save(video, empty, true);
        Setting.putIncognito(true);
        OfflineHistory.save(video, progress(400, 4200), true);
        flush();
        assertEquals(1000, online().getPosition());
    }

    @Test public void missingOriginalIdentityDoesNotCreateASeparateHistory() throws Exception {
        OfflineVideo incomplete = new OfflineVideo("incomplete", "视频", "第1集", "线路", video.url, null, Collections.emptyMap());
        History progress = progress(400, 4200);
        assertEquals(C.TIME_UNSET, OfflineHistory.restore(incomplete).getPosition());
        OfflineHistory.save(incomplete, progress, true);
        flush();
        assertNull(online());
    }
}

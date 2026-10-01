package com.fongmi.android.tv.playback.vod;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Site;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CachedVodControllerTest {
    @Test
    public void cachedEpisodeSkipsSourceRequestAndNextUncachedEpisodeUsesOnline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Episode first = Episode.create("1", "https://example.com/1");
            Episode second = Episode.create("2", "https://example.com/2");
            Flag flag = Flag.create("line");
            flag.getEpisodes().addAll(List.of(first, second));
            flag.setSelected(flag);
            History history = new History();
            history.setKey("test@@@cache-first@@@0");
            history.setVodName("Cache first");
            history.setVodFlag("line");
            history.setVodRemarks("1");
            history.setEpisodeUrl(first.getUrl());
            history.setPosition(1600);
            // No persisted progress is needed by this controller-only fixture.
            VodPlaybackState state = new VodPlaybackState();
            state.setHistory(history);
            state.setFlags(Collections.singletonList(flag));
            AtomicInteger online = new AtomicInteger(), cached = new AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean defer = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.ArrayList<Object[]> lookups = new java.util.ArrayList<>();
            VodPlaybackHost host = (VodPlaybackHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{VodPlaybackHost.class}, (proxy, method, args) -> {
                        if (method.getName().equals("tryCachedPlayback")) {
                            if (defer.get()) { lookups.add(args); return null; }
                            History requested = (History) args[0];
                            boolean found = requested.getEpisodeUrl().equals(first.getUrl());
                            if (found) {
                                assertEquals(1600L, ((Number) args[1]).longValue());
                                cached.incrementAndGet();
                            }
                            ((Consumer<Boolean>) args[4]).accept(found);
                            return null;
                        }
                        if (method.getName().equals("getVodKey")) return "test";
                        if (method.getName().equals("getVodId")) return "cache-first";
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return 0L;
                        if (method.getReturnType() == String.class) return "";
                        return null;
                    });
            VodDataSource source = new VodDataSource() {
                public void detailContent(String key, String id) {}
                public void playerContent(VodPlayRequest request) { online.incrementAndGet(); }
                public void preloadContent(VodPlayRequest request) {}
                public void searchContent(List<Site> sites, String keyword, boolean quick) {}
            };
            VodPlaybackController controller = new VodPlaybackController(host, source, state);
            controller.selectEpisode(first);
            assertEquals("A completed cache must bypass the source API", 0, online.get());
            assertEquals(1, cached.get());
            assertEquals(first.getUrl(), state.getPlayingRequest().getId());
            assertEquals("test@@@cache-first@@@0", history.getKey());
            controller.playbackError("Corrupt cache");
            assertEquals("Read failures should retry online once", 1, online.get());
            assertEquals("The failed cache must not be selected again", 1, cached.get());
            controller.selectEpisode(second);
            assertEquals(2, online.get());
            assertEquals(second.getUrl(), state.getPendingRequest().getId());
            defer.set(true);
            controller.selectEpisode(first);
            controller.selectEpisode(second);
            assertFalse(((java.util.function.BooleanSupplier) lookups.get(0)[3]).getAsBoolean());
            assertTrue(((java.util.function.BooleanSupplier) lookups.get(1)[3]).getAsBoolean());
            ((Consumer<Boolean>) lookups.get(0)[4]).accept(true);
            ((Consumer<Boolean>) lookups.get(0)[4]).accept(false);
            assertEquals("Stale cache lookup must neither play nor request the old episode", 2, online.get());
            ((Consumer<Boolean>) lookups.get(1)[4]).accept(false);
            assertEquals(3, online.get());
            assertEquals(second.getUrl(), state.getPendingRequest().getId());
        });
    }
}

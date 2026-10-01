package com.fongmi.android.tv.offline;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class OfflineVideoTest {
    @Test
    public void snapshotSurvivesCallerChangingEpisodesAndHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "episode-one");
        OfflineVideo video = new OfflineVideo("site:show:1", "剧名", "第一集", "线路一",
                "https://example.com/video.mp4", null, headers);
        headers.put("Cookie", "episode-two");
        assertEquals("episode-one", video.headers.get("Cookie"));
        assertThrows(UnsupportedOperationException.class, () -> video.headers.put("Referer", "changed"));
        OfflineVideo restored = OfflineVideo.decode(video.encode());
        assertEquals(video.id, restored.id);
        assertEquals(video.title, restored.title);
        assertEquals(video.episode, restored.episode);
        assertEquals(video.line, restored.line);
        assertEquals(video.url, restored.url);
        assertEquals(video.headers, restored.headers);
        assertNull(restored.mimeType);
    }

    @Test
    public void temporaryUrlsDoNotDuplicateAStableEpisodeIdentity() {
        OfflineVideo first = new OfflineVideo("site:show:1:720p", "Show", "1", "A",
                "https://example.com/v.mp4?token=one", "video/mp4", new HashMap<>());
        OfflineVideo second = new OfflineVideo("site:show:1:720p", "Show", "1", "A",
                "https://example.com/v.mp4?token=two", "video/mp4", new HashMap<>());
        OfflineVideo another = new OfflineVideo("site:show:2:720p", "Show", "2", "A",
                first.url, first.mimeType, new HashMap<>());
        assertEquals(first.id, second.id);
        assertNotEquals(first.id, another.id);
        assertEquals("video/mp4", OfflineVideo.decode(first.encode()).mimeType);
    }

    @Test
    public void playbackSettingsAndDanmakuRoundTripWithoutChangingLegacyIdentity() {
        String history = "{\"key\":\"site:show\",\"opening\":60000,\"ending\":30000}";
        String danmaku = "[{\"name\":\"Comments\",\"url\":\"https://example.com/comments.xml\",\"selected\":true}]";
        String source = "{\"key\":\"site\",\"flag\":\"original-line\",\"url\":\"https://example.com/episode/1\"}";
        OfflineVideo video = new OfflineVideo("site:show:1", "Show", "1", "A",
                "https://example.com/v.mp4", null, new HashMap<>(), history, danmaku, source);
        OfflineVideo restored = OfflineVideo.decode(video.encode());
        assertEquals(history, restored.history);
        assertEquals(danmaku, restored.danmaku);
        assertEquals(source, restored.source);
        assertEquals(video.id, restored.id);
    }

    @Test
    public void oldDownloadsDoNotRequireNewPlaybackMetadata() throws Exception {
        OfflineVideo video = new OfflineVideo("old", "Show", "1", "A",
                "https://example.com/v.mp4", null, new HashMap<>());
        org.json.JSONObject json = new org.json.JSONObject(new String(video.encode(), java.nio.charset.StandardCharsets.UTF_8));
        json.remove("history");
        json.remove("danmaku");
        json.remove("source");
        OfflineVideo legacy = OfflineVideo.decode(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(video.id, legacy.id);
        assertEquals("", legacy.history);
        assertEquals("[]", legacy.danmaku);
        assertEquals("", legacy.source);
    }

    @Test
    public void episodesGroupByOriginalShowAcrossLinesAndLegacyMetadata() {
        Map<String, String> headers = new HashMap<>();
        OfflineVideo first = new OfflineVideo("one", "Show", "1", "A", "https://example.com/1", null, headers);
        OfflineVideo second = new OfflineVideo("two", "Show", "2", "B", "https://example.com/2", null, headers);
        OfflineVideo other = new OfflineVideo("three", "Another show", "1", "A", "https://example.com/3", null, headers);
        assertEquals(first.groupKey(), second.groupKey());
        assertNotEquals(first.groupKey(), other.groupKey());
        OfflineVideo original = new OfflineVideo("four", "Show", "3", "A", "https://example.com/4", null, headers, "{\"key\":\"source:show\"}", "[]");
        OfflineVideo anotherLine = new OfflineVideo("five", "Show", "4", "B", "https://example.com/5", null, headers, "{\"key\":\"source:show\"}", "[]");
        OfflineVideo anotherSource = new OfflineVideo("six", "Show", "4", "B", "https://example.com/6", null, headers, "{\"key\":\"other:show\"}", "[]");
        assertEquals(original.groupKey(), anotherLine.groupKey());
        assertNotEquals(original.groupKey(), anotherSource.groupKey());
    }

    @Test
    public void corruptMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OfflineVideo.decode(new byte[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> OfflineVideo.decode("{}".getBytes()));
    }
}

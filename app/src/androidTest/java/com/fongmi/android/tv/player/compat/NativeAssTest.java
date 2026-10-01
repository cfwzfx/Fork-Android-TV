package com.fongmi.android.tv.player.compat;

import com.fongmi.android.tv.player.subtitle.AndroidFontConfig;
import java.nio.charset.StandardCharsets;
import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeAssTest {
    public static final String SCRIPT = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Roboto,30,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,{\\move(100,100,400,100)}Hello 字幕
            """;

    @Test public void testRenderSeekStyleAndExpiry() {
        assertTrue("Bundled libmpv must export libass", NativeAss.isAvailable());
        File config = AndroidFontConfig.prepare();
        assertNotNull("Android font configuration", config);
        try (NativeAss ass = new NativeAss(config.getAbsolutePath(), null, "sans-serif")) {
            ass.configure(640, 360, 1, "");
            ass.feed(SCRIPT.getBytes(StandardCharsets.UTF_8));
            NativeAss.Frame first = ass.render(100);
            assertNotNull(first); assertNotNull(first.bitmap);
            assertTrue(first.bitmap.getWidth() > 0);
            assertTrue(first.x >= 0 && first.x + first.bitmap.getWidth() <= 640);
            NativeAss.Frame moving = ass.render(1000);
            assertNotNull(moving); assertNotNull(moving.bitmap);
            assertTrue("ASS movement must follow playback time", moving.x > first.x);
            NativeAss.Frame expired = ass.render(2500);
            assertNotNull(expired); assertNull(expired.bitmap);
            NativeAss.Frame seek = ass.render(100);
            assertNotNull(seek); assertNotNull(seek.bitmap);
            assertEquals(first.x, seek.x);
            ass.configure(640, 360, 1, "PrimaryColour=&H000000FF\n");
            NativeAss.Frame styled = ass.render(100);
            assertNotNull(styled); assertNotNull(styled.bitmap);
            boolean red = false;
            for (int y = 0; y < styled.bitmap.getHeight(); y++) {
                for (int x = 0; x < styled.bitmap.getWidth(); x++) {
                    int pixel = styled.bitmap.getPixel(x, y);
                    if (((pixel >>> 16) & 255) > 180 && ((pixel >>> 8) & 255) < 50 && (pixel >>> 24) > 100) red = true;
                }
            }
            assertTrue("Style updates must recolor rendered glyphs", red);
        }
    }

    @Test public void testClosedHandleAndEmptyTrack() {
        assertTrue(NativeAss.isAvailable());
        NativeAss ass = new NativeAss(AndroidFontConfig.prepare().getAbsolutePath(), null, null);
        ass.configure(1920, 1080, 1, "");
        NativeAss.Frame frame = ass.render(0);
        assertNotNull(frame); assertNull(frame.bitmap);
        ass.close(); ass.close(); assertNull(ass.render(0));
    }
}

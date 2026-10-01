package com.fongmi.android.tv.player.exo;

import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.text.TextOutput;
import java.util.function.Consumer;

final class SecondarySubtitleOutput implements TextOutput {
    private Consumer<CueGroup> listener;
    private CueGroup cues = CueGroup.EMPTY_TIME_ZERO;
    @Override public void onCues(CueGroup value) {
        cues = value;
        if (listener != null) listener.accept(value);
    }
    void setListener(Consumer<CueGroup> value) {
        listener = value;
        if (value != null) value.accept(cues);
    }
    void refresh() { if (listener != null) listener.accept(cues); }
    void clear() { listener = null; cues = CueGroup.EMPTY_TIME_ZERO; }
}

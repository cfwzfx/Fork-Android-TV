package com.fongmi.android.tv.player.exo;

import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import com.fongmi.android.tv.player.engine.PlayerEngine.SecondarySubtitleState;
import com.fongmi.android.tv.setting.SubtitleSetting;
import java.util.Locale;

final class ExoSubtitleController {
    private final ExoPlayerSession session;
    private PlayerView view;
    private FrameLayout container;
    private SubtitleView secondaryView;
    private TrackSelectionOverride explicitSelection;
    private final View.OnLayoutChangeListener layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> updateAssPresentation();
    private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) { unbind(); }
    };

    ExoSubtitleController(ExoPlayerSession session) { this.session = session; applySubtitleStyle(); }
    void release() { unbind(); }
    void bindPlayerView(PlayerView next) {
        if (view == next) { applySubtitleStyle(); return; }
        unbind();
        view = next;
        SubtitleView primary = next.getSubtitleView();
        if (primary != null && primary.getParent() instanceof FrameLayout frame) {
            container = frame;
            secondaryView = new SubtitleView(next.getContext());
            secondaryView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(secondaryView, new FrameLayout.LayoutParams(-1, -1));
            frame.addOnLayoutChangeListener(layoutListener);
            next.addOnAttachStateChangeListener(attachListener);
            session.secondaryTextOutput().setListener(this::showSecondaryCues);
        }
        applySubtitleStyle();
    }
    private void showSecondaryCues(CueGroup cues) {
        if (secondaryView == null) return;
        secondaryView.setCues(cues.cues.stream().map(cue -> cue.bitmap != null ? cue : cue.buildUpon()
                .setLine(SubtitleSetting.getSecondaryPosition() / 100f, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE).build()).toList());
    }
    private void unbind() {
        session.secondaryTextOutput().setListener(null);
        if (container != null) {
            container.removeOnLayoutChangeListener(layoutListener);
            if (secondaryView != null) container.removeView(secondaryView);
        }
        if (view != null) view.removeOnAttachStateChangeListener(attachListener);
        view = null; container = null; secondaryView = null;
    }
    void applySubtitleStyle() {
        if (view != null) SubtitleSetting.applyStyle(view.getSubtitleView());
        if (secondaryView != null) {
            SubtitleSetting.applyStyle(secondaryView);
            secondaryView.setBottomPosition(0);
            secondaryView.setBottomPaddingFraction((100f - SubtitleSetting.getSecondaryPosition()) / 100f);
            session.secondaryTextOutput().refresh();
        }
        updateAssPresentation();
        int mode = SubtitleSetting.getSecondaryMode();
        session.trackSelector().setSecondary(mode == SubtitleSetting.SECONDARY_MODE_OFF ? null : explicitSelection, mode == SubtitleSetting.SECONDARY_MODE_AUTO);
    }
    SecondarySubtitleState getSecondarySubtitleState() {
        DualSubtitleTrackSelector.Snapshot state = session.trackSelector().snapshot();
        return new SecondarySubtitleState(state.primary(), explicitSelection, state.candidates(), false);
    }
    void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
        explicitSelection = selection;
        applySubtitleStyle();
    }
    private void updateAssPresentation() {
        int width = container == null || container.getWidth() == 0 ? 1280 : container.getWidth();
        int height = container == null || container.getHeight() == 0 ? 720 : container.getHeight();
        double factor = Math.min(1.0, Math.min(1920.0 / width, 1080.0 / height));
        width = Math.max(1, (int) (width * factor)); height = Math.max(1, (int) (height * factor));
        StringBuilder overrides = new StringBuilder();
        String family = SubtitleSetting.getFontFamily();
        if (family != null) overrides.append("Fontname=").append(family.replace("\n", "")).append('\n');
        if (SubtitleSetting.isStyleForced()) {
            CaptionStyleCompat style = SubtitleSetting.getStyle();
            overrides.append("PrimaryColour=").append(assColor(style.foregroundColor)).append('\n');
            overrides.append("OutlineColour=").append(assColor(style.edgeColor)).append('\n');
            overrides.append("BackColour=").append(assColor(style.backgroundColor)).append('\n');
            overrides.append("Outline=").append(style.edgeType == CaptionStyleCompat.EDGE_TYPE_NONE ? 0 : style.edgeWidth).append('\n');
            overrides.append("Shadow=").append(style.edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW ? style.shadowOffset : 0).append('\n');
        }
        for (AssSubtitleRenderer renderer : session.assRenderers()) {
            boolean secondary = renderer.getName().equals("SecondaryAssRenderer");
            float position = secondary ? SubtitleSetting.getSecondaryPosition() / 100f : SubtitleSetting.getPosition() / 100f;
            renderer.setPresentation(new AssSubtitleRenderer.Presentation(width, height, SubtitleSetting.isScaleApplied() ? SubtitleSetting.getAppliedScale() : 1.0, overrides.toString(), position, secondary));
        }
    }
    private static String assColor(int argb) {
        return String.format(Locale.ROOT, "&H%02X%02X%02X%02X", 255 - ((argb >>> 24) & 255), argb & 255, (argb >>> 8) & 255, (argb >>> 16) & 255);
    }
}

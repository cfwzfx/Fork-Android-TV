package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

import java.util.ArrayList;
import java.util.List;

/** Delegates normal audio/video/primary selection and adds a second text renderer. */
final class DualSubtitleTrackSelector extends TrackSelector {
    private final DecodeTrackSelector primary;
    private final boolean nativeAss;
    private volatile TrackSelectionOverride explicit;
    private volatile boolean auto;
    private volatile Snapshot snapshot = new Snapshot(null, null, List.of());

    record Snapshot(@Nullable TrackSelectionOverride primary, @Nullable TrackSelectionOverride secondary,
                    List<TrackSelectionOverride> candidates) {}

    DualSubtitleTrackSelector(DecodeTrackSelector primary, boolean nativeAss) {
        this.primary = primary;
        this.nativeAss = nativeAss;
    }
    void setDecode(int decode) { ExoUtil.setDecodePreferences(primary, decode); }
    void setSecondary(@Nullable TrackSelectionOverride value, boolean automatic) {
        explicit = value; auto = automatic; invalidate(null);
    }
    Snapshot snapshot() { return snapshot; }

    @Override public void init(InvalidationListener listener, BandwidthMeter meter) {
        super.init(listener, meter); primary.init(listener, meter);
    }
    @Override public void release() { primary.release(); super.release(); }
    @Override public void onSelectionActivated(@Nullable Object info) { primary.onSelectionActivated(info); }
    @Override public void onParametersActivated(@Nullable TrackSelectionParameters parameters) { primary.onParametersActivated(parameters); }
    @Override public TrackSelectionParameters getParameters() { return primary.getParameters(); }
    @Override public boolean isSetParametersSupported() { return true; }
    @Override public void setParameters(TrackSelectionParameters value) { primary.setParameters(value); }
    @Override public void setAudioAttributes(AudioAttributes value) { primary.setAudioAttributes(value); }
    @Override public RendererCapabilities.Listener getRendererCapabilitiesListener() { return primary.getRendererCapabilitiesListener(); }

    @Override public TrackSelectorResult selectTracks(RendererCapabilities[] capabilities, TrackGroupArray groups,
                                                     MediaPeriodId period, Timeline timeline) throws ExoPlaybackException {
        List<Integer> text = new ArrayList<>();
        for (int i = 0; i < capabilities.length; i++) if (capabilities[i].getTrackType() == C.TRACK_TYPE_TEXT) text.add(i);
        int primaryCount = nativeAss ? 2 : 1;
        RendererCapabilities[] masked = capabilities.clone();
        for (int n = 0; n < text.size(); n++) {
            int index = text.get(n);
            if (n >= primaryCount) masked[index] = new MaskedCapabilities(capabilities[index], true, false);
            else if (nativeAss && n == 0) masked[index] = new MaskedCapabilities(capabilities[index], false, true);
        }
        TrackSelectorResult result = primary.selectTracks(masked, groups, period, timeline);
        ExoTrackSelection[] selections = result.selections.clone();
        RendererConfiguration[] configurations = result.rendererConfigurations.clone();
        TrackSelectionOverride selectedPrimary = null;
        for (int n = 0; n < Math.min(primaryCount, text.size()); n++) {
            ExoTrackSelection selected = selections[text.get(n)];
            if (selected != null) selectedPrimary = new TrackSelectionOverride(selected.getTrackGroup(), selected.getIndexInTrackGroup(0));
        }
        List<TrackSelectionOverride> candidates = new ArrayList<>();
        for (int g = 0; g < groups.length; g++) {
            TrackGroup group = groups.get(g);
            if (group.type != C.TRACK_TYPE_TEXT) continue;
            for (int t = 0; t < group.length; t++) {
                TrackSelectionOverride candidate = new TrackSelectionOverride(group, t);
                if (candidate.equals(selectedPrimary)) continue;
                for (int n = primaryCount; n < text.size(); n++) {
                    if (RendererCapabilities.getFormatSupport(capabilities[text.get(n)].supportsFormat(group.getFormat(t))) == C.FORMAT_HANDLED) {
                        candidates.add(candidate); break;
                    }
                }
            }
        }
        TrackSelectionOverride secondary = candidates.contains(explicit) ? explicit : auto ? chooseAuto(candidates, selectedPrimary) : null;
        if (secondary != null && selectedPrimary != null && !getParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            Format format = secondary.mediaTrackGroup.getFormat(secondary.trackIndices.get(0));
            for (int n = primaryCount; n < text.size(); n++) {
                int index = text.get(n);
                if (nativeAss && MimeTypes.TEXT_SSA.equals(format.sampleMimeType) && n == primaryCount) continue;
                if (RendererCapabilities.getFormatSupport(capabilities[index].supportsFormat(format)) != C.FORMAT_HANDLED) continue;
                selections[index] = new FixedTrackSelection(secondary.mediaTrackGroup, secondary.trackIndices.get(0));
                configurations[index] = RendererConfiguration.DEFAULT; break;
            }
        } else secondary = null;
        snapshot = new Snapshot(selectedPrimary, secondary, List.copyOf(candidates));
        List<Tracks.Group> tracks = new ArrayList<>();
        for (Tracks.Group group : result.tracks.getGroups()) {
            int[] support = new int[group.length]; boolean[] selected = new boolean[group.length];
            for (int t = 0; t < group.length; t++) {
                support[t] = group.getTrackSupport(t);
                selected[t] = group.isTrackSelected(t) || (secondary != null && secondary.mediaTrackGroup.equals(group.getMediaTrackGroup()) && secondary.trackIndices.contains(t));
            }
            tracks.add(new Tracks.Group(group.getMediaTrackGroup(), group.isAdaptiveSupported(), support, selected));
        }
        return new TrackSelectorResult(configurations, selections, new Tracks(tracks), result.info);
    }

    @Nullable static TrackSelectionOverride chooseAuto(List<TrackSelectionOverride> candidates, @Nullable TrackSelectionOverride primary) {
        if (primary == null) return null;
        String language = primary.mediaTrackGroup.getFormat(primary.trackIndices.get(0)).language;
        for (TrackSelectionOverride candidate : candidates) {
            String other = candidate.mediaTrackGroup.getFormat(candidate.trackIndices.get(0)).language;
            if (other != null && !other.equals(language)) return candidate;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private record MaskedCapabilities(RendererCapabilities delegate, boolean disabled, boolean excludeAss) implements RendererCapabilities {
        @Override public String getName() { return delegate.getName(); }
        @Override public int getTrackType() { return delegate.getTrackType(); }
        @Override public int supportsFormat(Format format) throws ExoPlaybackException {
            return disabled || (excludeAss && MimeTypes.TEXT_SSA.equals(format.sampleMimeType)) ? RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE) : delegate.supportsFormat(format);
        }
        @Override public int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException { return delegate.supportsMixedMimeTypeAdaptation(); }
    }
}

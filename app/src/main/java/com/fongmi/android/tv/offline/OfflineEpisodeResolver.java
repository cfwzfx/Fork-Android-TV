package com.fongmi.android.tv.offline;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.utils.Task;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resolves an episode without changing the active player, history or preload request. */
final class OfflineEpisodeResolver {

    static void resolve(Context context, History original, OfflineCache.Callback callback) {
        resolve(context, original, false, video -> OfflineIntegration.get(context).add(video, OfflineIntegration.downloadParameters().build(), callback), callback);
    }

    static void refresh(Context context, History original, java.util.function.Consumer<OfflineVideo> ready, OfflineCache.Callback callback) {
        resolve(context, original, true, ready, callback);
    }

    private static void resolve(Context context, History original, boolean refresh, java.util.function.Consumer<OfflineVideo> ready, OfflineCache.Callback callback) {
        History history = original.copy();
        String identity = OfflineVideo.identity(history);
        callback.complete(R.string.offline_preparing);
        AtomicBoolean done = new AtomicBoolean();
        ParseJob[] parse = new ParseJob[1];
        Future<?> request = Task.submit(() -> {
            try {
                String key = history.getKey().split(java.util.regex.Pattern.quote(AppDatabase.SYMBOL), -1)[0];
                if (refresh) {
                    if (com.fongmi.android.tv.api.config.VodConfig.getCid() != history.getCid()) {
                        App.post(() -> finish(done, callback, R.string.offline_recache_config)); return;
                    }
                    String id = history.getKey().split(java.util.regex.Pattern.quote(AppDatabase.SYMBOL), -1)[1];
                    var detail = SiteApi.detailContent(key, id).getVod();
                    if (!id.equals(detail.getId())) throw new IllegalStateException("The original show is unavailable");
                    var episodes = detail.getFlags().stream().filter(line -> line.getFlag().equals(history.getVodFlag()))
                            .flatMap(line -> line.getEpisodes().stream()).filter(episode -> history.getVodRemarks().trim().isEmpty()
                                    ? episode.getUrl().equals(history.getEpisodeUrl()) : episode.getName().equals(history.getVodRemarks())).toList();
                    if (episodes.size() != 1) throw new IllegalStateException("No unique exact episode");
                    history.setEpisodeUrl(episodes.get(0).getUrl());
                }
                Result result = SiteApi.playerContent(key, history.getVodFlag(), history.getEpisodeUrl(), new Source() {
                    @Override public String fetch(Result value) throws Exception {
                        // Stateful P2P/native extractors cannot provide independent complete HTTP downloads.
                        String url = value.getUrl().v();
                        if (url.startsWith("video://")) { value.setParse(1); return url.substring(8); }
                        if (!url.startsWith("http://") && !url.startsWith("https://"))
                            throw new UnsupportedOperationException();
                        return url;
                    }
                });
                App.post(() -> {
                    if (done.get()) return;
                    if (refresh && com.fongmi.android.tv.api.config.VodConfig.getCid() != history.getCid()) {
                        finish(done, callback, R.string.offline_recache_config); return;
                    }
                    if (result.getDrm() != null || result.getUrl().isEmpty()) {
                        finish(done, callback, R.string.offline_unsupported);
                        return;
                    }
                    ParseCallback resolved = new ParseCallback() {
                        @Override public void onParseSuccess(Map<String, String> headers, String url, String from) {
                            if (!done.compareAndSet(false, true)) return;
                            if (refresh && com.fongmi.android.tv.api.config.VodConfig.getCid() != history.getCid()) {
                                callback.complete(R.string.offline_recache_config); return;
                            }
                            if (!(url.startsWith("http://") || url.startsWith("https://")) || url.contains("***")) {
                                callback.complete(R.string.offline_unsupported);
                                return;
                            }
                            Map<String, String> merged = new HashMap<>(result.getHeader());
                            merged.putAll(headers);
                            OfflineVideo video = new OfflineVideo(identity, history.getVodName(), history.getVodRemarks(),
                                    history.getVodFlag(), url, result.getFormat(), merged, history.toString(),
                                    App.gson().toJson(result.getDanmaku()), result.toString());
                            try { ready.accept(video); }
                            catch (RuntimeException error) { callback.complete(R.string.offline_storage_error); }
                        }
                        @Override public void onParseError() {
                            finish(done, callback, R.string.offline_resolve_error);
                        }
                    };
                    if (result.needParse()) parse[0] = ParseJob.create(resolved).start(result, result.isUseParse());
                    else resolved.onParseSuccess(result.getHeader(), result.getRealUrl(), result.getJxFrom());
                });
            } catch (Exception error) {
                App.post(() -> finish(done, callback, error instanceof UnsupportedOperationException
                        ? R.string.offline_unsupported : R.string.offline_resolve_error));
            }
        });
        Task.schedule(() -> App.post(() -> {
            if (done.get()) return;
            request.cancel(true);
            if (parse[0] != null) parse[0].stop();
            finish(done, callback, R.string.offline_resolve_error);
        }), 60, TimeUnit.SECONDS);
    }

    private static void finish(AtomicBoolean done, OfflineCache.Callback callback, int message) {
        if (!done.compareAndSet(false, true)) return;
        callback.complete(message);
    }
}

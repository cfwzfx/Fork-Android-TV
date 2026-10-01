package com.fongmi.android.tv.offline;

import android.content.Context;
import android.os.Looper;

import androidx.media3.exoplayer.offline.Download;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** LAN invitation/pull protocol. Server registration and device UI are mobile flavor hooks. */
public final class OfflineCacheSync implements Process {
    private static final String PATH = "/offline-sync/";
    private static volatile OfflineCacheSync instance;
    private final OfflineCacheTransfer transfer;
    private final Map<String, Export> exports = new ConcurrentHashMap<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    // Long transfers must not occupy the application's small source-resolution executor.
    private final java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newCachedThreadPool();
    private final OkHttpClient client = OkHttp.client().newBuilder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();

    private OfflineCacheSync(Context context) { transfer = new OfflineCacheTransfer(context, OfflineIntegration.get(context)); }

    public static OfflineCacheSync get(Context context) {
        if (instance != null) return instance;
        FutureTask<OfflineCacheSync> task = new FutureTask<>(() -> {
            if (instance == null) instance = new OfflineCacheSync(context.getApplicationContext());
            return instance;
        });
        if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else App.post(task);
        try { return task.get(); } catch (Exception error) { throw new IllegalStateException("Cache unavailable", error); }
    }

    private static final class Export {
        final OfflineCacheTransfer.Snapshot snapshot;
        final String peer;
        final long created = System.currentTimeMillis();
        Export(OfflineCacheTransfer.Snapshot snapshot, String peer) { this.snapshot = snapshot; this.peer = peer; }
    }

    private static final class Job {
        final OfflineCacheTransfer.Import target;
        final String peer;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile String state = "receiving";
        volatile long received;
        volatile Call call;
        final long created = System.currentTimeMillis();
        Job(OfflineCacheTransfer.Import target, String peer) { this.target = target; this.peer = peer; }
    }

    @Override public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) { return url.startsWith(PATH); }

    @Override public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        String peer = session.getRemoteIpAddress();
        if (!OfflineLan.contains(peer)) return response(NanoHTTPD.Response.Status.FORBIDDEN, "lan_only");
        try {
            prune();
            Map<String, String> params = session.getParms();
            if (url.equals(PATH + "capabilities") && session.getMethod() == NanoHTTPD.Method.GET)
                return json(new JSONObject().put("version", OfflineCacheTransfer.VERSION).put("device", new JSONObject(Device.get().toString())));
            if (url.equals(PATH + "export") && session.getMethod() == NanoHTTPD.Method.GET) {
                Export source = exports.get(params.get("token"));
                if (source == null || !source.peer.equals(peer)) return response(NanoHTTPD.Response.Status.NOT_FOUND, "expired");
                InputStream input = source.snapshot.open();
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", input, source.snapshot.manifest.getLong("bytes"));
            }
            if (url.equals(PATH + "receive") && session.getMethod() == NanoHTTPD.Method.POST) {
                String raw = params.get("invitation");
                if (raw == null || raw.length() > 2 * 1024 * 1024 || jobs.size() >= 256) throw new IOException("Invalid invitation");
                JSONObject invitation = new JSONObject(raw);
                int port = invitation.getInt("port");
                String token = invitation.getString("token");
                if (port < 1 || port > 65535 || !token.matches("[0-9a-f-]{36}")) throw new IOException("Invalid sender");
                // Never trust a URL supplied by a peer: pull only from the actual LAN caller.
                String source = OfflineLan.endpoint("http://" + peer + ":" + port) + PATH + "export?token=" + token;
                OfflineCacheTransfer.Import target = transfer.prepare(invitation.getJSONObject("manifest"));
                if (target == null) return json(new JSONObject().put("state", "exists"));
                String id = UUID.randomUUID().toString();
                Job job = new Job(target, peer);
                jobs.put(id, job);
                App.post(() -> com.fongmi.android.tv.utils.Notify.show(App.get().getString(com.fongmi.android.tv.R.string.offline_sync_receiving, target.video.title + " · " + target.video.episode)));
                workers.execute(() -> receive(job, source));
                return json(new JSONObject().put("state", "receiving").put("job", id));
            }
            if ((url.equals(PATH + "status") && session.getMethod() == NanoHTTPD.Method.GET)
                    || (url.equals(PATH + "cancel") && session.getMethod() == NanoHTTPD.Method.POST)) {
                Job job = jobs.get(params.get("job"));
                if (job == null || !job.peer.equals(peer)) return response(NanoHTTPD.Response.Status.NOT_FOUND, "expired");
                if (url.endsWith("cancel")) { job.cancelled.set(true); if (job.call != null) job.call.cancel(); }
                return json(new JSONObject().put("state", job.state).put("bytes", job.received).put("total", job.target.bytes));
            }
            return response(NanoHTTPD.Response.Status.NOT_FOUND, "unsupported");
        } catch (Exception error) {
            // Do not echo headers, signed source URLs or paths over the wire.
            return response(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid_or_unavailable");
        }
    }

    private void receive(Job job, String source) {
        try (OfflineCacheTransfer.Import target = job.target) {
            job.call = client.newCall(new Request.Builder().url(source).build());
            if (job.cancelled.get()) job.call.cancel();
            try (Response result = job.call.execute()) {
                if (!result.isSuccessful() || result.body() == null || result.body().contentLength() != target.bytes) throw new IOException("Source unavailable");
                target.receive(result.body().byteStream(), job.cancelled::get, bytes -> job.received = bytes);
            }
            job.state = "completed";
            App.post(() -> com.fongmi.android.tv.utils.Notify.show(App.get().getString(com.fongmi.android.tv.R.string.offline_sync_received, job.target.video.title + " · " + job.target.video.episode)));
        } catch (Exception error) { job.state = job.cancelled.get() ? "cancelled" : "failed"; }
    }

    private void prune() {
        long before = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
        exports.entrySet().removeIf(entry -> entry.getValue().created < before);
        jobs.entrySet().removeIf(entry -> !entry.getValue().state.equals("receiving") && entry.getValue().created < before);
    }

    public interface Listener {
        void progress(String title, int index, int count, long received, long total);
        void complete(int sent, int skipped, String error);
    }

    public Send send(Device device, List<Download> selected, Listener listener) {
        Send send = new Send(device, List.copyOf(selected), listener);
        workers.execute(send);
        return send;
    }

    public final class Send implements Runnable {
        private final Device device;
        private final List<Download> downloads;
        private final Listener listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Call call;
        private String target, job;
        Send(Device device, List<Download> downloads, Listener listener) { this.device = device; this.downloads = downloads; this.listener = listener; }
        public void cancel() { cancelled.set(true); if (call != null) call.cancel(); }

        private JSONObject request(String url, FormBody body) throws Exception {
            if (cancelled.get()) throw new IOException("Cancelled");
            Request.Builder builder = new Request.Builder().url(url);
            if (body != null) builder.post(body);
            call = client.newCall(builder.build());
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("The other device is unavailable or rejected the transfer");
                String text = response.body().string();
                if (text.length() > 2 * 1024 * 1024) throw new IOException("Invalid reply");
                return new JSONObject(text);
            }
        }

        @Override public void run() {
            int sent = 0, skipped = 0;
            String error = null;
            try {
                target = OfflineLan.endpoint(device.getIp());
                JSONObject capabilities = request(target + PATH + "capabilities", null);
                Device actual = Device.objectFrom(capabilities.getJSONObject("device").toString());
                if (capabilities.getInt("version") != OfflineCacheTransfer.VERSION || actual == null || !actual.isMobile()
                        || !actual.getUuid().equals(device.getUuid())) throw new IOException("The other phone needs the same cache sync version");
                // The cache page can be opened from a notification before the home page starts its server.
                Server.get().start();
                HttpUrl own = HttpUrl.parse(Server.get().getAddress());
                if (own == null || !OfflineLan.contains(own.host())) throw new IOException("No LAN connection");
                for (int i = 0; i < downloads.size(); i++) {
                    job = null;
                    Download download = downloads.get(i);
                    OfflineVideo video = OfflineVideo.decode(download.request.data);
                    String title = video.title + " · " + video.episode;
                    int index = i + 1;
                    App.post(() -> listener.progress(title, index, downloads.size(), 0, 0));
                    OfflineCacheTransfer.Snapshot snapshot = transfer.export(download.request.id, cancelled::get);
                    String token = UUID.randomUUID().toString();
                    exports.put(token, new Export(snapshot, HttpUrl.parse(target).host()));
                    try {
                        JSONObject invitation = new JSONObject().put("port", own.port()).put("token", token).put("manifest", snapshot.manifest);
                        if (invitation.toString().length() > 2 * 1024 * 1024) throw new IOException("Cache metadata is too large");
                        JSONObject status = request(target + PATH + "receive", new FormBody.Builder().add("invitation", invitation.toString()).build());
                        if (status.getString("state").equals("exists")) { skipped++; continue; }
                        job = status.getString("job");
                        long deadline = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2);
                        while (true) {
                            if (cancelled.get() || System.currentTimeMillis() > deadline) throw new IOException("Transfer cancelled or timed out");
                            status = request(target + PATH + "status?job=" + job, null);
                            String state = status.getString("state");
                            long bytes = status.getLong("bytes"), total = status.getLong("total");
                            App.post(() -> listener.progress(title, index, downloads.size(), bytes, total));
                            if (state.equals("completed")) { sent++; break; }
                            if (!state.equals("receiving")) throw new IOException("Transfer failed; check network and storage, then retry");
                            Thread.sleep(500);
                        }
                    } finally { exports.remove(token); }
                }
            } catch (Exception failure) { error = cancelled.get() ? "cancelled" : failure.getMessage(); }
            finally {
                if (job != null && target != null && error != null) {
                    try (Response ignored = client.newCall(new Request.Builder().url(target + PATH + "cancel")
                            .post(new FormBody.Builder().add("job", job).build()).build()).execute()) { }
                    catch (Exception ignored) { }
                }
                int completed = sent, existed = skipped;
                String message = error;
                App.post(() -> listener.complete(completed, existed, message));
            }
        }
    }

    private static NanoHTTPD.Response json(JSONObject object) { return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", object.toString()); }
    private static NanoHTTPD.Response response(NanoHTTPD.Response.Status status, String message) { return NanoHTTPD.newFixedLengthResponse(status, "text/plain", message); }
}

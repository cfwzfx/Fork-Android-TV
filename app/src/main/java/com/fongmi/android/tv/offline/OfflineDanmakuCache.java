package com.fongmi.android.tv.offline;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Persists fetched comments outside the disposable online cache, using the current PlayerView API. */
public final class OfflineDanmakuCache {
    private OfflineDanmakuCache() {}

    public static OkHttpClient client(OkHttpClient base) {
        return (base == null ? OkHttp.client() : base).newBuilder().addInterceptor(chain -> {
            File directory = new File(App.get().getFilesDir(), "danmaku_saved");
            String url = chain.request().url().toString();
            File saved = new File(directory, UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)) + ".xml");
            try {
                Response response = chain.proceed(chain.request());
                if (!response.isSuccessful() || response.body() == null) {
                    if (saved.isFile()) { response.close(); throw new IOException("Danmaku unavailable"); }
                    return response;
                }
                MediaType type = response.body().contentType();
                byte[] bytes;
                try (ResponseBody body = response.body()) { bytes = body.bytes(); }
                File temporary = null;
                try {
                    if (directory.isDirectory() || directory.mkdirs()) {
                        temporary = File.createTempFile("comments-", ".tmp", directory);
                        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); }
                        temporary.renameTo(saved);
                    }
                } catch (IOException ignored) {
                    // Storage errors must not prevent online comments from loading.
                } finally {
                    if (temporary != null) temporary.delete();
                }
                return response.newBuilder().body(ResponseBody.create(type, bytes)).build();
            } catch (IOException error) {
                if (!saved.isFile()) throw error;
                byte[] bytes;
                try (java.io.FileInputStream input = new java.io.FileInputStream(saved);
                     java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                    bytes = output.toByteArray();
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Saved danmaku").body(ResponseBody.create(MediaType.parse("application/xml"), bytes)).build();
            }
        }).build();
    }
}

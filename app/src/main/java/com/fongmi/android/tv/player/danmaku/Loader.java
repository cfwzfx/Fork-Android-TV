package com.fongmi.android.tv.player.danmaku;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import okhttp3.Response;

import master.flame.danmaku.danmaku.loader.ILoader;
import master.flame.danmaku.danmaku.loader.IllegalDataException;
import master.flame.danmaku.danmaku.parser.android.AndroidFileSource;

public class Loader implements ILoader {

    private AndroidFileSource dataSource;

    public Loader(Danmaku item) {
        try {
            load(item.getUrl());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void load(String url) throws IllegalDataException {
        if (url.startsWith("/") || url.startsWith("file:")) {
            try {
                load(new FileInputStream(url.startsWith("/") ? url : android.net.Uri.parse(url).getPath()));
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }
        File directory = new File(App.get().getFilesDir(), "danmaku_saved");
        directory.mkdirs();
        File saved = new File(directory, UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)) + ".xml");
        File temporary = null;
        try {
            OkHttp.cancel("danmaku");
            try (Response response = OkHttp.newCall(OkHttp.client(Constant.TIMEOUT_DANMAKU), UrlUtil.convert(url), "danmaku").execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Cannot load danmaku");
                temporary = File.createTempFile("danmaku-", ".tmp", directory);
                try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                }
                if (!temporary.renameTo(saved)) throw new IOException("Cannot save danmaku");
                load(new FileInputStream(saved));
            }
        } catch (IOException e) {
            try {
                if (saved.isFile()) load(new FileInputStream(saved));
                else e.printStackTrace();
            } catch (IOException failure) {
                failure.printStackTrace();
            }
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    @Override
    public void load(InputStream stream) throws IllegalDataException {
        dataSource = new AndroidFileSource(stream);
    }

    @Override
    public AndroidFileSource getDataSource() {
        return dataSource;
    }
}

package com.fongmi.android.tv.player.danmaku;

import android.content.Context;
import android.widget.Toast;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.fongmi.android.tv.App.post;

// todo cf 弹幕
public class CustomConfigManager {

    private static final String FILE_NAME = "cf-custom-34a24vx2d34002199a3d42.json";
    private static CustomConfigManager instance;
    private CustomConfig config;
    private boolean initialized;
    private Context context;
    private String currentHistoryId;

    private CustomConfigManager() {
        this.config = new CustomConfig();
        this.initialized = false;
    }

    public static CustomConfigManager get() {
        if (instance == null) {
            instance = new CustomConfigManager();
        }
        return instance;
    }

    /**
     * 初始化方法，可以多次调用，如果已经初始化则跳过
     */
    public void init() {
        if (initialized) {
            return;
        }
        loadFromFile();
        initialized = true;
    }

    /**
     * 从文件加载配置
     */
    private void loadFromFile() {
        File file = getConfigFile();
        if (!file.exists() || file.length() == 0) {
            // 文件不存在或为空，使用默认配置
            config = new CustomConfig();
            return;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int bytesRead = fis.read(buffer);
            if (bytesRead <= 0) {
                config = new CustomConfig();
                return;
            }
            String json = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
            config = App.gson().fromJson(json, CustomConfig.class);
            if (config == null) {
                config = new CustomConfig();
            }
            if (config.getHistory() == null) {
                config.setHistory(new ArrayList<>());
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 读取失败，使用默认配置
            config = new CustomConfig();
        }
    }

    /**
     * 保存配置到文件
     */
    private void saveToFile() {
        File file = getConfigFile();
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            String json = App.gson().toJson(config);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取配置文件
     */
    private File getConfigFile() {
        return new File(Path.files(), FILE_NAME);
    }

    /**
     * 设置Context
     *
     * @param context
     */
    public void setContext(Context context) {
        this.context = context;
    }

    /**
     * 获取Context
     *
     * @return
     */
    public Context getContext() {
        return context;
    }

    /**
     * 展示一个toast
     *
     * @param text
     */
    public void showToast(String text, long delayTime) {
        if (context != null) {
            post(() -> {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            }, delayTime);
        }
    }

    public void setCurrentHistoryId(String id) {
        currentHistoryId = id;
    }

    public String getCurrentHistoryId() {
        return currentHistoryId;
    }


    public void clearCurrentHistoryId() {
        currentHistoryId = null;
    }


    /**
     * 获取弹幕最大行数
     */
    public int getDanmuMaxLines() {
        if (!initialized) {
            init();
        }
        return config.getDanmuMaxLines();
    }

    /**
     * 设置弹幕最大行数
     */
    public void setDanmuMaxLines(int danmuMaxLines) {
        if (!initialized) {
            init();
        }
        config.setDanmuMaxLines(danmuMaxLines);
        saveToFile();
    }

    /**
     * 获取历史记录列表
     */
    public List<CustomConfig.HistoryItem> getHistory() {
        if (!initialized) {
            init();
        }
        return config.getHistory();
    }

    /**
     * 添加或更新历史记录
     */
    public void addOrUpdateHistory(String id, long offset) {
        if (!initialized) {
            init();
        }
        List<CustomConfig.HistoryItem> history = config.getHistory();
        if (history == null) {
            history = new ArrayList<>();
            config.setHistory(history);
        }

        // 查找是否已存在
        boolean found = false;
        for (CustomConfig.HistoryItem item : history) {
            if (id.equals(item.getId())) {
                item.setOffset(offset);
                found = true;
                break;
            }
        }

        // 如果不存在，添加新记录
        if (!found) {
            history.add(new CustomConfig.HistoryItem(id, offset));
        }

        saveToFile();
    }

    /**
     * 更新历史记录的偏移量
     */
    public void updateHistory(String id, long offset) {
        if (!initialized) {
            init();
        }
        List<CustomConfig.HistoryItem> history = config.getHistory();
        if (history == null) {
            return;
        }

        for (CustomConfig.HistoryItem item : history) {
            if (id.equals(item.getId())) {
                item.setOffset(offset);
                saveToFile();
                return;
            }
        }
    }

    /**
     * 删除历史记录
     */
    public void deleteHistory(String id) {
        if (!initialized) {
            init();
        }
        List<CustomConfig.HistoryItem> history = config.getHistory();
        if (history == null) {
            return;
        }

        Iterator<CustomConfig.HistoryItem> iterator = history.iterator();
        while (iterator.hasNext()) {
            CustomConfig.HistoryItem item = iterator.next();
            if (id.equals(item.getId())) {
                iterator.remove();
                saveToFile();
                return;
            }
        }
    }

    /**
     * 根据ID获取历史记录的偏移量
     */
    public long getHistoryOffset(String id) {
        if (!initialized) {
            init();
        }
        List<CustomConfig.HistoryItem> history = config.getHistory();
        if (history == null) {
            return 0;
        }

        for (CustomConfig.HistoryItem item : history) {
            if (id.equals(item.getId())) {
                return item.getOffset();
            }
        }
        return 0;
    }

    /**
     * 清空所有历史记录
     */
    public void clearHistory() {
        if (!initialized) {
            init();
        }
        config.setHistory(new ArrayList<>());
        saveToFile();
    }
}


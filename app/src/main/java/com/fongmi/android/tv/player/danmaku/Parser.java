package com.fongmi.android.tv.player.danmaku;

import com.fongmi.android.tv.bean.DanmakuData;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDanmakus;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.parser.android.AndroidFileSource;
import master.flame.danmaku.danmaku.util.DanmakuUtils;

public class Parser extends BaseDanmakuParser {

    private static final Pattern XML = Pattern.compile("p=\"([^\"]+)\"[^>]*>([^<]+)<");
    private static final Pattern TXT = Pattern.compile("\\[(.*?)\\](.*)");

    @Override
    public Danmakus parse() {
        String line;
        Pattern pattern = null;
        if (mDataSource == null) return null;
        List<DanmakuData> items = new ArrayList<>();
        AndroidFileSource source = (AndroidFileSource) mDataSource;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(source.data()))) {
            while ((line = br.readLine()) != null) {
                if (pattern == null) pattern = line.startsWith("<") ? XML : TXT;
                Matcher matcher = pattern.matcher(line);
                while (matcher.find() && matcher.groupCount() == 2) {
                    try {
                        items.add(new DanmakuData(matcher, mDispDensity));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            Danmakus result = new Danmakus(IDanmakus.ST_BY_TIME);
            for (int i = 0; i < items.size(); i++) {
                BaseDanmaku item = mContext.mDanmakuFactory.createDanmaku(items.get(i).getType(), mContext);
                if (item.getType() == BaseDanmaku.TYPE_SPECIAL) continue;
                DanmakuUtils.fillText(item, items.get(i).getText());
                item.textShadowColor = items.get(i).getShadow();
                item.textColor = items.get(i).getColor();
                item.flags = mContext.mGlobalFlagValues;
                item.textSize = items.get(i).getSize();
                item.setTime(addRandomOffset(items.get(i).getTime()));
                item.setTimer(mTimer);
                item.index = i;
                synchronized (result.obtainSynchronizer()) {
                    result.addItem(item);
                }
            }
            // todo cf 弹幕
            if (result.size() > 0) {
                CustomConfigManager.get().showToast("加载弹幕:" + result.size() +"条", 1000);
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // todo cf 弹幕
    // 添加随机数，让弹幕更加随机一点
    public static long addRandomOffset(long number) {
        Random random = new Random();
        int randomIndex = random.nextInt(10);
        int offset = -500 + randomIndex * 100;
        long randomTime =  number + offset;
        if (randomTime < 0) {
            return 0;
        } else {
            return randomTime;
        }
    }
}
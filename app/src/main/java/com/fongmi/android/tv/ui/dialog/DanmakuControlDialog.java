
package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.SeekBar;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.DialogDanmakuControlBinding;
import com.fongmi.android.tv.player.Players;
import com.fongmi.android.tv.player.danmaku.CustomConfigManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

// todo cf 弹幕
public final class DanmakuControlDialog extends BaseDialog {

    private DialogDanmakuControlBinding binding;
    private Players player;
    private History history;

    public static DanmakuControlDialog create() {
        return new DanmakuControlDialog();
    }

    public DanmakuControlDialog player(Players player) {
        this.player = player;
        return this;
    }

    public DanmakuControlDialog history(History history) {
        this.history = history;
        return this;
    }


    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof BottomSheetDialogFragment) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDanmakuControlBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.offsetValue.setText(CustomConfigManager.get().getHistoryOffset(history.getKey()) + "秒");
        OffsetViewInfo info = transferHistoryToViewValue((int) CustomConfigManager.get().getHistoryOffset(history.getKey()));

        binding.offset1sSeekbar.setProgress(info.oneSecProgress);
        binding.offset10sSeekbar.setProgress(info.tenSecProgress);
        binding.offsetSwitch.setChecked(info.isForward);
        binding.offset1sSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateHistory();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        binding.offset10sSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateHistory();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        binding.offsetSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateHistory();
        });


        binding.countValue.setText(CustomConfigManager.get().getDanmuMaxLines() + "行");
        binding.countSeekbar.setProgress(CustomConfigManager.get().getDanmuMaxLines());
        binding.countSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    CustomConfigManager.get().setDanmuMaxLines(progress);
                    binding.countValue.setText(CustomConfigManager.get().getDanmuMaxLines() + "行");
                    player.setDanmakuRlCount(CustomConfigManager.get().getDanmuMaxLines());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

    }

    @Override
    protected void initEvent() {
        //binding.choose.setOnClickListener(this::showChooser);
    }

    private void updateHistory() {
        OffsetViewInfo info = new OffsetViewInfo();
        info.oneSecProgress = binding.offset1sSeekbar.getProgress();
        info.tenSecProgress = binding.offset10sSeekbar.getProgress();
        info.isForward = binding.offsetSwitch.isChecked();
        int historyValue = transferViewToHistoryValue(info);

        CustomConfigManager.get().addOrUpdateHistory(history.getKey(), historyValue);
        binding.offsetValue.setText(historyValue + "秒");
        player.setDanmakuOffset(historyValue);
    }

    private OffsetViewInfo transferHistoryToViewValue(int historyValue) {
        OffsetViewInfo info = new OffsetViewInfo();
        info.isForward = historyValue >= 0;
        info.tenSecProgress = Math.abs(historyValue) / 10;
        info.oneSecProgress = historyValue % 10;

        return info;
    }

    private int transferViewToHistoryValue(OffsetViewInfo info) {
        int value = info.tenSecProgress * 10 + info.oneSecProgress;
        return info.isForward ? value : -value;
    }
}

class OffsetViewInfo{
    int oneSecProgress = 0;
    int tenSecProgress = 0;
    boolean isForward = true;
}
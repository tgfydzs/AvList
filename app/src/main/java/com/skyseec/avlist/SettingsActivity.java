package com.skyseec.avlist;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.SwitchCompat;

/**
 * 软件设置页 —— 抽屉菜单入口和「运行日志」放一组。
 *
 * 页面按**分区**组织（见 activity_settings.xml，一块白底一个分区），现在只有
 * 「刮削」一个分区；以后加别的分区照那块复制一份就行。
 *
 * 目前三项都属于刮削，存储和默认值都在 {@link ScrapeSettings}。将来出现
 * 非刮削的设置，另开一个 Settings 工具类，别把这里撑成万能对象。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText etQueueTimeout;
    private EditText etQueueStep;
    private SwitchCompat swDisableOnFail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        etQueueTimeout = findViewById(R.id.etQueueTimeout);
        etQueueStep = findViewById(R.id.etQueueStep);
        swDisableOnFail = findViewById(R.id.swDisableOnFail);

        // 每行只显示"标题 + 一句摘要 + 控件"，详细说明**长按整行**弹窗看
        bindRowHelp(R.id.rowTimeout, "队列单卡片搜索上限",
                "一次尝试最多占用队列多久，单位秒。\n\n"
                        + "超时就算这次没搜到，把这张卡排到队列最后回头再试，别的卡先走 —— "
                        + "所以这个值直接决定队列的流动速度。\n\n"
                        + "默认 20，可填 3~300。");

        bindRowHelp(R.id.rowStep, "队列单卡片搜索步进",
                "每多一轮，等待窗口乘多少倍。\n\n"
                        + "填 1.0（默认）就是每一轮等一样久，也就是没有阶梯。"
                        + "只有某些源在后面几轮确实需要多等时才调大，比如 1.5 会让等待窗口变成 8 / 12 / 18 秒。\n\n"
                        + "正常刮削第 1 轮就搜到了，走不到后面几轮。");

        bindRowHelp(R.id.rowDisableOnFail, "刮削失败后关闭刮削",
                "一张卡连着 3 次都没搜到，就把它自己的刮削关掉：卡片上的「清除」会消失，"
                        + "缺图补刮也不会再把它扫进队列 —— 这张卡就不会再自己占着队列了。\n\n"
                        + "想再试就点卡片上的「刮削」，会重新打开。\n\n"
                        + "注意：网络整体不通时，一批卡可能一起失败然后被集体关掉；"
                        + "那种情况下在列表里全选、重新点一次「刮削」就能全部开回来。");

        loadCurrent();

        ((AppCompatButton) findViewById(R.id.btnSave)).setOnClickListener(v -> save());
        ((AppCompatButton) findViewById(R.id.btnReset)).setOnClickListener(v -> {
            ScrapeSettings.resetToDefault(this);
            loadCurrent();
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
            LogUtil.i("软件设置: 已恢复默认");
        });
    }

    /**
     * 长按一整行，弹窗显示这条设置的详细说明。
     *
     * 说明文字写在调用处，不放布局里 —— 布局里每行只留"标题 + 一句摘要 + 控件"，
     * 这样行高才压得住。
     *
     * 长按行上任何地方都行，**除了控件本身**：EditText 和 Switch 是可点的子 View，
     * 会自己把触摸吃掉。所以长按输入框出来的是文本选择菜单、长按开关没反应，
     * 想读说明就长按标题/摘要那一侧。
     */
    private void bindRowHelp(int rowId, String title, String message) {
        findViewById(rowId).setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("知道了", null)
                    .show();
            return true;
        });
    }

    /** 把当前值回显到控件。每次保存后也调一次，用来显示被夹过的值。 */
    private void loadCurrent() {
        etQueueTimeout.setText(String.valueOf(ScrapeSettings.getQueueCardTimeoutSec(this)));
        etQueueStep.setText(String.valueOf(ScrapeSettings.getQueueCardWaitStep(this)));
        swDisableOnFail.setChecked(ScrapeSettings.isDisableOnFailEnabled(this));
    }

    private void save() {
        String timeoutText = etQueueTimeout.getText().toString().trim();
        String stepText = etQueueStep.getText().toString().trim();

        int timeout;
        try {
            timeout = Integer.parseInt(timeoutText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "搜索上限要填整数秒数", Toast.LENGTH_SHORT).show();
            return;
        }

        float step;
        try {
            step = Float.parseFloat(stepText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "搜索步进要填小数", Toast.LENGTH_SHORT).show();
            return;
        }

        int finalTimeout = ScrapeSettings.clampTimeout(timeout);
        float finalStep = ScrapeSettings.clampStep(step);
        boolean disableOnFail = swDisableOnFail.isChecked();

        ScrapeSettings.setQueueCardTimeoutSec(this, finalTimeout);
        ScrapeSettings.setQueueCardWaitStep(this, finalStep);
        ScrapeSettings.setDisableOnFailEnabled(this, disableOnFail);
        // 回显夹过的值 —— 否则用户填了 0，看到输入框还写着 0，会以为存进去了
        loadCurrent();

        boolean clamped = finalTimeout != timeout || finalStep != step;
        Toast.makeText(this, clamped
                        ? "已保存（超出范围，收敛为 " + finalTimeout + " 秒 / " + finalStep + "）"
                        : "已保存",
                clamped ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();

        LogUtil.i("软件设置: 队列单卡片搜索上限=" + finalTimeout + "s 步进=" + finalStep
                + " 刮削失败后关闭刮削=" + disableOnFail
                + (clamped ? "（已收敛）" : ""));
    }
}

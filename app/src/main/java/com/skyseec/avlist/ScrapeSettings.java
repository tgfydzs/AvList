package com.skyseec.avlist;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 刮削的**全局**设置（每个刮削源自己的那些参数在 {@link ScrapeSource} 里，不要混）。
 *
 * 目前三项，都在「软件设置」页的「刮削」分区里改 —— 抽屉菜单的入口和「运行日志」放一组。
 *
 * 前两项是给"队列里的一个任务最多占多久"用的：一次刮削 = 一次尝试，尝试没出结果
 * 就退回队列尾部（轮转），所以它们直接决定队列的流动速度。第三项决定一张卡
 * 反复搜不到之后还要不要继续占队列。
 */
public class ScrapeSettings {

    private static final String PREFS = "scrape_prefs";
    private static final String KEY_QUEUE_CARD_TIMEOUT_SEC = "queue_card_timeout_sec";
    private static final String KEY_QUEUE_CARD_WAIT_STEP = "queue_card_wait_step";
    private static final String KEY_DISABLE_ON_FAIL = "disable_on_fail";

    /** 一次尝试的墙钟上限（秒）。超时即按"空结果"结束本次尝试，进入轮转。 */
    public static final int DEFAULT_QUEUE_CARD_TIMEOUT_SEC = 20;

    /** 每轮等待窗口的乘数。1.0 = 每轮等一样久，也就是**没有阶梯**。 */
    public static final float DEFAULT_QUEUE_CARD_WAIT_STEP = 1.0f;

    /**
     * 刮削彻底失败（尝试次数用尽）后，要不要把这卡自己的刮削关掉。
     *
     * 默认**开**：这正是这个开关的意义 —— 搜不到的卡别再每天被缺图补刮扫一遍、
     * 每次白烧一分钟。代价是网络整体不通时会把一批卡一起关掉，
     * 那种情况下用户在列表里全选重新点「刮削」就能全部开回来。
     */
    public static final boolean DEFAULT_DISABLE_ON_FAIL = true;

    // 合法区间：手滑填 0 或 99999 会把队列搞死，这里兜住
    public static final int MIN_QUEUE_CARD_TIMEOUT_SEC = 3;
    public static final int MAX_QUEUE_CARD_TIMEOUT_SEC = 300;
    public static final float MIN_QUEUE_CARD_WAIT_STEP = 0.1f;
    public static final float MAX_QUEUE_CARD_WAIT_STEP = 10.0f;

    private ScrapeSettings() {
        // 纯静态工具类
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 队列单卡片搜索上限（秒）—— 一次尝试最多占用队列多久。
     */
    public static int getQueueCardTimeoutSec(Context context) {
        return clampTimeout(prefs(context).getInt(
                KEY_QUEUE_CARD_TIMEOUT_SEC, DEFAULT_QUEUE_CARD_TIMEOUT_SEC));
    }

    public static void setQueueCardTimeoutSec(Context context, int seconds) {
        prefs(context).edit()
                .putInt(KEY_QUEUE_CARD_TIMEOUT_SEC, clampTimeout(seconds))
                .apply();
    }

    /**
     * 队列单卡片搜索步进 —— 每多一轮，等待窗口乘多少。
     * 1.0 就是不乘（每轮一样），正常刮削用不到这个旋钮。
     */
    public static float getQueueCardWaitStep(Context context) {
        return clampStep(prefs(context).getFloat(
                KEY_QUEUE_CARD_WAIT_STEP, DEFAULT_QUEUE_CARD_WAIT_STEP));
    }

    public static void setQueueCardWaitStep(Context context, float step) {
        prefs(context).edit()
                .putFloat(KEY_QUEUE_CARD_WAIT_STEP, clampStep(step))
                .apply();
    }

    /**
     * 刮削彻底失败后，要不要把这卡自己的刮削关掉（卡片上「清除」消失、不再自动补刮）。
     * 只影响"试过了但搜不到"，不影响用户主动取消 —— 取消不是失败。
     */
    public static boolean isDisableOnFailEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DISABLE_ON_FAIL, DEFAULT_DISABLE_ON_FAIL);
    }

    public static void setDisableOnFailEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DISABLE_ON_FAIL, enabled).apply();
    }

    public static void resetToDefault(Context context) {
        prefs(context).edit()
                .remove(KEY_QUEUE_CARD_TIMEOUT_SEC)
                .remove(KEY_QUEUE_CARD_WAIT_STEP)
                .remove(KEY_DISABLE_ON_FAIL)
                .apply();
    }

    public static int clampTimeout(int seconds) {
        if (seconds < MIN_QUEUE_CARD_TIMEOUT_SEC) return MIN_QUEUE_CARD_TIMEOUT_SEC;
        if (seconds > MAX_QUEUE_CARD_TIMEOUT_SEC) return MAX_QUEUE_CARD_TIMEOUT_SEC;
        return seconds;
    }

    public static float clampStep(float step) {
        if (Float.isNaN(step)) return DEFAULT_QUEUE_CARD_WAIT_STEP;
        if (step < MIN_QUEUE_CARD_WAIT_STEP) return MIN_QUEUE_CARD_WAIT_STEP;
        if (step > MAX_QUEUE_CARD_WAIT_STEP) return MAX_QUEUE_CARD_WAIT_STEP;
        return step;
    }
}

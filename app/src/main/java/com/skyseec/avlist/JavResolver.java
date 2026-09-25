package com.skyseec.avlist;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 单个刮削任务的编排：预热 → 搜索 → 等内容 → 提取。**一次调用只做一次尝试。**
 *
 * 本类不接触 WebViewClient，也不拼 JS —— 那些分别归 WebScrapeEngine 和 PageScripts。
 *
 * 重试**不在这里**：没出结果就把任务退回队列尾部，由 ScrapeService 轮转出一个
 * "每张卡都过一遍、没过的再轮一遍"的顺序。原来这里有一条 8/16/24 秒的翻倍等待阶梯
 * （空结果就 retryCount++ 重载搜索页），那是老版本的遗留 —— 它当初是为了"搜索页撞上
 * CF 挑战页时多等一会，挑战跑完内容自然就出来"，而现在预热已经把 CF 解决了。
 * 留着它的唯一效果就是让一张搜不到的卡堵住整个队列 66 秒。
 */
public class JavResolver {

    private final WebScrapeEngine engine;
    private final ScrapeSource source;
    private final PrewarmListener prewarmListener;
    private final ResolveCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 服务启动时已经预热过了，本任务直接跳到搜索页 */
    private final boolean skipPrewarm;

    /** 这是这张卡的第几次尝试（0 起）。等待窗口按它放大，日志里也用它。 */
    private final int attempt;

    /** 本次尝试的墙钟上限，来自「软件设置」的「队列单卡片搜索上限」 */
    private final long timeoutMs;

    /** 每轮等待窗口的乘数，来自「软件设置」的「队列单卡片搜索步进」。1.0 = 每轮一样 */
    private final float waitStep;

    /** 预热页停留时间：只给 CF 一个在后台种 cf_clearance 的机会，不等它跑完 */
    private static final int PREWARM_SETTLE_MS = 2_000;
    private static final int PREWARM_SETTLE_CHALLENGE_MS = 5_000;
    private static final int READY_POLL_MS = 200;
    private static final int POLL_LOG_INTERVAL_MS = 2_000;
    /** 轮询窗口在 waitSec 之上多给几秒，让"等够了"和"差一点"在日志上能分开 */
    private static final int POLL_GRACE_SEC = 3;

    private String scrapeKeyword;
    private boolean finished;

    private long overallStartTime;
    private long stageStartTime;
    private long pollStartTime;
    private long lastPollLogAt;

    public interface ResolveCallback {
        void onResult(String title, String imgUrl, String detailUrl);
    }

    /**
     * 本任务的预热真的过了（cf_clearance 已生效）。
     *
     * 回报给服务的原因：批量刮削时服务是一直活着的，第一个任务把验证过掉之后，
     * 后面几十个任务没有理由再各加载一遍站点首页 —— 那既慢，又是在给 CF 攒可疑行为。
     * 服务收到这个通知后置 prewarmDone，后续任务直接搜关键词。
     */
    public interface PrewarmListener {
        void onPrewarmPassed();
    }

    /**
     * @param attempt 这张卡的第几次尝试（0 起），由队列的 {@code ScrapeTask.attempts} 提供
     */
    public JavResolver(Context context, WebScrapeEngine engine, ScrapeSource source,
                       boolean skipPrewarm, int attempt,
                       PrewarmListener prewarmListener,
                       ResolveCallback callback) {
        this.engine = engine;
        this.source = source;
        this.skipPrewarm = skipPrewarm;
        this.attempt = Math.max(0, attempt);
        this.prewarmListener = prewarmListener;
        this.callback = callback;
        // 在构造时读一次设置：改完设置，下一个任务就生效，不用重启服务
        this.timeoutMs = ScrapeSettings.getQueueCardTimeoutSec(context) * 1000L;
        this.waitStep = ScrapeSettings.getQueueCardWaitStep(context);
    }

    // =====================================================================
    // 入口
    // =====================================================================
    public void startScrape(String keyword) {
        if (source == null) {
            LogUtil.w("【刮削】源为空，无法刮削");
            finish(null, null);
            return;
        }
        this.scrapeKeyword = keyword;
        this.finished = false;
        this.overallStartTime = System.currentTimeMillis();

        LogUtil.i("══════ 刮削开始（第" + (attempt + 1) + "次尝试）══════");
        LogUtil.i("【源】" + source.getName());
        LogUtil.i("【番号】" + keyword);
        LogUtil.i("【搜索URL】" + source.buildSearchUrl(keyword));
        LogUtil.i("【预算】上限=" + (timeoutMs / 1000) + "s 步进=" + waitStep);
        LogUtil.i("【预热】" + source.isNeedsPrewarm() + " 等待=" + source.getWaitSeconds() + "s");
        LogUtil.i("【标题XPath】" + source.getTitleXPath());
        LogUtil.i("【图片XPath】" + source.getImageXPath());

        // 必须在任何 load 之前设好
        String customUa = source.getUserAgent();
        engine.applyUserAgent(customUa);
        LogUtil.i("【UA】" + (customUa.isEmpty() ? "(设备默认 UA)" : customUa));

        armTimeout();

        if (source.isNeedsPrewarm() && !skipPrewarm) {
            prewarm();
        } else {
            if (skipPrewarm) {
                LogUtil.i("【预热】验证已过，直接搜索，跳过预热");
            }
            loadSearchPage();
        }
    }

    /** 外部中止（渲染进程死亡等）。不触发回调，由调用方决定后续。 */
    public void cancel() {
        finished = true;
        cancelAll();
    }

    // =====================================================================
    // 预热（只做一次短暂停留，不等验证跑完）
    // =====================================================================
    private void prewarm() {
        String prewarmUrl = source.getPrewarmUrl();
        if (prewarmUrl.isEmpty()) {
            LogUtil.w("【预热】预热URL为空，跳过");
            loadSearchPage();
            return;
        }

        LogUtil.i("【预热】开始 " + prewarmUrl);
        stageStartTime = System.currentTimeMillis();
        engine.loadPrewarm(prewarmUrl, url -> {
            LogUtil.i("【预热·onPageFinished】+" + elapsed() + "ms url=" + url);
            if (!url.equals(prewarmUrl)) {
                LogUtil.i("【预热】被重定向: " + prewarmUrl + " → " + url);
            }
            settleAfterPrewarm();
        });
    }

    /**
     * 预热页停一小会儿就去搜索页 —— **不在这里等验证跑完**。
     *
     * 这是原版（a3ba0f5「实现missav的刮削」）的做法：onPageFinished 后看一眼是不是
     * 挑战页，是就多停几秒，然后直奔搜索页。真正等出内容的地方是搜索页那段 XPath
     * 轮询 —— 它等的是"要的东西出现了"，不是"cf_clearance 出现了"；挑战跑完了
     * XPath 自然就命中。
     *
     * 这里曾经挂着"拿不到内容就原地重试两轮"的兜底。那两轮现在搬去了队列层
     * （ScrapeService 的轮转），本类一次调用只做一次尝试 —— 见类注释。
     *
     * 我一度把它改成"轮询 cf_clearance 最多 45 秒、超时再重载预热页又 45 秒"。
     * 真机日志的结论是那段等待纯属负收益：90 秒里 cookie=(无)、body 一个字节没动，
     * 队列被堵死。别再加回去了。
     *
     * hasClearance 的早退保留：它真的命中时能省掉这几秒。
     */
    private void settleAfterPrewarm() {
        if (ChallengeDetector.hasClearance(source.getPrewarmUrl())) {
            LogUtil.i("【预热】cf_clearance 已种下，直接开始搜索");
            notifyPrewarmPassed();
            handler.postDelayed(this::loadSearchPage, 500);
            return;
        }

        engine.eval(PageScripts.detectChallengeJs(), raw -> {
            if (finished) return;
            String json = PageScripts.decode(raw);
            boolean challenge = ChallengeDetector.isChallenge(json);
            // 把"在等什么"一次说全：页面状态 + 手上已有哪些 cookie
            LogUtil.i("【预热·页面状态】" + ChallengeDetector.describe(json)
                    + " cookie=" + ChallengeDetector.cookieNames(source.getPrewarmUrl()));
            if (challenge) {
                // 挑战脚本的地址单独打一行 —— 日志里那个 401 是不是它，靠这个对
                LogUtil.i("【预热】挑战脚本: " + ChallengeDetector.scriptSrc(json));
            } else {
                // 页面干净 = 验证真过了。这比"cookie 在 jar 里"可信 ——
                // 换过 UA 的旧 cf_clearance 还躺着但已经失效（见 ScrapeService.pollWarmUp）
                notifyPrewarmPassed();
            }

            int settle = challenge ? PREWARM_SETTLE_CHALLENGE_MS : PREWARM_SETTLE_MS;
            LogUtil.i("【预热】停 " + (settle / 1000) + " 秒后去搜索页"
                    + (challenge ? "（预热页不等它跑完，等的是搜索页的内容）" : ""));
            handler.postDelayed(this::loadSearchPage, settle);
        });
    }

    private void notifyPrewarmPassed() {
        if (prewarmListener != null) prewarmListener.onPrewarmPassed();
    }

    // =====================================================================
    // 搜索页
    // =====================================================================
    private void loadSearchPage() {
        if (finished) return;
        stageStartTime = System.currentTimeMillis();
        String url = source.buildSearchUrl(scrapeKeyword);
        LogUtil.i("── 搜索（第" + (attempt + 1) + "次尝试）──");
        LogUtil.i("【搜索·URL】" + url);

        engine.load(url, this::onSearchLoaded);
    }

    private void onSearchLoaded(String url) {
        LogUtil.i("【搜索·onPageFinished】+" + elapsed() + "ms url=" + url + "（含重定向则为最终地址）");
        startContentPolling();
    }

    /**
     * 就绪检测由 Java 驱动：每次轮询直接求值一次 XPath 提取。
     *
     * 以前靠页面里的 setInterval 置一个标志位，而页面不可见时 Chromium 会把
     * 定时器节流（先降到 1 次/秒，隐藏满 5 分钟降到 1 次/分钟），后台就几乎不动。
     * evaluateJavascript 是 IPC 求值、不是页面定时器，不受这个限制。
     */
    private void startContentPolling() {
        int baseWait = source.getWaitSeconds();
        if (baseWait < 1) baseWait = 3;

        // 等待窗口 = 源自己的 waitSeconds × 步进^轮次。
        // 步进默认 1.0，也就是每一轮等一样久 —— 原来那条 base*(retry+1) 的翻倍阶梯已经删了。
        long waitSec = Math.round(baseWait * Math.pow(waitStep, attempt));
        long wantedMs = (waitSec + POLL_GRACE_SEC) * 1000L;

        // 夹到本次尝试剩下的预算里。不夹的话超时会先触发、handleExtract 根本轮不到跑，
        // 日志里就只剩一句"超时"，看不出是轮询等够了还是被上限砍掉了
        long remainingMs = timeoutMs - (System.currentTimeMillis() - overallStartTime);
        final long maxWaitMs = Math.max(READY_POLL_MS, Math.min(wantedMs, remainingMs));
        final boolean clamped = maxWaitMs < wantedMs;

        pollStartTime = System.currentTimeMillis();
        lastPollLogAt = 0;
        LogUtil.i("【搜索·等待】最长" + (maxWaitMs / 1000) + "s (base=" + baseWait
                + "s step=" + waitStep + " 第" + (attempt + 1) + "次尝试"
                + (clamped ? "，被上限" + (timeoutMs / 1000) + "s砍到" : "") + ")");

        final String extractJs = PageScripts.extractJs(source.getTitleXPath(), source.getImageXPath());

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                engine.eval(extractJs, raw -> {
                    if (finished) return;
                    long waited = System.currentTimeMillis() - pollStartTime;
                    PageScripts.ExtractResult r = PageScripts.ExtractResult.parse(PageScripts.decode(raw));

                    // 每 2 秒打一次诊断，替代原来的轮询日志 + XPath 命中日志
                    if (waited - lastPollLogAt >= POLL_LOG_INTERVAL_MS) {
                        lastPollLogAt = waited;
                        LogUtil.i("【轮询·" + (waited / 1000) + "s】"
                                + (r == null ? "求值无返回" : r.describe()));
                    }

                    if (r != null && !r.isEmpty()) {
                        LogUtil.i("【搜索·就绪】" + waited + "ms");
                        handleExtract(r);
                        return;
                    }
                    if (waited >= maxWaitMs) {
                        LogUtil.w("【搜索·轮询超时】" + waited + "ms 仍未取到内容");
                        handleExtract(r);
                        return;
                    }
                    handler.postDelayed(this, READY_POLL_MS);
                });
            }
        }, READY_POLL_MS);
    }

    // =====================================================================
    // 结果
    // =====================================================================
    private void handleExtract(PageScripts.ExtractResult r) {
        String title = r == null ? "" : r.title;
        String img = r == null ? "" : r.img;

        LogUtil.i("【解析·结果】title=[" + title + "] img=[" + (img.isEmpty() ? "(空)" : img) + "]");

        if (title.isEmpty() && img.isEmpty()) {
            // 不在这里重试 —— 空结果原样回报，由 ScrapeService 把这张卡退回队列尾部，
            // 别的卡先走。这就是"每张卡都过一遍、没过的再轮一遍"的轮转。
            LogUtil.w("【解析】空。本次尝试到此为止，交回队列排到后面再轮");
        }

        LogUtil.i("══════ 刮削结束 总耗时=" + (System.currentTimeMillis() - overallStartTime) + "ms ══════");
        finish(title, img);
    }

    // =====================================================================
    // 收尾
    // =====================================================================
    private void armTimeout() {
        handler.postDelayed(() -> {
            if (finished) return;
            LogUtil.e("【超时】本次尝试超过" + (timeoutMs / 1000) + "秒，按空结果结束", null);
            finish(null, null);
        }, timeoutMs);
    }

    private void finish(String title, String img) {
        if (finished) return;
        finished = true;
        String url = engine.getUrl();
        cancelAll();
        callback.onResult(title, img, url);
    }

    private void cancelAll() {
        handler.removeCallbacksAndMessages(null);
        engine.stop();
    }

    private long elapsed() {
        return System.currentTimeMillis() - stageStartTime;
    }
}

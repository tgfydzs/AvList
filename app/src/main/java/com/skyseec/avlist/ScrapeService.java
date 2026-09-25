package com.skyseec.avlist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台刮削前台服务。
 * 独立于 Activity 生命周期，息屏/切后台继续工作。
 * 使用前台通知保活并绕过 Doze 限制。
 */
public class ScrapeService extends Service {

    // Intent actions
    public static final String ACTION_ENQUEUE = "com.skyseec.avlist.action.ENQUEUE";
    public static final String ACTION_CANCEL_ALL = "com.skyseec.avlist.action.CANCEL_ALL";
    public static final String ACTION_WARMUP = "com.skyseec.avlist.action.WARMUP";

    // Broadcast actions
    public static final String BROADCAST_PROGRESS = "com.skyseec.avlist.broadcast.SCRAPE_PROGRESS";

    // Extras
    public static final String EXTRA_TASKS_JSON = "tasks_json";
    public static final String EXTRA_QUEUE_JSON = "queue_json";

    private static final String CHANNEL_ID = "scrape_service";
    private static final int NOTIFICATION_ID = 1001;
    private static final String QUEUE_PREFS = "scrape_queue_prefs";
    private static final String KEY_QUEUE = "queue";
    private static final long IDLE_STOP_DELAY_MS = 5_000;

    private WebScrapeEngine engine;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ScrapeTask> taskList = new ArrayList<>();
    private boolean processing;
    private Runnable idleStopRunnable;
    private PowerManager.WakeLock wakeLock;

    /** 当前在跑的任务编排，渲染进程死亡时需要它来中止 */
    private JavResolver currentResolver;

    /** 浏览器内核崩溃后重建的提示，随下一次任务完成清除 */
    private String statusBanner;

    /**
     * 一张卡总共能被执行几次。到顶之后不再回队，判 FAILED。
     *
     * 计数在 {@link ScrapeTask#getAttempts()}，两种重跑原因共用：没搜到结果被轮转回来、
     * 内核崩溃后重建。所以"3 次"指的是**执行次数**，不是"每种原因各 3 次"。
     */
    private static final int MAX_TASK_ATTEMPTS = 3;

    /**
     * 进 app 预热：占着 WebView 提前把 cf_clearance 跑下来，等队列来用。
     *
     * 这里**没有"等验证过去"的短超时，也不重载预热页**。原版从来不等人机验证跑完
     * —— 预热页停 2-5 秒就去搜索页，真正等出内容的是搜索页的 XPath 轮询加重试阶梯。
     * 我一度在这里等 45 秒、超时再重载一轮，真机日志的结论是那段等待纯属负收益：
     * 90 秒里 cookie=(无)、body 一个字节没动，队列还被堵死。
     *
     * 现在它的定位就一句话：队列空的时候占着 WebView 等，队列一来立刻让位。
     * 所以下面这个上限只是兜底，正常轮不到它（队列来了让位、队列一直空则被空闲停止收掉）。
     */
    private static final long WARMUP_MAX_WAIT_MS = 120_000;
    private static final long WARMUP_POLL_MS = 2_000;

    /** 预热正在占用 WebView，队列里的任务来了就让位 */
    private boolean warmingUp;
    /** 预热页地址，用来查 cf_clearance cookie */
    private String warmUpUrl;
    /**
     * 预热已经把"过验证"这步做掉了（本次服务运行期内有效）。
     * 有了它，任务可以直接跳到搜索页，不用自己再预热一遍 ——
     * 否则预热只赚到一个 cookie，那趟预热页加载等于白跑。
     */
    private boolean prewarmDone;

    // =====================================================================
    // 便捷静态方法
    // =====================================================================

    /**
     * 从 Activity 发起刮削请求
     */
    public static void enqueue(Context context, ScrapeTask task) {
        List<ScrapeTask> list = new ArrayList<>();
        list.add(task);
        enqueueAll(context, list);
    }

    /**
     * 批量入队
     */
    public static void enqueueAll(Context context, List<ScrapeTask> tasks) {
        Intent intent = new Intent(context, ScrapeService.class);
        intent.setAction(ACTION_ENQUEUE);
        intent.putExtra(EXTRA_TASKS_JSON, new Gson().toJson(tasks));
        context.startService(intent);
    }

    /**
     * 取消所有待处理任务
     */
    public static void cancelAll(Context context) {
        Intent intent = new Intent(context, ScrapeService.class);
        intent.setAction(ACTION_CANCEL_ALL);
        context.startService(intent);
    }

    /**
     * 进 app 就先把人机验证跑掉，把 cf_clearance 种下。
     *
     * 否则服务启动后的第一张卡要现场从零过验证：WebView profile 全空，
     * 挑战磨 45 秒都过不去，实测那一张要 60 多秒，之后才恢复正常。
     */
    public static void warmUp(Context context) {
        Intent intent = new Intent(context, ScrapeService.class);
        intent.setAction(ACTION_WARMUP);
        context.startService(intent);
    }

    // =====================================================================
    // Service 生命周期
    // =====================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtil.i("【ScrapeService】onCreate");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("准备中...", 0, 0));

        // 获取 WakeLock 防止 CPU 休眠
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvList:ScrapeWakeLock");
            wakeLock.setReferenceCounted(false);
        }

        // 创建刮削专用 WebView（由引擎独占，业务侧不再直接碰 WebViewClient）
        engine = new WebScrapeEngine(this);
        engine.setOnRendererGoneListener(this::onRendererGone);

        // 恢复上次未完成的队列
        loadQueue();
        LogUtil.i("【ScrapeService】恢复队列: " + taskList.size() + " 个任务");

        // 开始处理
        processNext();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_ENQUEUE:
                    handleEnqueue(intent);
                    break;
                case ACTION_CANCEL_ALL:
                    handleCancelAll();
                    break;
                case ACTION_WARMUP:
                    handleWarmUp();
                    break;
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不需要绑定
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LogUtil.i("【ScrapeService】onDestroy");

        // 标记运行中任务为失败
        for (ScrapeTask t : taskList) {
            if (t.getStatus() == ScrapeTask.Status.RUNNING) {
                t.setStatus(ScrapeTask.Status.FAILED);
                t.setErrorMessage("服务被终止");
            }
        }
        saveQueue();

        warmingUp = false;
        if (currentResolver != null) {
            currentResolver.cancel();
            currentResolver = null;
        }
        if (engine != null) {
            engine.destroy();
        }
        cancelIdleStop();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // =====================================================================
    // 命令处理
    // =====================================================================

    private void handleEnqueue(Intent intent) {
        String json = intent.getStringExtra(EXTRA_TASKS_JSON);
        if (json == null || json.isEmpty()) return;

        try {
            Type type = new TypeToken<List<ScrapeTask>>() {}.getType();
            List<ScrapeTask> newTasks = gson.fromJson(json, type);
            if (newTasks == null || newTasks.isEmpty()) return;

            // 去重：跳过已存在的 cardId（同一张卡不重复刮削）
            int added = 0;
            for (ScrapeTask newTask : newTasks) {
                boolean exists = false;
                for (ScrapeTask existing : taskList) {
                    if (existing.getCardId().equals(newTask.getCardId())
                            && !existing.isFinished()) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    // 确保 ID 唯一
                    if (newTask.getId() == null || newTask.getId().isEmpty()) {
                        newTask.setId(java.util.UUID.randomUUID().toString());
                    }
                    newTask.setStatus(ScrapeTask.Status.PENDING);
                    newTask.setCreatedAt(System.currentTimeMillis());
                    taskList.add(newTask);
                    added++;
                }
            }

            LogUtil.i("【ScrapeService】入队 " + added + " 个任务（跳过 " + (newTasks.size() - added) + " 个重复）");
            saveQueue();
            updateNotification();
            broadcastProgress();

            // 尝试处理
            processNext();

        } catch (Exception e) {
            LogUtil.e("【ScrapeService】解析入队任务失败", e);
        }
    }

    private void handleCancelAll() {
        int removed = 0;
        ScrapeTask runningCancelled = null;
        for (ScrapeTask t : taskList) {
            if (t.getStatus() == ScrapeTask.Status.PENDING) {
                t.setStatus(ScrapeTask.Status.FAILED);
                t.setErrorMessage("用户取消");
                removed++;
            } else if (t.getStatus() == ScrapeTask.Status.RUNNING) {
                // 正在跑的那个也必须一起停。只取消 PENDING 的话，它在跑的这次尝试
                // 结束时会以"空结果"被轮转逻辑判为"该回队" —— 于是刚被取消的任务
                // 换个位置又活了，用户点一次「取消全部」要等 3 轮才真正生效。
                t.setStatus(ScrapeTask.Status.FAILED);
                t.setErrorMessage("用户取消");
                runningCancelled = t;
                removed++;
            }
        }

        // cancel() 不触发结果回调（见 JavResolver.cancel），所以这里的收尾要自己做：
        // 否则 processing 会永远留在 true，队列再也起不来
        if (runningCancelled != null) {
            if (currentResolver != null) {
                currentResolver.cancel();
                currentResolver = null;
            }
            processing = false;
            releaseWakeLock();
            LogUtil.i("【ScrapeService】已中止正在执行的任务: " + runningCancelled.getCode());
        }

        LogUtil.i("【ScrapeService】取消 " + removed + " 个任务");
        saveQueue();
        updateNotification();
        broadcastProgress();
        scheduleIdleStop();
    }

    // =====================================================================
    // 队列处理
    // =====================================================================

    private void processNext() {
        if (processing) return;
        // 有任务来了，预热立刻让位。它的定位是"队列空的时候占着 WebView 等验证"，
        // 不是队列的必经前置步骤 —— 让任务干等它，就是拿队列去换一个可能根本换不到的东西。
        if (warmingUp) {
            LogUtil.i("【ScrapeService】队列来任务了，预热让位（任务自己走预热+搜索）");
            cancelWarmUp();
        }

        // 取第一个 PENDING 任务。顺序就是轮转顺序：回队时是挪到末尾的，
        // 所以"每张卡都过一遍、没过的再轮一遍"直接由这个取法实现。
        ScrapeTask nextTask = null;
        boolean demoted = false;
        for (ScrapeTask t : taskList) {
            if (t.getStatus() != ScrapeTask.Status.PENDING) continue;
            // 上限在派发时也要兜一次：loadQueue() 会把 RUNNING 还原成 PENDING 且保留
            // attempts，杀进程时正好卡在到顶的那个任务，重启后会白跑一次；反复杀就没上限了
            if (t.getAttempts() >= MAX_TASK_ATTEMPTS) {
                t.setStatus(ScrapeTask.Status.FAILED);
                t.setErrorMessage("已达到最大尝试次数");
                LogUtil.w("【ScrapeService】任务已达尝试上限，不再派发: " + t.getCode());
                disableScrapeOnFailure(t.getCardId());
                demoted = true;
                continue;
            }
            nextTask = t;
            break;
        }

        if (demoted) {
            saveQueue();
            updateNotification();
            broadcastProgress();
        }

        if (nextTask == null) {
            // 没有待处理任务，准备停止
            LogUtil.i("【ScrapeService】队列空闲");
            scheduleIdleStop();
            return;
        }

        cancelIdleStop();
        processing = true;

        // 查找刮削源
        final ScrapeSource source = findSource(nextTask.getScrapeSourceId());
        if (source == null) {
            LogUtil.w("【ScrapeService】找不到刮削源: " + nextTask.getScrapeSourceId());
            nextTask.setStatus(ScrapeTask.Status.FAILED);
            nextTask.setErrorMessage("刮削源不存在");
            processing = false;
            saveQueue();
            updateNotification();
            broadcastProgress();
            processNext();
            return;
        }

        final ScrapeTask task = nextTask;
        task.setStatus(ScrapeTask.Status.RUNNING);
        saveQueue();
        updateNotification();
        broadcastProgress();

        LogUtil.i("【ScrapeService】开始刮削: code=" + task.getCode() + " cardId=" + task.getCardId());

        // 获取 WakeLock
        acquireWakeLock();

        // 启动预热做过的活，任务不用再做一遍
        final boolean skipPrewarm = prewarmDone;
        final int attempt = task.getAttempts();   // 0 起，决定这次的等待窗口
        JavResolver resolver = new JavResolver(
                this,
                engine,
                source,
                skipPrewarm,
                attempt,
                // 本任务把验证过掉了 —— 记下来，同一批后面的任务直接搜关键词。
                // 服务在批量刮削期间一直活着，这个标志能省掉每个任务一次首页加载。
                () -> prewarmDone = true,
                (scrapeTitle, imgUrl, detailUrl) -> {
                    // 在回调线程中处理结果（可能是主线程）
                    LogUtil.i("【ScrapeService】刮削回调: title=" + scrapeTitle + " img=" + (imgUrl != null ? imgUrl.substring(0, Math.min(40, imgUrl.length())) : "null"));

                    boolean success = scrapeTitle != null && !scrapeTitle.isEmpty()
                            || imgUrl != null && !imgUrl.isEmpty();

                    // 结果回来时这张卡可能已经被「清除」了 —— 清除会把 scrapeEnabled 置 false，
                    // 意思就是"这张卡我不要了"。**两条路径都要判**：成功路径要拦住写回，
                    // 空结果路径要拦住"回队再试"。
                    boolean cardWanted = isCardScrapeEnabled(task.getCardId());

                    // 空结果（这次尝试没搜到）是唯一值得重试的失败。别的重试都不解决问题：
                    // 卡片被关掉是用户的决定，结果被丢弃是卡没了，内核反复崩溃是环境问题。
                    final boolean retryable = !success && cardWanted;

                    if (success && cardWanted) {
                        // 1. 下载图片
                        if (imgUrl != null && !imgUrl.isEmpty()) {
                            String resolvedUrl = source.resolveImageUrl(imgUrl);
                            downloadImage(task.getCardId(), resolvedUrl, source);
                        }

                        // 2. 更新 CardItem 数据
                        // 走 mainHandler：与 downloadImage 里的尺寸写入串行化，
                        // 两次读改写同一份 JSON 才不会互相覆盖
                        final String resultTitle = scrapeTitle;
                        final String resultImg = imgUrl;
                        mainHandler.post(() -> updateCardData(task.getCardId(), resultTitle, resultImg));

                        task.setTitle(scrapeTitle);
                        task.setImgUrl(imgUrl);
                        task.setStatus(ScrapeTask.Status.COMPLETED);
                        statusBanner = null;   // 有任务跑通，内核重启的提示可以撤了
                        LogUtil.i("【ScrapeService】刮削成功: " + task.getCode());
                    } else if (success) {
                        // 有结果但卡已经被清除了：图片一个字节都别下 —— 文件一旦落到
                        // filesDir/images/，CardAdapter 是"本地文件优先"（localFile.exists()
                        // 就显示），卡片照样把图显示出来。光在 updateCardData 里跳过写回没用。
                        task.setStatus(ScrapeTask.Status.FAILED);
                        task.setErrorMessage("卡片已关闭刮削");
                        LogUtil.i("【ScrapeService】结果已丢弃(卡已关闭刮削): " + task.getCode());
                    } else {
                        // 空结果。真正的处置在主线程块里 —— 回队要动 taskList 的结构。
                        LogUtil.i("【ScrapeService】本次尝试无结果: " + task.getCode()
                                + (cardWanted ? "，稍后轮回来再试" : "（卡已关闭刮削，不再重试）"));

                        // cf_clearance 可能真的过期了。但只在"这次是跳过预热直接搜的"时候
                        // 才作废这个结论：如果本任务自己预热过还是空，那问题不在 clearance，
                        // 作废只会让后面每个任务都重新预热一遍（"反复冲击人机验证"）。
                        if (skipPrewarm) {
                            prewarmDone = false;
                        }
                    }

                    // 清理并继续
                    mainHandler.post(() -> {
                        processing = false;
                        currentResolver = null;

                        if (retryable) {
                            // 回队轮转：挪到 taskList 末尾。processNext() 取的是第一个
                            // PENDING，所以"每张卡都过一遍、没过的再轮一遍"这个顺序
                            // 自然就形成了，不需要额外的"第几轮"计数。
                            //
                            // ⚠️ 顺序是硬要求：必须在 processNext() 之前做。
                            // 反过来（先 processNext 再回队）会看到任务是 FAILED、找不到
                            // PENDING → 调 scheduleIdleStop()；而任务翻回 PENDING 之后
                            // 没有任何东西会再触发派发 —— 队列永久卡死，外加一个
                            // 永远不退出的前台服务（idleStop 的重检把 PENDING 算作"有活"）。
                            //
                            // 结构改动也只能在主线程做：buildNotification / scheduleIdleStop /
                            // processNext / handleEnqueue / saveQueue 都在遍历这个 list。
                            task.setAttempts(task.getAttempts() + 1);
                            if (task.getAttempts() >= MAX_TASK_ATTEMPTS) {
                                task.setStatus(ScrapeTask.Status.FAILED);
                                task.setErrorMessage("无刮削结果");
                                LogUtil.w("【ScrapeService】已尝试 " + task.getAttempts()
                                        + " 次仍无结果，放弃: " + task.getCode());
                                // 按设置把这卡自己的刮削关掉，别再让它每天被扫一遍
                                disableScrapeOnFailure(task.getCardId());
                            } else {
                                task.setStatus(ScrapeTask.Status.PENDING);
                                taskList.remove(task);
                                taskList.add(task);
                                // 原版每次重试前清一次 HTTP 缓存，保持一致
                                engine.clearCache();
                                LogUtil.i("【ScrapeService】退回队列尾部（第 " + task.getAttempts()
                                        + " 次重试）: " + task.getCode());
                            }
                        }

                        // 成功失败都记一笔，缺图补刮才不会反复重试搜不到的卡
                        markCardScraped(task.getCardId());
                        saveQueue();
                        updateNotification();
                        broadcastProgress();
                        releaseWakeLock();
                        processNext();
                    });
                });

        currentResolver = resolver;
        resolver.startScrape(task.getCode());
    }

    // =====================================================================
    // 进 app 预热（提前把 cf_clearance 跑下来）
    // =====================================================================

    private void handleWarmUp() {
        if (warmingUp || processing || currentResolver != null) {
            // 队列已经在跑了（通常是上次没刮完的任务被恢复）。这里不去抢 WebView：
            // profile 是冷的，谁先跑都得付那一次冷启动成本，抢过来只是把成本换个地方花
            LogUtil.i("【预热】跳过：队列已在刮削，冷启动成本由当前任务承担");
            return;
        }
        ScrapeSource source = ScrapeSource.getDefault(this);
        if (source == null || !source.isNeedsPrewarm()) return;
        String url = source.getPrewarmUrl();
        if (url.isEmpty()) return;

        warmingUp = true;
        warmUpUrl = url;
        prewarmDone = false;       // 重新预热，之前那次的结论作废
        cancelIdleStop();          // 预热期间别被空闲停止干掉
        updateNotification("正在通过人机验证…");
        engine.applyUserAgent(source.getUserAgent());

        LogUtil.i("【预热】应用启动预热: " + url
                + "，现有 cookie=" + ChallengeDetector.cookieNames(url));
        engine.loadPrewarm(url, loaded -> pollWarmUp(System.currentTimeMillis()));
    }

    /**
     * 等在预热页上，直到 cf_clearance 种下、或者队列来把 WebView 要走。
     *
     * 期间**不重载、不清缓存**：挑战脚本自己会跑，重载只会把跑了一半的挑战打断，
     * 反复冲击人机验证本身也只会让情况更糟。
     */
    private void pollWarmUp(long startAt) {
        if (!warmingUp) return;

        // cookie 才是真正的判据：页面可能还挂着「请稍候…」而 clearance 早就种下了
        // ⚠️ 顺序有讲究：**先看页面，再看 cookie**。反过来会踩这个坑 ——
        // cf_clearance 是绑 UA 的，换过 UA 之后旧 cookie 还躺在 jar 里但已经失效，
        // CF 照旧给 403 挑战页。这时候"有 cookie 就算过了"会让预热 1 毫秒宣布成功、
        // 然后队列空转，而我们其实连挑战页都没过（真机日志里就是这么演的）。
        //
        // 页面还是挑战页 ⇒ 手上这个 cookie 没起作用，无论它在不在。反过来，
        // 如果 cookie 真的有效，CF 的挑战页自己会跳转到目标地址，等页面就行。
        engine.eval(PageScripts.detectChallengeJs(), raw -> {
            if (!warmingUp) return;
            String json = PageScripts.decode(raw);
            long waited = System.currentTimeMillis() - startAt;
            boolean challenge = ChallengeDetector.isChallenge(json);
            // 把"在等什么"一次说全：页面状态 + 挑战类型 + 手上已有哪些 cookie
            LogUtil.i("【预热】已等" + (waited / 1000) + "s " + ChallengeDetector.describe(json)
                    + " cookie=" + ChallengeDetector.cookieNames(warmUpUrl));
            if (waited < 1000) {
                // 挑战脚本的地址单独打一行 —— 日志里那个 401 是不是它，靠这个对
                LogUtil.i("【预热】挑战脚本: " + ChallengeDetector.scriptSrc(json));
                // 指纹不在这里打了：WebScrapeEngine.onPageFinished 已经打了一份。
                // （之前以为那边没生效才在这里补了一份，结果是装了旧包。）
            }
            if (waited < 1000 && ChallengeDetector.needsInteraction(json)) {
                LogUtil.w("【预热】cType 显示这个挑战需要人工交互，自动流程等不到，交还 WebView");
                finishWarmUp();
                return;
            }

            if (!challenge) {
                LogUtil.i("【预热】通过，耗时" + waited + "ms"
                        + (ChallengeDetector.hasClearance(warmUpUrl) ? "（cookie 也在）" : "")
                        + "。后面的任务可以直接搜索，不用再预热");
                prewarmDone = true;
                finishWarmUp();
                return;
            }
            if (waited >= WARMUP_MAX_WAIT_MS) {
                // 不是"失败"：任务自己走预热+搜索+重试那套照样能成，这里只是不再占着 WebView
                LogUtil.w("【预热】等了" + (WARMUP_MAX_WAIT_MS / 1000)
                        + "s 页面仍是挑战页，交还 WebView 给队列");
                finishWarmUp();
                return;
            }
            mainHandler.postDelayed(() -> pollWarmUp(startAt), WARMUP_POLL_MS);
        });
    }

    /** 让位：预热还没出结果，WebView 就被队列要走了 */
    private void cancelWarmUp() {
        if (!warmingUp) return;
        warmingUp = false;
        prewarmDone = false;   // 没等到结论，任务得自己预热一遍
    }

    private void finishWarmUp() {
        warmingUp = false;
        // 队列空就照常走空闲停止，服务自己会退掉
        processNext();
    }

    // =====================================================================
    // 渲染进程死亡恢复
    // =====================================================================

    /**
     * WebView 渲染进程被系统回收（后台内存紧张时常见）。
     *
     * 不处理的话所有 evaluateJavascript 回调都不会再来，而 processing 只在
     * 刮削回调里复位 —— 整个队列会一直停摆到 90 秒总超时，任务记 FAILED，
     * 而且没有任何提示。这里改成：把当前任务退回队列、重建 WebView、继续跑。
     */
    private void onRendererGone(boolean crashed) {
        LogUtil.e("【ScrapeService】浏览器内核终止 crashed=" + crashed + "，准备重建", null);

        ScrapeTask running = null;
        for (ScrapeTask t : taskList) {
            if (t.getStatus() == ScrapeTask.Status.RUNNING) {
                running = t;
                break;
            }
        }

        boolean requeued = false;
        if (running != null) {
            running.setAttempts(running.getAttempts() + 1);
            if (running.getAttempts() >= MAX_TASK_ATTEMPTS) {
                running.setStatus(ScrapeTask.Status.FAILED);
                running.setErrorMessage("浏览器内核反复崩溃");
                LogUtil.w("【ScrapeService】内核反复崩溃，放弃该任务: " + running.getCode());
                // 反复崩溃多半是这张卡所在页面有问题，同样按设置关掉，别一直占着队列
                disableScrapeOnFailure(running.getCardId());
            } else {
                running.setStatus(ScrapeTask.Status.PENDING);
                // 和"没搜到结果"走同一套回队方式：挪到队尾轮转，而不是原地重来
                // —— 原地的话崩溃的卡会一直卡在队首，堵住后面所有的卡
                taskList.remove(running);
                taskList.add(running);
                requeued = true;
                LogUtil.i("【ScrapeService】任务退回队列尾部（第" + running.getAttempts() + "次）: " + running.getCode());
            }
        }

        // 内核没了，预热那条轮询也永远不会回来，得手动收尾，否则队列会一直卡在等待
        warmingUp = false;
        prewarmDone = false;

        if (currentResolver != null) {
            currentResolver.cancel();
            currentResolver = null;
        }
        processing = false;
        // 只有真的打断了任务才提示；空闲时内核被回收不必惊动用户
        if (requeued) {
            statusBanner = "浏览器内核已重启";
        }
        saveQueue();
        updateNotification();
        broadcastProgress();

        // 等这一轮 WebView 拆干净再重建，别在回调里直接销毁
        mainHandler.postDelayed(() -> {
            if (engine != null) engine.rebuild();
            processNext();
        }, 300);
    }

    // =====================================================================
    // 卡片数据更新
    // =====================================================================

    /**
     * 直接读写 SharedPreferences 更新卡片数据，避免与 Activity 的 originList 竞态。
     */
    private void updateCardData(String cardId, String title, String imgUrl) {
        try {
            SharedPreferences prefs = getSharedPreferences("app_cache", MODE_PRIVATE);
            String json = prefs.getString("data", "[]");
            Type type = new TypeToken<List<CardItem>>() {}.getType();
            List<CardItem> list = gson.fromJson(json, type);
            if (list == null) return;

            for (CardItem item : list) {
                if (cardId.equals(item.getId())) {
                    // 尊重 userEdited 标记
                    if (item.isUserEdited()) {
                        LogUtil.i("【ScrapeService】跳过 userEdited 卡片: " + item.getCode());
                        return;
                    }
                    if (title != null && !title.isEmpty()) {
                        item.setTitle(title);
                    }
                    if (imgUrl != null && !imgUrl.isEmpty()) {
                        item.setImageUrl(imgUrl);
                    }
                    break;
                }
            }

            // 写回
            prefs.edit().putString("data", gson.toJson(list)).apply();
            LogUtil.i("【ScrapeService】卡片已更新: " + cardId);

        } catch (Exception e) {
            LogUtil.e("【ScrapeService】更新卡片数据失败", e);
        }
    }

    /**
     * 这张卡现在还要不要刮（scrapeEnabled 的最新值）。
     *
     * 不在入队时判断、而在结果回来时判断，是因为用户可能在任务排队或正在执行期间
     * 点了「清除」—— 入队时是 true，回来时已经 false 了。读一次 app_cache 拿最新值，
     * 这个判断才是无竞态的。
     *
     * 读不到卡（已被删除）、或读失败，一律返回 false：宁可少写一次，也不要把用户
     * 明确不要的东西写回去。异常会记日志，不会静默。
     */
    private boolean isCardScrapeEnabled(String cardId) {
        try {
            SharedPreferences prefs = getSharedPreferences("app_cache", MODE_PRIVATE);
            String json = prefs.getString("data", "[]");
            Type type = new TypeToken<List<CardItem>>() {}.getType();
            List<CardItem> list = gson.fromJson(json, type);
            if (list == null) return false;

            for (CardItem item : list) {
                if (cardId.equals(item.getId())) return item.isScrapeEnabled();
            }
            return false;   // 卡已经不在了，结果没有落点

        } catch (Exception e) {
            LogUtil.e("【ScrapeService】读取卡片刮削状态失败", e);
            return false;
        }
    }

    /**
     * 刮削彻底失败（尝试次数用尽）时，按设置把这卡自己的刮削关掉。
     *
     * 关掉之后：卡片上的「清除」会消失，缺图补刮也不会再把它扫进队列 ——
     * 也就是这张卡不会再自己消耗队列时间了。想再试就点卡片上的「刮削」。
     *
     * 只用于"试过了但搜不到"这一类失败。**用户主动取消不算失败**，别调这个。
     *
     * 必须在 mainHandler 上调用：和其他几个 app_cache 的读改写在同一条线程上串行，
     * 否则两份 JSON 会互相覆盖。
     */
    private void disableScrapeOnFailure(String cardId) {
        if (!ScrapeSettings.isDisableOnFailEnabled(this)) return;
        try {
            SharedPreferences prefs = getSharedPreferences("app_cache", MODE_PRIVATE);
            String json = prefs.getString("data", "[]");
            Type type = new TypeToken<List<CardItem>>() {}.getType();
            List<CardItem> list = gson.fromJson(json, type);
            if (list == null) return;

            boolean dirty = false;
            for (CardItem item : list) {
                if (cardId.equals(item.getId())) {
                    if (item.isScrapeEnabled()) {
                        item.setScrapeEnabled(false);
                        dirty = true;
                        LogUtil.i("【ScrapeService】刮削失败，已关闭该卡的刮削: " + item.getCode());
                    }
                    break;
                }
            }
            if (!dirty) return;
            prefs.edit().putString("data", gson.toJson(list)).apply();
            broadcastProgress();   // 让列表把「清除」按钮收掉

        } catch (Exception e) {
            LogUtil.e("【ScrapeService】关闭卡片刮削失败", e);
        }
    }

    /**
     * 记录该卡片最近一次刮削尝试的时间。必须在 mainHandler 上调用，
     * 与 updateCardData / updateCardImageSize 串行，否则读改写会互相覆盖。
     */
    private void markCardScraped(String cardId) {
        try {
            SharedPreferences prefs = getSharedPreferences("app_cache", MODE_PRIVATE);
            String json = prefs.getString("data", "[]");
            Type type = new TypeToken<List<CardItem>>() {}.getType();
            List<CardItem> list = gson.fromJson(json, type);
            if (list == null) return;

            boolean dirty = false;
            for (CardItem item : list) {
                if (cardId.equals(item.getId())) {
                    item.setLastScrapeAt(System.currentTimeMillis());
                    dirty = true;
                    break;
                }
            }
            if (!dirty) return;
            prefs.edit().putString("data", gson.toJson(list)).apply();

        } catch (Exception e) {
            LogUtil.e("【ScrapeService】记录刮削时间失败", e);
        }
    }

    /**
     * 记录图片原始宽高。必须在 mainHandler 上调用，与 updateCardData 串行，
     * 否则两次读改写会互相覆盖。
     */
    private void updateCardImageSize(String cardId, int width, int height) {
        if (width <= 0 || height <= 0) return;
        try {
            SharedPreferences prefs = getSharedPreferences("app_cache", MODE_PRIVATE);
            String json = prefs.getString("data", "[]");
            Type type = new TypeToken<List<CardItem>>() {}.getType();
            List<CardItem> list = gson.fromJson(json, type);
            if (list == null) return;

            boolean dirty = false;
            for (CardItem item : list) {
                if (cardId.equals(item.getId())) {
                    if (item.getImgWidth() != width || item.getImgHeight() != height) {
                        item.setImageSize(width, height);
                        dirty = true;
                    }
                    break;
                }
            }
            if (!dirty) return;

            prefs.edit().putString("data", gson.toJson(list)).apply();
            LogUtil.i("【ScrapeService】图片尺寸已记录: " + cardId + " " + width + "x" + height);

        } catch (Exception e) {
            LogUtil.e("【ScrapeService】记录图片尺寸失败", e);
        }
    }

    // =====================================================================
    // 图片下载
    // =====================================================================

    private void downloadImage(String cardId, String imageUrl, ScrapeSource source) {
        if (!ScrapeSource.isValidImageUrl(imageUrl)) {
            LogUtil.w("【ScrapeService】图片URL无效，跳过: " + imageUrl);
            return;
        }
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(imageUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                if (source != null) {
                    connection.setRequestProperty("Referer", source.getBaseDomain());
                }
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.connect();

                if (connection.getResponseCode() == 200) {
                    InputStream is = connection.getInputStream();

                    File dir = new File(getFilesDir(), "images");
                    if (!dir.exists()) dir.mkdirs();

                    File outFile = new File(dir, cardId + ".jpg");
                    FileOutputStream fos = new FileOutputStream(outFile);

                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }

                    fos.flush();
                    fos.close();
                    is.close();

                    LogUtil.i("【ScrapeService】图片已保存: " + outFile.getAbsolutePath());

                    // 立刻记下原始宽高：卡片行高靠它算，图片异步加载就不会撑开列表
                    int[] size = ImageSizeUtil.readSize(outFile);
                    if (size != null) {
                        mainHandler.post(() -> updateCardImageSize(cardId, size[0], size[1]));
                    }
                }
            } catch (Exception e) {
                LogUtil.e("【ScrapeService】图片下载失败", e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    // =====================================================================
    // 队列持久化
    // =====================================================================

    private void loadQueue() {
        try {
            String json = getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
                    .getString(KEY_QUEUE, "[]");
            Type type = new TypeToken<List<ScrapeTask>>() {}.getType();
            List<ScrapeTask> saved = gson.fromJson(json, type);
            taskList.clear();
            if (saved != null) {
                // 恢复时把 RUNNING 改为 PENDING（上次被杀时正在运行的任务需要重试）
                for (ScrapeTask t : saved) {
                    if (t.getStatus() == ScrapeTask.Status.RUNNING) {
                        t.setStatus(ScrapeTask.Status.PENDING);
                    }
                    taskList.add(t);
                }
            }
        } catch (Exception e) {
            LogUtil.e("【ScrapeService】加载队列失败", e);
        }
    }

    private void saveQueue() {
        try {
            String json = gson.toJson(taskList);
            getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_QUEUE, json)
                    .apply();
        } catch (Exception e) {
            LogUtil.e("【ScrapeService】保存队列失败", e);
        }
    }

    // =====================================================================
    // 通知
    // =====================================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.scrape_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.scrape_channel_desc));
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void updateNotification() {
        updateNotification(null);
    }

    /** @param forcedTitle 非空时顶掉按状态推出来的标题（预热之类的临时阶段用） */
    private void updateNotification(String forcedTitle) {
        Notification notification = buildNotification(forcedTitle, 0, 0);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String forcedTitle, int forcedProgress, int forcedMax) {
        // 统计
        int pending = 0, running = 0, completed = 0, failed = 0;
        String currentCode = null;
        for (ScrapeTask t : taskList) {
            switch (t.getStatus()) {
                case PENDING: pending++; break;
                case RUNNING:
                    running++;
                    currentCode = t.getCode();
                    break;
                case COMPLETED: completed++; break;
                case FAILED: failed++; break;
            }
        }
        int total = pending + running + completed + failed;
        int done = completed + failed;

        String title;
        String text;
        int progress = forcedMax > 0 ? forcedProgress : done;
        int max = forcedMax > 0 ? forcedMax : Math.max(total, 1);

        if (forcedTitle != null) {
            title = forcedTitle;
            text = "";
        } else if (running > 0) {
            title = "正在刮削: " + (currentCode != null ? currentCode : "");
            text = "进度 " + done + "/" + total
                    + (failed > 0 ? "（" + failed + " 失败）" : "");
        } else if (pending > 0) {
            title = "等待刮削...";
            text = "剩余 " + pending + " 个任务";
        } else {
            title = "刮削已完成";
            text = "成功 " + completed + " 个"
                    + (failed > 0 ? "，失败 " + failed + " 个" : "");
        }

        // 内核重建之类的临时状态，挂在正文前面，让"刚才发生了什么"可见
        if (statusBanner != null && !text.isEmpty()) {
            text = statusBanner + " · " + text;
        } else if (statusBanner != null) {
            text = statusBanner;
        }

        // 点击通知进入 MainActivity
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_scrape_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(pending > 0 || running > 0)
                .setProgress(max, progress, false)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        // 添加取消按钮（仅在有待处理任务时）
        if (pending > 0 || running > 0) {
            Intent cancelIntent = new Intent(this, ScrapeService.class);
            cancelIntent.setAction(ACTION_CANCEL_ALL);
            PendingIntent cancelPI = PendingIntent.getService(
                    this, 1, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消全部", cancelPI);
        }

        return builder.build();
    }

    // =====================================================================
    // 广播
    // =====================================================================

    private void broadcastProgress() {
        Intent intent = new Intent(BROADCAST_PROGRESS);
        intent.setPackage(getPackageName()); // Android 14+ 显式限定同应用内广播
        intent.putExtra(EXTRA_QUEUE_JSON, gson.toJson(taskList));
        sendBroadcast(intent);
    }

    // =====================================================================
    // WakeLock
    // =====================================================================

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L); // 最长 10 分钟
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {}
        }
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private ScrapeSource findSource(String sourceId) {
        List<ScrapeSource> sources = ScrapeSource.loadAll(this);
        for (ScrapeSource s : sources) {
            if (s.getId().equals(sourceId)) return s;
        }
        // 回退到默认源
        return ScrapeSource.getDefault(this);
    }

    private void scheduleIdleStop() {
        cancelIdleStop();
        idleStopRunnable = () -> {
            // 再次确认空闲
            boolean hasPending = false;
            for (ScrapeTask t : taskList) {
                if (t.getStatus() == ScrapeTask.Status.PENDING
                        || t.getStatus() == ScrapeTask.Status.RUNNING) {
                    hasPending = true;
                    break;
                }
            }
            if (!hasPending) {
                LogUtil.i("【ScrapeService】空闲停止");
                stopSelf();
            }
        };
        mainHandler.postDelayed(idleStopRunnable, IDLE_STOP_DELAY_MS);
    }

    private void cancelIdleStop() {
        if (idleStopRunnable != null) {
            mainHandler.removeCallbacks(idleStopRunnable);
            idleStopRunnable = null;
        }
    }
}

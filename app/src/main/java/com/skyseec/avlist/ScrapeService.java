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

    private X5WebView webView;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ScrapeTask> taskList = new ArrayList<>();
    private boolean processing;
    private Runnable idleStopRunnable;
    private PowerManager.WakeLock wakeLock;

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

        // 创建刮削专用 WebView
        webView = new X5WebView(this);

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

        if (webView != null) {
            webView.stopReadyCheck();
            webView.stopLoading();
            webView.destroy();
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
        for (ScrapeTask t : taskList) {
            if (t.getStatus() == ScrapeTask.Status.PENDING) {
                t.setStatus(ScrapeTask.Status.FAILED);
                t.setErrorMessage("用户取消");
                removed++;
            }
        }
        LogUtil.i("【ScrapeService】取消 " + removed + " 个待处理任务");
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

        // 取第一个 PENDING 任务
        ScrapeTask nextTask = null;
        for (ScrapeTask t : taskList) {
            if (t.getStatus() == ScrapeTask.Status.PENDING) {
                nextTask = t;
                break;
            }
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

        JavResolver resolver = new JavResolver(
                webView,
                source,
                (scrapeTitle, imgUrl, detailUrl) -> {
                    // 在回调线程中处理结果（可能是主线程）
                    LogUtil.i("【ScrapeService】刮削回调: title=" + scrapeTitle + " img=" + (imgUrl != null ? imgUrl.substring(0, Math.min(40, imgUrl.length())) : "null"));

                    boolean success = scrapeTitle != null && !scrapeTitle.isEmpty()
                            || imgUrl != null && !imgUrl.isEmpty();

                    if (success) {
                        // 1. 下载图片
                        if (imgUrl != null && !imgUrl.isEmpty()) {
                            String resolvedUrl = source.resolveImageUrl(imgUrl);
                            downloadImage(task.getCardId(), resolvedUrl, source);
                        }

                        // 2. 更新 CardItem 数据
                        updateCardData(task.getCardId(), scrapeTitle, imgUrl);

                        task.setTitle(scrapeTitle);
                        task.setImgUrl(imgUrl);
                        task.setStatus(ScrapeTask.Status.COMPLETED);
                    } else {
                        task.setStatus(ScrapeTask.Status.FAILED);
                        task.setErrorMessage("无刮削结果");
                    }

                    LogUtil.i("【ScrapeService】刮削" + (success ? "成功" : "失败") + ": " + task.getCode());

                    // 清理并继续
                    mainHandler.post(() -> {
                        processing = false;
                        saveQueue();
                        updateNotification();
                        broadcastProgress();
                        releaseWakeLock();
                        processNext();
                    });
                });

        resolver.startScrape(task.getCode());
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
        Notification notification = buildNotification(null, 0, 0);
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

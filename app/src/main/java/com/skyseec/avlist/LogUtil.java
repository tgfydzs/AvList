package com.skyseec.avlist;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用日志工具 — 内存缓存 + 写入内部文件
 */
public class LogUtil {

    private static final String TAG = "AV";
    private static final int MAX_MEMORY_LINES = 500;

    // 读（getAll/getAllText/clear）和写（log）统一用 logBuffer 这一把锁。
    // 之前 log() 锁的是 LogUtil.class，读锁的是 logBuffer，两把锁不互斥，
    // 定时刷新的 UI 边读后台边写就会抛 ConcurrentModificationException。
    private static final List<String> logBuffer = new ArrayList<>();
    private static File logFile;

    /** 每次内容变化自增，供 UI 判断要不要重绘 */
    private static int version = 0;

    public static void init(File dir) {
        if (!dir.exists()) dir.mkdirs();
        synchronized (logBuffer) {
            logFile = new File(dir, "app.log");
        }
    }

    public static void i(String msg) {
        log(TAG, msg, null);
    }

    public static void w(String msg) {
        log(TAG + "-W", msg, null);
    }

    public static void e(String msg, Throwable t) {
        log(TAG + "-E", msg, t);
    }

    private static void log(String tag, String msg, Throwable t) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = ts + " [" + tag + "] " + msg;

        if (t != null) {
            line += " | " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        Log.d(tag, msg, t);

        synchronized (logBuffer) {
            logBuffer.add(line);
            if (logBuffer.size() > MAX_MEMORY_LINES) {
                logBuffer.remove(0);
            }
            version++;

            // 追加写入文件（留在锁内：并发追加会把行写串）
            if (logFile != null) {
                try {
                    FileOutputStream fos = new FileOutputStream(logFile, true);
                    OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                    osw.write(line + "\n");
                    osw.flush();
                    osw.close();
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static List<String> getAll() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }

    public static String getAllText() {
        StringBuilder sb = new StringBuilder();
        synchronized (logBuffer) {
            for (String s : logBuffer) {
                sb.append(s).append("\n");
            }
        }
        return sb.toString();
    }

    public static File getLogFile() {
        synchronized (logBuffer) {
            return logFile;
        }
    }

    /**
     * 内容版本号，每次新增/清空都会变。UI 拿它判断是否需要重绘，
     * 避免每次轮询都拼一遍完整文本。
     */
    public static int getVersion() {
        synchronized (logBuffer) {
            return version;
        }
    }

    public static void clear() {
        synchronized (logBuffer) {
            logBuffer.clear();
            version++;
            if (logFile != null && logFile.exists()) {
                logFile.delete();
            }
        }
    }
}

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

    private static final List<String> logBuffer = new ArrayList<>();
    private static File logFile;

    public static void init(File dir) {
        if (!dir.exists()) dir.mkdirs();
        logFile = new File(dir, "app.log");
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

    private static synchronized void log(String tag, String msg, Throwable t) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = ts + " [" + tag + "] " + msg;

        if (t != null) {
            line += " | " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        Log.d(tag, msg, t);
        logBuffer.add(line);
        if (logBuffer.size() > MAX_MEMORY_LINES) {
            logBuffer.remove(0);
        }

        // 追加写入文件
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
        return logFile;
    }

    public static void clear() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
        if (logFile != null && logFile.exists()) {
            logFile.delete();
        }
    }
}

package com.skyseec.avlist;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 定时备份管理器。
 * 从 app_cache 读取数据，写入外部存储 AvListBackups/ 目录（用户可直接访问）。
 *
 * 策略：记录上次备份的日期（天级别），每次 app 冷启动时检查日期差，
 * 满足间隔要求则自动备份一次，之后整个进程生命周期内不再检查。
 * 手动备份通过 MainActivity 的 SAF 导出功能完成。
 */
public class BackupManager {

    private static final String PREFS_NAME = "backup_prefs";
    private static final String KEY_BACKUP_COUNT = "backup_count";
    private static final String KEY_LAST_BACKUP_DATE = "last_backup_date";
    private static final int DEFAULT_BACKUP_COUNT = 3;
    private static final String BACKUP_DIR = "AvListBackups";
    private static final String BACKUP_PREFIX = "card_backup_";
    private static final SimpleDateFormat FILE_SDF = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
    private static final SimpleDateFormat DISPLAY_SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String KEY_BACKUP_INTERVAL_DAYS = "backup_interval_days";
    public static final int DEFAULT_INTERVAL_DAYS = 1;
    private static final int MAX_INTERVAL_DAYS = 30;

    // =========================
    // 配置读写
    // =========================

    public static int getBackupCount(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_BACKUP_COUNT, DEFAULT_BACKUP_COUNT);
    }

    public static void setBackupCount(Context context, int count) {
        count = Math.max(1, Math.min(10, count));
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BACKUP_COUNT, count).apply();
    }

    public static int getBackupIntervalDays(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_BACKUP_INTERVAL_DAYS, DEFAULT_INTERVAL_DAYS);
    }

    public static void setBackupIntervalDays(Context context, int days) {
        days = Math.max(1, Math.min(MAX_INTERVAL_DAYS, days));
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BACKUP_INTERVAL_DAYS, days).apply();
    }

    // =========================
    // 上次备份日期（天级别）
    // =========================

    private static String getLastBackupDate(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_BACKUP_DATE, "");
    }

    public static void recordBackupDone(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_BACKUP_DATE, LocalDate.now().format(DATE_FMT)).apply();
    }

    public static String formatLastBackupDate(Context context) {
        String dateStr = getLastBackupDate(context);
        if (dateStr.isEmpty()) return "从未备份";
        try {
            LocalDate d = LocalDate.parse(dateStr, DATE_FMT);
            return d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (Exception e) {
            return dateStr;
        }
    }

    // =========================
    // 备份目录路径（外部存储，用户可访问）
    // =========================

    /**
     * 备份根目录（外部存储的应用专属目录，用户可通过文件管理器访问）。
     * 路径示例：/storage/emulated/0/Android/data/com.skyseec.avlist/files/AvListBackups/
     */
    public static File getBackupDir(Context context) {
        return new File(context.getExternalFilesDir(null), BACKUP_DIR);
    }

    // =========================
    // 从 app_cache 获取数据
    // =========================

    /**
     * 读取 app_cache 的 JSON 数据。供外部导出使用。
     */
    public static String getDataJson(Context context) {
        return context.getSharedPreferences("app_cache", Context.MODE_PRIVATE)
                .getString("data", "[]");
    }

    // =========================
    // 核心：启动时检查并自动备份
    // =========================

    /**
     * 在 app 冷启动时调用一次。比较当前日期与上次备份日期，
     * 满足间隔要求则自动备份到外部存储。
     */
    public static void checkAndBackup(Context context) {
        int intervalDays = getBackupIntervalDays(context);
        String lastDateStr = getLastBackupDate(context);

        if (!lastDateStr.isEmpty()) {
            try {
                LocalDate lastDate = LocalDate.parse(lastDateStr, DATE_FMT);
                LocalDate today = LocalDate.now();
                long daysSince = ChronoUnit.DAYS.between(lastDate, today);
                if (daysSince < intervalDays) {
                    LogUtil.i("【备份】距上次" + daysSince + "天，未到间隔" + intervalDays + "天，跳过");
                    return;
                }
                LogUtil.i("【备份】距上次" + daysSince + "天，已超过间隔" + intervalDays + "天，开始备份");
            } catch (Exception e) {
                LogUtil.e("【备份】日期解析失败", e);
            }
        } else {
            LogUtil.i("【备份】首次备份");
        }

        autoBackup(context);
    }

    // =========================
    // 自动备份（写入外部存储，无需用户交互）
    // =========================

    /**
     * 自动备份到外部存储 AvListBackups/ 目录。无需用户交互。
     */
    private static String autoBackup(Context context) {
        String json = getDataJson(context);
        if (json.equals("[]")) {
            LogUtil.w("【备份】数据为空，跳过");
            return null;
        }

        // 与最新备份比较：内容相同则跳过
        List<File> existingFiles = getBackupFiles(context);
        if (!existingFiles.isEmpty()) {
            String lastContent = readFileContent(existingFiles.get(0));
            if (json.equals(lastContent)) {
                LogUtil.i("【备份】内容无变化，跳过");
                recordBackupDone(context);
                return existingFiles.get(0).getAbsolutePath();
            }
        }

        // 确保目录存在
        File dir = getBackupDir(context);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            LogUtil.e("【备份】无法创建目录: " + dir.getAbsolutePath(), null);
            return null;
        }

        // 写入备份文件
        String fileName = BACKUP_PREFIX + FILE_SDF.format(new Date()) + ".json";
        File backupFile = new File(dir, fileName);

        try (FileWriter writer = new FileWriter(backupFile)) {
            writer.write(json);
            writer.flush();
        } catch (IOException e) {
            LogUtil.e("【备份】写入失败", e);
            return null;
        }

        recordBackupDone(context);
        trimOldBackups(context, getBackupCount(context));

        LogUtil.i("【备份】完成: " + fileName);
        return backupFile.getAbsolutePath();
    }

    /**
     * 获取所有自动备份文件，按时间倒序。
     */
    public static List<File> getBackupFiles(Context context) {
        File dir = getBackupDir(context);
        List<File> result = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory()) return result;

        File[] files = dir.listFiles((d, name) -> name.startsWith(BACKUP_PREFIX) && name.endsWith(".json"));
        if (files == null) return result;

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return Arrays.asList(files);
    }

    private static void trimOldBackups(Context context, int maxCount) {
        List<File> files = getBackupFiles(context);
        if (files.size() <= maxCount) return;
        for (int i = maxCount; i < files.size(); i++) {
            File f = files.get(i);
            if (f.delete()) {
                LogUtil.i("【备份】删除旧备份: " + f.getName());
            }
        }
    }

    private static String readFileContent(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    public static String parseTimestampFromName(String fileName) {
        try {
            String ts = fileName.replace(BACKUP_PREFIX, "").replace(".json", "");
            Date date = FILE_SDF.parse(ts);
            return DISPLAY_SDF.format(date);
        } catch (Exception e) {
            return fileName;
        }
    }
}

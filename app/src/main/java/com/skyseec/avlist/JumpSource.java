package com.skyseec.avlist;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 跳转源配置。每个源定义了一个跳转URL模板，{keyword} 为番号占位符。
 */
public class JumpSource implements Serializable {

    private String id;
    private String name;
    private String baseUrl;     // 跳转URL，{keyword} 为番号占位符
    private boolean isDefault;

    public JumpSource() {
        this.id = UUID.randomUUID().toString();
    }

    public JumpSource(String id, String name, String baseUrl, boolean isDefault) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.baseUrl = baseUrl;
        this.isDefault = isDefault;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name == null ? "" : name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl == null ? "" : baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public String buildUrl(String keyword) {
        String url = getBaseUrl();
        if (url.isEmpty() || keyword == null) return url;
        return url.replace("{keyword}", keyword);
    }

    // =========================
    // 持久化管理
    // =========================

    private static final String PREFS_NAME = "jump_source_prefs";
    private static final String KEY_SOURCE = "current_jump_source";
    private static final Gson gson = new Gson();

    /**
     * 获取当前选中的跳转源。无配置时返回默认。
     */
    public static JumpSource getCurrent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SOURCE, null);
        if (json == null) {
            JumpSource def = createDefault();
            saveCurrent(context, def);
            return def;
        }
        JumpSource s = gson.fromJson(json, JumpSource.class);
        return s != null ? s : createDefault();
    }

    public static void saveCurrent(Context context, JumpSource source) {
        String json = gson.toJson(source);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SOURCE, json)
                .apply();
    }

    /**
     * 读取所有跳转源（内置 + 用户自定义）
     */
    public static List<JumpSource> loadAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString("all_sources", null);
        if (json == null) {
            List<JumpSource> defaults = createDefaults();
            saveAll(context, defaults);
            return defaults;
        }
        Type type = new TypeToken<List<JumpSource>>() {}.getType();
        List<JumpSource> list = gson.fromJson(json, type);
        if (list == null) list = new ArrayList<>();

        // 合并内置
        boolean changed = false;
        for (JumpSource builtin : createDefaults()) {
            boolean found = false;
            for (JumpSource s : list) {
                if (builtin.getName().equals(s.getName())) { found = true; break; }
            }
            if (!found) { builtin.setDefault(false); list.add(builtin); changed = true; }
        }
        if (changed) saveAll(context, list);
        return list;
    }

    public static void saveAll(Context context, List<JumpSource> sources) {
        String json = gson.toJson(sources);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("all_sources", json)
                .apply();
    }

    /**
     * 设一个源为当前选中
     */
    public static void setCurrent(Context context, JumpSource current) {
        saveCurrent(context, current);
        // 同时更新 all_sources 中的默认标记
        List<JumpSource> all = loadAll(context);
        for (JumpSource s : all) {
            s.setDefault(s.getId().equals(current.getId()));
        }
        saveAll(context, all);
    }

    private static JumpSource createDefault() {
        return new JumpSource(
                UUID.randomUUID().toString(),
                "MissAV",
                "https://missav.ws/search/{keyword}",
                true
        );
    }

    private static List<JumpSource> createDefaults() {
        List<JumpSource> list = new ArrayList<>();
        list.add(createDefault());
        return list;
    }
}

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
 * 刮削源配置数据模型。
 * 每个源定义了搜索URL模板和XPath提取规则。
 */
public class ScrapeSource implements Serializable {

    private String id;
    private String name;
    private String baseUrl;      // 搜索URL，{keyword} 为番号占位符
    private String titleXPath;   // 标题提取XPath
    private String imageXPath;   // 图片提取XPath
    private boolean isDefault;
    private int waitSeconds = 3; // 页面加载后等待DOM渲染的秒数（默认3）
    private boolean needsPrewarm = false; // 是否需要先访问首页过CF验证

    public ScrapeSource() {
        this.id = UUID.randomUUID().toString();
    }

    public ScrapeSource(String id, String name, String baseUrl,
                        String titleXPath, String imageXPath, boolean isDefault) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.baseUrl = baseUrl;
        this.titleXPath = titleXPath;
        this.imageXPath = imageXPath;
        this.isDefault = isDefault;
    }

    // =========================
    // Getter / Setter
    // =========================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getTitleXPath() {
        return titleXPath == null ? "" : titleXPath;
    }

    public void setTitleXPath(String titleXPath) {
        this.titleXPath = titleXPath;
    }

    public String getImageXPath() {
        return imageXPath == null ? "" : imageXPath;
    }

    public void setImageXPath(String imageXPath) {
        this.imageXPath = imageXPath;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public int getWaitSeconds() {
        return waitSeconds > 0 ? waitSeconds : 3;
    }

    public void setWaitSeconds(int waitSeconds) {
        this.waitSeconds = waitSeconds;
    }

    public boolean isNeedsPrewarm() {
        return needsPrewarm;
    }

    public void setNeedsPrewarm(boolean needsPrewarm) {
        this.needsPrewarm = needsPrewarm;
    }

    /**
     * 获取预热URL（网站的首页/根路径），用于过CF验证
     */
    public String getPrewarmUrl() {
        return getBaseDomain();
    }

    /**
     * 构造搜索URL：将 {keyword} 替换为实际番号
     */
    public String buildSearchUrl(String keyword) {
        String url = getBaseUrl();
        if (url.isEmpty() || keyword == null) return url;
        return url.replace("{keyword}", keyword);
    }

    /**
     * 从 baseUrl 提取域名，用于补全相对路径的图片URL
     */
    public String getBaseDomain() {
        String url = getBaseUrl();
        if (url.isEmpty()) return "";
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getProtocol() + "://" + u.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析图片URL：若以 "/" 开头则用源域名补全；过滤 data: 等非 HTTP 协议
     */
    public String resolveImageUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return "";
        String lower = rawUrl.toLowerCase();
        // 过滤占位图：data URI、blob、1x1 像素等
        if (lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("javascript:")) {
            return "";
        }
        // 过滤明显是占位的极小图片（常见1x1 base64占位）
        if (rawUrl.startsWith("/")) {
            return getBaseDomain() + rawUrl;
        }
        return rawUrl;
    }

    /**
     * 检查图片URL是否可下载（必须是 http/https）
     */
    public static boolean isValidImageUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase().trim();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    // =========================
    // ScrapeSourceManager — 持久化管理
    // =========================

    private static final String PREFS_NAME = "scrape_source_prefs";
    private static final String KEY_SOURCES = "sources";
    private static final Gson gson = new Gson();

    /**
     * 读取所有刮削源
     */
    /**
     * 读取所有刮削源。首次启动创建内置源；升级时自动合并新增内置源。
     */
    public static List<ScrapeSource> loadAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SOURCES, null);
        if (json == null) {
            List<ScrapeSource> defaults = createDefaults();
            saveAll(context, defaults);
            return defaults;
        }
        Type type = new TypeToken<List<ScrapeSource>>() {}.getType();
        List<ScrapeSource> list = gson.fromJson(json, type);
        if (list == null) list = new ArrayList<>();

        // 合并内置源：按名称判断，不存在的自动追加，存在的自动升级默认值
        boolean changed = false;
        for (ScrapeSource builtin : createDefaults()) {
            boolean found = false;
            for (ScrapeSource s : list) {
                if (builtin.getName().equals(s.getName())) {
                    found = true;
                    // 自动升级：旧用户没有预热配置的，更新为内置默认值
                    if (builtin.isNeedsPrewarm() && !s.isNeedsPrewarm()) {
                        s.setNeedsPrewarm(true);
                        if (s.getWaitSeconds() <= 3) s.setWaitSeconds(builtin.getWaitSeconds());
                        changed = true;
                    }
                    break;
                }
            }
            if (!found) {
                builtin.setDefault(false);
                list.add(builtin);
                changed = true;
            }
        }
        if (changed) {
            saveAll(context, list);
        }

        return list;
    }

    /**
     * 保存所有刮削源
     */
    public static void saveAll(Context context, List<ScrapeSource> sources) {
        String json = gson.toJson(sources);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SOURCES, json)
                .apply();
    }

    /**
     * 获取当前默认源。若无标记则返回第一个，列表为空返回 null。
     */
    public static ScrapeSource getDefault(Context context) {
        List<ScrapeSource> sources = loadAll(context);
        if (sources.isEmpty()) return null;
        for (ScrapeSource s : sources) {
            if (s.isDefault()) return s;
        }
        // 回退：返回第一个并标记为默认
        if (!sources.isEmpty()) {
            sources.get(0).setDefault(true);
            saveAll(context, sources);
            return sources.get(0);
        }
        return null;
    }

    /**
     * 创建默认源列表（仅 JavBus）
     */
    private static List<ScrapeSource> createDefaults() {
        List<ScrapeSource> list = new ArrayList<>();
        ScrapeSource missav = new ScrapeSource(
                UUID.randomUUID().toString(),
                "MissAV",
                "https://missav.ws/search/{keyword}",
                "//div[contains(@class,'thumbnail')]//div[contains(@class,'truncate')]/a",
                "//div[contains(@class,'thumbnail')]//img[contains(@class,'w-full')]/@data-src",
                true
        );
        missav.setNeedsPrewarm(true);
        missav.setWaitSeconds(8);
        list.add(missav);

        list.add(new ScrapeSource(
                UUID.randomUUID().toString(),
                "JavBus",
                "https://www.javbus.com/search/{keyword}",
                "//div[contains(@class,'movie-box')]//div[contains(@class,'photo-frame')]/img/@title",
                "//div[contains(@class,'movie-box')]//div[contains(@class,'photo-frame')]/img/@src",
                false
        ));
        return list;
    }
}

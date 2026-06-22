package com.skyseec.avlist;

import java.io.File;
import java.io.Serializable;
import java.util.Locale;
import java.util.UUID;

public class CardItem implements Serializable {

    private String id;
    private String title;
    private String content;
    private String imageUrl;
    private long createTime;
    private String remark;
    private String tag;
    private String code;
    private int rating = 0;  // 评分 0-5，0 表示未评分
    private boolean scrapeEnabled = true;
    private boolean userEdited = false;  // 用户手动编辑过 → 刮削不再覆盖标题/图片
    private transient String localImagePath; // transient: 不序列化到 JSON，运行时从内部存储推导

    public CardItem() {
        // 防止Gson空字段崩
        this.id = UUID.randomUUID().toString();
        this.createTime = System.currentTimeMillis();
    }

    public CardItem(String id, String title, String content, String imageUrl,
                    long createTime, String remark, String tag, String code,
                    boolean scrapeEnabled) {

        this.id = id != null ? id : UUID.randomUUID().toString();
        this.title = title;
        this.content = content;
        this.imageUrl = imageUrl;
        this.createTime = createTime;
        this.remark = remark;
        this.tag = tag;
        this.code = code;
        this.scrapeEnabled = scrapeEnabled;
    }

    // =========================
    // Getter / Setter
    // =========================

    public String getId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return cleanDisplayValue(title);
    }

    public void setTitle(String title) {
        this.title = isBlankOrNullStr(title) ? "" : title;
    }

    public String getContent() {
        return content == null ? "" : content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImageUrl() {
        return cleanDisplayValue(imageUrl);
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = isBlankOrNullStr(imageUrl) ? "" : imageUrl;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public String getRemark() {
        return remark == null ? "" : remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getTag() {
        return tag == null ? "" : tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getCode() {
        return code == null ? "" : code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    // =========================
    // 核心：统一标准化code（重要优化）
    // =========================
    public String normalizeCode() {
        if (code == null) return "";
        return code.replaceAll("[^a-zA-Z0-9]", "").toUpperCase(Locale.ROOT);
    }

    /**
     * 判断字符串是否为空、空串或字面值 "null" / "undefined" / "none"
     */
    public static boolean isBlankOrNullStr(String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("undefined") || t.equalsIgnoreCase("none");
    }

    /**
     * 用于 getter 展示层过滤：把无效值转为空串，保证 UI 永不会显示 null/undefined/none
     */
    private static String cleanDisplayValue(String s) {
        return isBlankOrNullStr(s) ? "" : s;
    }

    public static String normalizeCode(String input) {
        if (input == null) return "";
        return input.replaceAll("[^a-zA-Z0-9]", "").toUpperCase(Locale.ROOT);
    }

    // =========================
    // 唯一性判断（优化版）
    // =========================
    public boolean isSameCode(String targetCode) {
        String a = normalizeCode();
        String b = normalizeCode(targetCode);
        return !a.isEmpty() && a.equals(b);
    }

    public boolean isScrapeEnabled() {
        return scrapeEnabled;
    }

    public void setScrapeEnabled(boolean scrapeEnabled) {
        this.scrapeEnabled = scrapeEnabled;
    }

    public boolean isUserEdited() {
        return userEdited;
    }

    public void setUserEdited(boolean userEdited) {
        this.userEdited = userEdited;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = Math.max(0, Math.min(5, rating));
    }

    public String getLocalImagePath() {
        return localImagePath;
    }

    public void setLocalImagePath(String localImagePath) {
        this.localImagePath = localImagePath;
    }

    /**
     * 根据 context 推导本地图片文件路径（不依赖 transient 字段持久化）
     */
    public String resolveLocalImagePath(android.content.Context context) {
        if (localImagePath != null) return localImagePath;
        File dir = new File(context.getFilesDir(), "images");
        File file = new File(dir, id + ".jpg");
        return file.getAbsolutePath();
    }
}
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
    private int jumpCount = 0;  // 跳转次数
    private int imgWidth = 0;   // 图片原始宽，0 表示未知
    private int imgHeight = 0;  // 图片原始高，0 表示未知
    private long lastScrapeAt = 0; // 上次被刮削的时间（成功失败都算），0 表示从没刮过
    /**
     * 这张卡要不要刮削 —— 卡片上「刮削」/「清除」两颗按钮控制的唯一状态。
     *
     * 点「刮削」置 true，点「清除」置 false（同时清掉标题和图片）。
     * 它只决定"要不要"，读它的地方目前是启动时的缺图补刮
     * （MainActivity.enqueueCardsMissingImage）—— 关了就不会被自动补刮。
     *
     * 注意手动刮削不看这个值：点「刮削」本身就会先把它置 true 再入队。
     */
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

    public int getJumpCount() {
        return jumpCount;
    }

    public void setJumpCount(int jumpCount) {
        this.jumpCount = Math.max(0, jumpCount);
    }

    public long getLastScrapeAt() {
        return lastScrapeAt;
    }

    /** 记录这次刮削尝试（成功或失败都记），用于"缺图补刮"的重试冷却 */
    public void setLastScrapeAt(long lastScrapeAt) {
        this.lastScrapeAt = lastScrapeAt;
    }

    public int getImgWidth() {
        return imgWidth;
    }

    public int getImgHeight() {
        return imgHeight;
    }

    /**
     * 记录图片原始宽高（尺寸非法时忽略，不会把已知值冲掉）
     */
    public void setImageSize(int width, int height) {
        if (width > 0 && height > 0) {
            this.imgWidth = width;
            this.imgHeight = height;
        }
    }

    /**
     * 清空图片尺寸记录。图片被清除后调用，等换图时重新测，
     * 免得下一张图沿用上一张的比例。
     */
    public void clearImageSize() {
        this.imgWidth = 0;
        this.imgHeight = 0;
    }

    /**
     * 图片高/宽比；宽高未知返回 0，调用方需自行兜底。
     * 卡片行高只由这个值决定，从而与图片异步加载时机解耦。
     */
    public float getImageRatio() {
        return (imgWidth > 0 && imgHeight > 0) ? (float) imgHeight / imgWidth : 0f;
    }

    /**
     * 把另一份（重新解析出来的）同名卡片的值就地合并进当前对象，保留对象身份。
     * 返回展示层字段是否有变化。
     *
     * 不合并 rating / jumpCount / code：这三个是排序键，变了必须整体重排，
     * 由调用方通过 sortKeysChangedFrom() 判断后走全量刷新。
     */
    public boolean applyChangesFrom(CardItem other) {
        if (other == null) return false;
        boolean changed = false;

        if (!strEquals(title, other.title)) { title = other.title; changed = true; }
        if (!strEquals(imageUrl, other.imageUrl)) { imageUrl = other.imageUrl; changed = true; }
        if (!strEquals(tag, other.tag)) { tag = other.tag; changed = true; }
        if (!strEquals(remark, other.remark)) { remark = other.remark; changed = true; }
        if (!strEquals(content, other.content)) { content = other.content; changed = true; }
        if (userEdited != other.userEdited) { userEdited = other.userEdited; changed = true; }
        if (imgWidth != other.imgWidth || imgHeight != other.imgHeight) {
            imgWidth = other.imgWidth;
            imgHeight = other.imgHeight;
            changed = true;
        }

        // lastScrapeAt 要跟着同步，但它不影响显示 —— 故意不计入 changed，
        // 否则每刮完一张卡都会白白触发一次重绑，把列表又弄响
        lastScrapeAt = other.lastScrapeAt;

        return changed;
    }

    /**
     * 排序键（评分/跳转次数/番号）是否与另一份不同 —— 不同则需要整体重新排序
     */
    public boolean sortKeysChangedFrom(CardItem other) {
        if (other == null) return true;
        return rating != other.rating
                || jumpCount != other.jumpCount
                || !strEquals(code, other.code);
    }

    private static boolean strEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
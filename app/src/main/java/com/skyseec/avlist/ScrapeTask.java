package com.skyseec.avlist;

import java.io.Serializable;
import java.util.UUID;

/**
 * 刮削任务数据模型 — 后台服务队列中的单个任务。
 * 持久化到 SharedPreferences 以支持杀进程后恢复。
 */
public class ScrapeTask implements Serializable {

    public enum Status {
        PENDING,    // 等待执行
        RUNNING,    // 正在刮削
        COMPLETED,  // 刮削成功
        FAILED      // 刮削失败（超时/网络错误/无结果）
    }

    private String id;
    private String cardId;        // 对应 CardItem.getId()
    private String code;          // 番号（搜索关键字）
    private String scrapeSourceId; // 使用的刮削源ID
    private Status status;
    private String title;         // 刮削结果标题
    private String imgUrl;        // 刮削结果图片URL
    private String errorMessage;  // 失败原因
    private long createdAt;
    /**
     * 这个任务已经被重新执行过几次。两种原因共用这一个计数：
     * ① 没搜到结果，被退回队列尾部轮转；② 浏览器内核崩溃，重建后重排。
     * 到了上限（{@code ScrapeService.MAX_TASK_ATTEMPTS}）就不再回队，判 FAILED。
     */
    private int attempts = 0;

    public ScrapeTask() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.status = Status.PENDING;
    }

    public ScrapeTask(String cardId, String code, String scrapeSourceId) {
        this();
        this.cardId = cardId;
        this.code = code;
        this.scrapeSourceId = scrapeSourceId;
    }

    /**
     * 从 CardItem + ScrapeSource 构建任务
     */
    public static ScrapeTask from(CardItem item, ScrapeSource source) {
        return new ScrapeTask(
                item.getId(),
                item.getCode(),
                source != null ? source.getId() : ""
        );
    }

    // =========================
    // Getter / Setter
    // =========================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCardId() { return cardId; }
    public void setCardId(String cardId) { this.cardId = cardId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getScrapeSourceId() { return scrapeSourceId; }
    public void setScrapeSourceId(String scrapeSourceId) { this.scrapeSourceId = scrapeSourceId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    /**
     * 是否已结束（成功或失败）
     */
    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.FAILED;
    }
}

package com.skyseec.avlist;

import android.content.Context;

import java.io.File;

/**
 * 「筛选与排序」弹窗里的过滤条件。
 *
 * 三组条件之间是**且**的关系，和搜索关键词也是且的关系（都作用在 {@code originList} 上）。
 *
 * 和排序状态一样只活在内存里，不持久化 —— 重开 app 就回到默认的全放行。
 * 这样才安全：过滤是"我现在想找一批卡"的临时视角，不该在下次打开时悄悄少显示几张。
 */
public class CardFilter {

    /** 星级：不限 / 未评分 / ≥1★ / ≥3★ / 5★ */
    public enum Rating { ANY, UNRATED, GE1, GE3, FIVE }

    /** 图片：不限 / 有图 / 无图。判据和卡片上显示不显示封面完全一致 */
    public enum Image { ANY, HAS, NONE }

    /** 刮削开关：不限 / 开着 / 关掉 */
    public enum Scrape { ANY, ON, OFF }

    public Rating rating = Rating.ANY;
    public Image image = Image.ANY;
    public Scrape scrape = Scrape.ANY;

    /** 有没有任何一组过滤在生效 —— 工具栏图标靠它决定要不要高亮 */
    public boolean isActive() {
        return rating != Rating.ANY || image != Image.ANY || scrape != Scrape.ANY;
    }

    public void reset() {
        rating = Rating.ANY;
        image = Image.ANY;
        scrape = Scrape.ANY;
    }

    /**
     * 这张卡过不过滤。
     *
     * 图片那组要碰文件系统，所以放在最后判 —— 只在它真的被选中时才去 stat，
     * 平时翻列表/敲搜索框不会被这个拖慢。
     */
    public boolean matches(Context context, CardItem item) {
        if (rating != Rating.ANY && !matchRating(item.getRating())) return false;
        if (scrape != Scrape.ANY && !matchScrape(item.isScrapeEnabled())) return false;
        if (image != Image.ANY && !matchImage(context, item)) return false;
        return true;
    }

    private boolean matchRating(int value) {
        switch (rating) {
            case UNRATED:
                return value <= 0;
            case GE1:
                return value >= 1;
            case GE3:
                return value >= 3;
            case FIVE:
                return value >= 5;
            case ANY:
            default:
                return true;
        }
    }

    private boolean matchScrape(boolean enabled) {
        return scrape == Scrape.ON ? enabled : !enabled;
    }

    private boolean matchImage(Context context, CardItem item) {
        boolean has = hasImage(context, item);
        return image == Image.HAS ? has : !has;
    }

    /**
     * 这张卡在列表上显示不显示封面 —— 和 {@code CardAdapter} 的两条分支一一对应：
     * 本地文件优先，没有才看网络 URL。两处判据必须保持一致，
     * 否则会出现"筛『无图』却筛出一堆有图的卡"。
     */
    private static boolean hasImage(Context context, CardItem item) {
        if (new File(item.resolveLocalImagePath(context)).exists()) return true;
        return !CardItem.isBlankOrNullStr(item.getImageUrl());
    }
}

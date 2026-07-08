package com.skyseec.avlist;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;

/**
 * 辅助 DrawerLayout 从屏幕右侧 30%~100% 区域检测左滑手势。
 * 触摸起点在屏幕右半区、水平位移为主且超过阈值时打开侧边抽屉。
 */
public class DrawerEdgeHelper implements View.OnTouchListener {

    private final DrawerLayout drawerLayout;
    private final float startRatio;    // 检测起始位置比例（0~1，例如 0.3f 表示屏幕 30% 处）
    private final int swipeThreshold;  // 触发滑动的最小水平位移(px)
    private float downX, downY;
    private boolean tracking;

    /**
     * @param drawerLayout  目标抽屉
     * @param startRatio    触摸起点检测比例，例如 0.3f = 屏幕右侧 70% 区域
     * @param swipeThresholdDp 触发开抽屉所需的最小水平滑动距离(dp)
     */
    public DrawerEdgeHelper(DrawerLayout drawerLayout, float startRatio, int swipeThresholdDp) {
        this.drawerLayout = drawerLayout;
        this.startRatio = startRatio;
        float density = drawerLayout.getResources().getDisplayMetrics().density;
        this.swipeThreshold = (int) (swipeThresholdDp * density);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                // 触摸起点在屏幕 startRatio（默认30%位置）右侧
                tracking = x > v.getWidth() * startRatio;
                break;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) break;

                float dx = downX - x;          // 正数=左滑
                float dy = Math.abs(y - downY); // 竖向偏移绝对值

                // 条件：1)水平位移超过阈值  2)水平主导(不是竖向滚列表)
                if (dx > swipeThreshold && dx > dy * 1.2f) {
                    tracking = false;
                    drawerLayout.openDrawer(GravityCompat.END);
                    return true;
                }

                // 竖向移动明显 → 取消（用户在下拉列表）
                if (dy > swipeThreshold && dy > dx * 1.2f) {
                    tracking = false;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                break;
        }

        return false;
    }
}

package com.skyseec.avlist;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * 高度由固定比例决定的 ImageView。
 *
 * 存在的意义：卡片瀑布流里行高若由 drawable 决定，图片异步加载完成的那一刻
 * 高度才确定，会导致整列重新测量、下方内容被推开。这里改为高度 = 宽度 × ratio，
 * 比例来自 CardItem 里记录的真实图片宽高，所以图片到达前后布局完全一致。
 *
 * ratio <= 0 时不干预测量，退回普通 ImageView 行为。
 */
public class RatioImageView extends AppCompatImageView {

    /** 高/宽 */
    private float ratio = 0f;

    public RatioImageView(Context context) {
        super(context);
    }

    public RatioImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setRatio(float ratio) {
        // 只在真正变化时请求重排，避免每次绑定都触发一次布局
        if (Math.abs(ratio - this.ratio) < 0.001f) return;
        this.ratio = ratio;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (ratio <= 0f || getVisibility() == GONE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if ((widthMode == MeasureSpec.UNSPECIFIED) || width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        setMeasuredDimension(width, Math.round(width * ratio));
    }
}

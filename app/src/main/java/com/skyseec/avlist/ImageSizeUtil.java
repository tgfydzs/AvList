package com.skyseec.avlist;

import android.graphics.BitmapFactory;

import java.io.File;

/**
 * 读取图片原始宽高。
 *
 * 用 inJustDecodeBounds 只解析图片头，不解码像素，因此很快，
 * 可以在图片落盘后立刻调用，把尺寸记进 CardItem。
 */
public class ImageSizeUtil {

    private ImageSizeUtil() {}

    /**
     * @return {width, height}；文件不存在 / 不是有效图片 / 尺寸非法时返回 null
     */
    public static int[] readSize(File file) {
        if (file == null || !file.exists() || file.length() == 0) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth > 0 && options.outHeight > 0) {
                return new int[]{options.outWidth, options.outHeight};
            }
        } catch (Exception e) {
            LogUtil.e("读取图片尺寸失败: " + file.getName(), e);
        }
        return null;
    }

    /**
     * 读尺寸并直接写进卡片（宽高为 0 时保持不变）
     */
    public static void applyToCard(CardItem item, File file) {
        if (item == null) return;
        int[] size = readSize(file);
        if (size != null) {
            item.setImageSize(size[0], size[1]);
        }
    }
}

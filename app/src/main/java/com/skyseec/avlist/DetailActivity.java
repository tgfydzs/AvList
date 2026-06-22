package com.skyseec.avlist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DetailActivity extends AppCompatActivity {

    private ImageView ivDetailImage;
    private TextView tvDetailCode, tvDetailTitle, tvDetailTag, tvDetailTime;
    private TextView tvDetailContent, tvDetailRemark;
    private AppCompatButton btnEditDetail, btnScrapeDetail, btnClearDetail;
    private LinearLayout llContentSection, llRemarkSection;
    private ImageView[] detailStars;
    private CardItem item;

    private final BroadcastReceiver scrapeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadItemFromCache();
            bindData();
        }
    };

    // 图片选择器（注意：editDialogView 必须在 pickImageLauncher 之前声明）
    private View editDialogView;
    private Uri pendingImagePickUri;
    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    pendingImagePickUri = uri;
                    if (editDialogView != null) {
                        ImageView preview = editDialogView.findViewById(R.id.ivImagePreview);
                        if (preview != null) {
                            preview.setVisibility(View.VISIBLE);
                            Glide.with(DetailActivity.this).load(uri).dontTransform().into(preview);
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        ivDetailImage = findViewById(R.id.ivDetailImage);
        tvDetailCode = findViewById(R.id.tvDetailCode);
        tvDetailTitle = findViewById(R.id.tvDetailTitle);
        tvDetailTag = findViewById(R.id.tvDetailTag);
        tvDetailTime = findViewById(R.id.tvDetailTime);
        tvDetailContent = findViewById(R.id.tvDetailContent);
        tvDetailRemark = findViewById(R.id.tvDetailRemark);
        btnEditDetail = findViewById(R.id.btnEditDetail);
        btnScrapeDetail = findViewById(R.id.btnScrapeDetail);
        btnClearDetail = findViewById(R.id.btnClearDetail);
        llContentSection = findViewById(R.id.llContentSection);
        llRemarkSection = findViewById(R.id.llRemarkSection);

        // 评分星级
        detailStars = new ImageView[]{
                findViewById(R.id.ivDetailStar1),
                findViewById(R.id.ivDetailStar2),
                findViewById(R.id.ivDetailStar3),
                findViewById(R.id.ivDetailStar4),
                findViewById(R.id.ivDetailStar5)
        };

        item = (CardItem) getIntent().getSerializableExtra("card_data");
        if (item == null) {
            finish();
            return;
        }

        bindData();
    }

    private void bindData() {
        // ========== 图片：优先本地，其次网络，无图 GONE ==========
        String localPath = item.resolveLocalImagePath(this);
        File localFile = new File(localPath);
        boolean hasImage = false;

        if (localFile.exists()) {
            Glide.with(this).load(localFile).dontTransform().into(ivDetailImage);
            hasImage = true;
        } else {
            String imgUrl = item.getImageUrl();
            if (imgUrl != null && !imgUrl.trim().isEmpty()) {
                ScrapeSource defSource = ScrapeSource.getDefault(this);
                if (imgUrl.startsWith("/")) {
                    imgUrl = defSource.getBaseDomain() + imgUrl;
                }
                String referer = defSource.getBaseDomain();
                GlideUrl glideUrl = new GlideUrl(imgUrl,
                        new LazyHeaders.Builder()
                                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .addHeader("Referer", referer).build());
                Glide.with(this).load(glideUrl).dontTransform().into(ivDetailImage);
                hasImage = true;
            }
        }
        ivDetailImage.setVisibility(hasImage ? View.VISIBLE : View.GONE);

        // ========== 番号 ==========
        String code = item.getCode();
        tvDetailCode.setVisibility(code != null && !code.isEmpty() ? View.VISIBLE : View.GONE);
        tvDetailCode.setText(code);

        // ========== 标题 ==========
        String title = item.getTitle();
        tvDetailTitle.setVisibility(title != null && !title.trim().isEmpty() ? View.VISIBLE : View.GONE);
        tvDetailTitle.setText(title);

        // ========== 标签 ==========
        String tag = item.getTag();
        tvDetailTag.setVisibility(tag != null && !tag.trim().isEmpty() ? View.VISIBLE : View.GONE);
        tvDetailTag.setText(tag);

        // ========== 评分 ==========
        refreshDetailStars();
        for (int i = 0; i < detailStars.length; i++) {
            final int star = i + 1;
            detailStars[i].setOnClickListener(v -> {
                reloadItemFromCache();  // 先获取后台刮削可能已更新的数据
                item.setRating(star);
                item.setUserEdited(true);
                refreshDetailStars();
                saveItemToCache();
            });
        }

        // ========== 编辑按钮 ==========
        btnEditDetail.setOnClickListener(v -> showEditDialog());

        // ========== 刮削按钮 ==========
        btnScrapeDetail.setOnClickListener(v -> {
            if (code != null && !code.trim().isEmpty()) {
                triggerScrape(code);
            } else {
                Toast.makeText(this, "无番号，无法刮削", Toast.LENGTH_SHORT).show();
            }
        });

        // ========== 清除按钮 ==========
        boolean hasScrapedData = (item.getTitle() != null && !item.getTitle().trim().isEmpty())
                || (item.getImageUrl() != null && !item.getImageUrl().trim().isEmpty());
        btnClearDetail.setVisibility(hasScrapedData ? View.VISIBLE : View.GONE);
        btnClearDetail.setOnClickListener(v -> {
            try {
                String p = item.resolveLocalImagePath(this);
                File f = new File(p);
                if (f.exists()) f.delete();
            } catch (Exception ignored) {}
            item.setTitle("");
            item.setImageUrl("");
            item.setLocalImagePath(null);
            item.setUserEdited(false);  // 清除同时重置编辑标记
            saveItemToCache();
            bindData();
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
        });

        // ========== 时间 ==========
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        tvDetailTime.setText(sdf.format(new Date(item.getCreateTime())));

        // ========== 内容 ==========
        String content = item.getContent();
        if (content != null && !content.trim().isEmpty()) {
            tvDetailContent.setText(content);
            llContentSection.setVisibility(View.VISIBLE);
        } else {
            llContentSection.setVisibility(View.GONE);
        }

        // ========== 备注 ==========
        String remark = item.getRemark();
        if (remark != null && !remark.trim().isEmpty()) {
            tvDetailRemark.setText(remark);
            llRemarkSection.setVisibility(View.VISIBLE);
        } else {
            llRemarkSection.setVisibility(View.GONE);
        }
    }

    private void refreshDetailStars() {
        int r = item.getRating();
        for (int i = 0; i < detailStars.length; i++) {
            detailStars[i].setImageResource(
                    i < r ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off
            );
        }
    }

    private void refreshDialogStars(ImageView[] stars, int rating) {
        for (int i = 0; i < stars.length; i++) {
            stars[i].setImageResource(
                    i < rating ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off
            );
        }
    }

    /**
     * 从 SharedPreferences 重新加载 item，获取后台刮削可能更新的数据。
     */
    private void reloadItemFromCache() {
        SharedPreferences sp = getSharedPreferences("app_cache", MODE_PRIVATE);
        String json = sp.getString("data", "[]");
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<CardItem>>() {}.getType();
        java.util.List<CardItem> list = new com.google.gson.Gson().fromJson(json, type);
        if (list == null) return;
        for (CardItem cached : list) {
            if (cached.getId().equals(item.getId())) {
                item = cached;
                return;
            }
        }
    }

    /**
     * 将当前 item 的本地编辑字段写回 SharedPreferences。
     * 先读取缓存中的最新数据，合并本地修改后再写回，避免覆盖刮削服务的并发更新。
     */
    private void saveItemToCache() {
        SharedPreferences sp = getSharedPreferences("app_cache", MODE_PRIVATE);
        String json = sp.getString("data", "[]");
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<CardItem>>() {}.getType();
        java.util.List<CardItem> list = new com.google.gson.Gson().fromJson(json, type);
        if (list == null) return;
        for (CardItem cached : list) {
            if (cached.getId().equals(item.getId())) {
                // 只在缓存中更新 DetailActivity 可能修改的字段，其余字段保留缓存最新值
                cached.setTitle(item.getTitle());
                cached.setContent(item.getContent());
                cached.setImageUrl(item.getImageUrl());
                cached.setTag(item.getTag());
                cached.setRemark(item.getRemark());
                cached.setRating(item.getRating());
                cached.setUserEdited(item.isUserEdited());
                // 同步本地引用到最新缓存对象
                item = cached;
                break;
            }
        }
        sp.edit().putString("data", new com.google.gson.Gson().toJson(list)).apply();
    }

    private void triggerScrape(String code) {
        final ScrapeSource scrapeSource = ScrapeSource.getDefault(this);
        ScrapeTask task = ScrapeTask.from(item, scrapeSource);
        ScrapeService.enqueue(this, task);
        LogUtil.i("详情页刮削已入队: " + code);
        Toast.makeText(this, "刮削已加入队列", Toast.LENGTH_SHORT).show();
    }

    private void showEditDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add, null);
        EditText etTitle = view.findViewById(R.id.etTitle);
        EditText etContent = view.findViewById(R.id.etContent);
        EditText etImageUrl = view.findViewById(R.id.etImageUrl);
        EditText etTag = view.findViewById(R.id.etTag);
        EditText etRemark = view.findViewById(R.id.etRemark);
        androidx.appcompat.widget.AppCompatButton btnPickImage = view.findViewById(R.id.btnPickImage);
        ImageView ivPreview = view.findViewById(R.id.ivImagePreview);
        // 隐藏 code、刮削开关行、刮削源选择器，详情页编辑不需要
        view.findViewById(R.id.etCode).setVisibility(View.GONE);
        view.findViewById(R.id.llScrapeRow).setVisibility(View.GONE);
        view.findViewById(R.id.spScrapeSource).setVisibility(View.GONE);

        etTitle.setText(item.getTitle());
        etContent.setText(item.getContent());
        etImageUrl.setText(item.getImageUrl());
        etTag.setText(item.getTag());
        etRemark.setText(item.getRemark());

        // 评分
        LinearLayout llRating = view.findViewById(R.id.llRating);
        ImageView[] editStars = new ImageView[]{
                view.findViewById(R.id.ivDialogStar1),
                view.findViewById(R.id.ivDialogStar2),
                view.findViewById(R.id.ivDialogStar3),
                view.findViewById(R.id.ivDialogStar4),
                view.findViewById(R.id.ivDialogStar5)
        };
        final int[] editRating = {item.getRating()};
        refreshDialogStars(editStars, editRating[0]);
        for (int si = 0; si < editStars.length; si++) {
            final int starValue = si + 1;
            editStars[si].setOnClickListener(v -> {
                editRating[0] = starValue;
                refreshDialogStars(editStars, editRating[0]);
            });
        }

        // 图片选择
        editDialogView = view;
        pendingImagePickUri = null;
        // 如果已有本地图片，显示预览
        String localPath = item.resolveLocalImagePath(this);
        File localFile = new File(localPath);
        if (localFile.exists()) {
            ivPreview.setVisibility(View.VISIBLE);
            Glide.with(this).load(localFile).dontTransform().into(ivPreview);
        }
        btnPickImage.setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        new AlertDialog.Builder(this)
                .setTitle("编辑卡片: " + item.getCode())
                .setView(view)
                .setPositiveButton("保存", (d, i) -> {
                    item.setTitle(etTitle.getText().toString().trim());
                    item.setContent(etContent.getText().toString().trim());
                    item.setImageUrl(etImageUrl.getText().toString().trim());
                    item.setTag(etTag.getText().toString().trim());
                    item.setRemark(etRemark.getText().toString().trim());
                    item.setRating(editRating[0]);
                    item.setUserEdited(true);  // 标记用户已编辑，刮削不再覆盖

                    // 复制选中的本地图片
                    if (pendingImagePickUri != null) {
                        copyImageToLocal(pendingImagePickUri, item.getId());
                        pendingImagePickUri = null;
                    }
                    editDialogView = null;

                    saveItemToCache();  // 持久化到 SharedPreferences
                    bindData();
                    Toast.makeText(this, "已保存（刮削将不再覆盖）", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", (d, i) -> {
                    pendingImagePickUri = null;
                    editDialogView = null;
                })
                .create()
                .show();
    }

    private void copyImageToLocal(Uri sourceUri, String cardId) {
        try {
            InputStream is = getContentResolver().openInputStream(sourceUri);
            if (is == null) {
                LogUtil.w("无法打开图片: " + sourceUri);
                return;
            }
            File dir = new File(getFilesDir(), "images");
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, cardId + ".jpg");
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.flush();
            fos.close();
            is.close();
            LogUtil.i("本地图片已保存: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            LogUtil.e("复制图片失败", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从缓存重新加载最新数据（后台刮削可能已更新）
        reloadItemFromCache();
        bindData();
        IntentFilter filter = new IntentFilter(ScrapeService.BROADCAST_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scrapeReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scrapeReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(scrapeReceiver);
        } catch (IllegalArgumentException ignored) {}
    }
}

package com.skyseec.avlist;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DetailActivity extends AppCompatActivity {

    private ImageView ivDetailImage;
    private TextView tvDetailCode, tvDetailTitle, tvDetailTag, tvDetailTime;
    private TextView tvDetailContent, tvDetailRemark;
    private AppCompatButton btnEditDetail, btnScrapeDetail, btnClearDetail;
    private LinearLayout llContentSection, llRemarkSection;
    private CardItem item;
    private X5WebView scrapeWebView;

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

        item = (CardItem) getIntent().getSerializableExtra("card_data");
        if (item == null) {
            finish();
            return;
        }

        // 创建隐形 WebView 用于刮削
        scrapeWebView = new X5WebView(this);
        scrapeWebView.setVisibility(View.GONE);
        ((ViewGroup) findViewById(android.R.id.content)).addView(
                scrapeWebView,
                new ViewGroup.LayoutParams(1, 1));

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

    private void triggerScrape(String code) {
        Toast.makeText(this, "正在刮削 " + code + " ...", Toast.LENGTH_SHORT).show();

        final ScrapeSource scrapeSource = ScrapeSource.getDefault(this);
        final boolean skipOverwrite = item.isUserEdited();

        if (skipOverwrite) {
            LogUtil.i("刮削跳过（用户已编辑）: " + code);
        }

        JavResolver resolver = new JavResolver(
                scrapeWebView,
                scrapeSource,
                (scrapeTitle, imgUrl, detailUrl) -> {

                    // 下载图片（不受 userEdited 影响，始终下载）
                    if (imgUrl != null && !imgUrl.isEmpty()) {
                        String resolvedUrl = scrapeSource.resolveImageUrl(imgUrl);
                        downloadImageToLocal(resolvedUrl);
                    }

                    runOnUiThread(() -> {
                        if (!skipOverwrite) {
                            item.setTitle(scrapeTitle);
                            item.setImageUrl(imgUrl);
                        } else {
                            LogUtil.i("刮削结果未覆盖: " + item.getCode());
                        }
                        bindData();
                        Toast.makeText(this, "刮削完成", Toast.LENGTH_SHORT).show();
                    });
                });

        resolver.startScrape(code);
    }

    private void downloadImageToLocal(String imageUrl) {
        if (!ScrapeSource.isValidImageUrl(imageUrl)) {
            LogUtil.w("图片URL无效，跳过下载: " + imageUrl);
            return;
        }
        new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                java.net.URL url = new java.net.URL(imageUrl);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                // 用刮削源的域名做 Referer（CDN 校验来源）
                ScrapeSource ds = ScrapeSource.getDefault(DetailActivity.this);
                connection.setRequestProperty("Referer", ds.getBaseDomain());
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.connect();

                if (connection.getResponseCode() == 200) {
                    java.io.InputStream is = connection.getInputStream();

                    File dir = new File(getFilesDir(), "images");
                    if (!dir.exists()) dir.mkdirs();

                    File outFile = new File(dir, item.getId() + ".jpg");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);

                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }

                    fos.flush();
                    fos.close();
                    is.close();

                    item.setLocalImagePath(outFile.getAbsolutePath());

                    runOnUiThread(this::bindData);
                }
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "图片下载失败", Toast.LENGTH_SHORT).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void showEditDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add, null);
        EditText etTitle = view.findViewById(R.id.etTitle);
        EditText etContent = view.findViewById(R.id.etContent);
        EditText etImageUrl = view.findViewById(R.id.etImageUrl);
        EditText etTag = view.findViewById(R.id.etTag);
        EditText etRemark = view.findViewById(R.id.etRemark);
        // 隐藏 code、刮削开关行、刮削源选择器，详情页编辑不需要
        view.findViewById(R.id.etCode).setVisibility(View.GONE);
        view.findViewById(R.id.llScrapeRow).setVisibility(View.GONE);
        view.findViewById(R.id.spScrapeSource).setVisibility(View.GONE);

        etTitle.setText(item.getTitle());
        etContent.setText(item.getContent());
        etImageUrl.setText(item.getImageUrl());
        etTag.setText(item.getTag());
        etRemark.setText(item.getRemark());

        new AlertDialog.Builder(this)
                .setTitle("编辑卡片: " + item.getCode())
                .setView(view)
                .setPositiveButton("保存", (d, i) -> {
                    item.setTitle(etTitle.getText().toString().trim());
                    item.setContent(etContent.getText().toString().trim());
                    item.setImageUrl(etImageUrl.getText().toString().trim());
                    item.setTag(etTag.getText().toString().trim());
                    item.setRemark(etRemark.getText().toString().trim());
                    item.setUserEdited(true);  // 标记用户已编辑，刮削不再覆盖
                    bindData();
                    Toast.makeText(this, "已保存（刮削将不再覆盖）", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create()
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scrapeWebView != null) {
            scrapeWebView.destroy();
        }
    }
}

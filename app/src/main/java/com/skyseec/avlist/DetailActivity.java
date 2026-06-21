package com.skyseec.avlist;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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
    private AppCompatButton btnScrapeDetail, btnClearDetail;
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

        // ========== 刮削 / 清除按钮 ==========
        btnScrapeDetail.setOnClickListener(v -> {
            if (code != null && !code.trim().isEmpty()) {
                triggerScrape(code);
            } else {
                Toast.makeText(this, "无番号，无法刮削", Toast.LENGTH_SHORT).show();
            }
        });

        boolean hasScrapedData = (item.getTitle() != null && !item.getTitle().trim().isEmpty())
                || (item.getImageUrl() != null && !item.getImageUrl().trim().isEmpty());
        btnClearDetail.setVisibility(hasScrapedData ? View.VISIBLE : View.GONE);
        btnClearDetail.setOnClickListener(v -> {
            // 删除本地图片
            try {
                String p = item.resolveLocalImagePath(this);
                File f = new File(p);
                if (f.exists()) f.delete();
            } catch (Exception ignored) {}
            item.setTitle("");
            item.setImageUrl("");
            item.setLocalImagePath(null);
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

        JavResolver resolver = new JavResolver(
                scrapeWebView,
                scrapeSource,
                (scrapeTitle, imgUrl, detailUrl) -> {

                    // 下载新图片到本地（覆盖旧图）
                    if (imgUrl != null && !imgUrl.isEmpty()) {
                        String resolvedUrl = scrapeSource.resolveImageUrl(imgUrl);
                        downloadImageToLocal(resolvedUrl);
                    }

                    runOnUiThread(() -> {
                        item.setTitle(scrapeTitle);
                        item.setImageUrl(imgUrl);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scrapeWebView != null) {
            scrapeWebView.destroy();
        }
    }
}

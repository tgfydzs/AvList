package com.skyseec.avlist;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyseec.avlist.databinding.ActivityMainBinding;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements CardAdapter.OnItemSelectListener {
    private ActivityMainBinding binding;
    private List<CardItem> cardList = new ArrayList<>();
    private List<CardItem> originList = new ArrayList<>();
    private List<CardItem> selectedList = new ArrayList<>();
    private CardAdapter adapter;
    private final Gson gson = new Gson();
    private boolean isMultiMode = false;
    private X5WebView verifyWebView;

    private final ActivityResultLauncher<Intent> selectFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    readJsonFromUri(uri);
                }
            }
    );

    // 导出用的 launcher：SAF 创建文件后写入
    private String pendingExportJson = null;
    private boolean pendingMoveOut = false;

    private final ActivityResultLauncher<Intent> createExportFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingExportJson != null) {
                    Uri uri = result.getData().getData();
                    writeJsonToUri(uri, pendingExportJson);
                    if (pendingMoveOut) {
                        performMoveOutDelete();
                    }
                    pendingExportJson = null;
                    pendingMoveOut = false;
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 第一步：先初始化ViewBinding，再使用binding里所有控件
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 初始化日志
        LogUtil.init(new File(getFilesDir(), "logs"));

        LogUtil.i("应用启动");

        // 动态创建WebView，不再依赖XML的 wv_verify_container
        verifyWebView = new X5WebView(this);
        CoordinatorLayout.LayoutParams layoutParams = new CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT
        );
        verifyWebView.setVisibility(View.GONE);
        // 添加到根布局
        binding.getRoot().addView(verifyWebView, layoutParams);

        initRecyclerView();
        loadCacheData();

        // 返回键逻辑不变
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isMultiMode) {
                    exitMultiMode();
                } else if (verifyWebView.getVisibility() == View.VISIBLE) {
                    hideVerifyWebView();
                    verifyWebView.stopLoading();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // 搜索栏
        binding.btnMore.setOnClickListener(this::showPopupMenu);

        EditText etSearch = binding.getRoot().findViewById(R.id.etSearch);
        ImageView ivClear = binding.getRoot().findViewById(R.id.ivClear);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterData(s.toString());
                ivClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                filterData(v.getText().toString());
                return true;
            }
            return false;
        });

        ivClear.setOnClickListener(v -> {
            etSearch.setText("");
            filterData("");
        });
        binding.btnBatchDelete.setOnClickListener(v -> batchDelete());
        binding.btnBatchMoveOut.setOnClickListener(v -> batchMoveOut());
        binding.btnBatchExport.setOnClickListener(v -> batchExport());
        binding.fabAdd.setOnClickListener(v -> showAddDialog());
    }

    // 显示验证WebView
    public void showVerifyWebView() {
        runOnUiThread(() -> verifyWebView.setVisibility(View.VISIBLE));
    }

    public void hideVerifyWebView() {
        runOnUiThread(() -> verifyWebView.setVisibility(View.GONE));
    }

    @Override
    public void onItemClick(int position) {
        CardItem cardItem = cardList.get(position);
        Intent intent = new Intent(MainActivity.this, DetailActivity.class);
        intent.putExtra("card_data", cardItem);
        startActivity(intent);
    }

    private void initRecyclerView() {
        adapter = new CardAdapter(this, cardList, selectedList);
        adapter.setOnItemSelectListener(this);
        StaggeredGridLayoutManager manager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        manager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_NONE);
        binding.recyclerView.setLayoutManager(manager);
        binding.recyclerView.setAdapter(adapter);
    }

    private void showAddDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add, null);

        EditText etCode = view.findViewById(R.id.etCode);
        EditText etTitle = view.findViewById(R.id.etTitle);
        EditText etContent = view.findViewById(R.id.etContent);
        EditText etImageUrl = view.findViewById(R.id.etImageUrl);
        EditText etTag = view.findViewById(R.id.etTag);
        EditText etRemark = view.findViewById(R.id.etRemark);
        SwitchCompat swScrape = view.findViewById(R.id.swScrape);

        builder.setView(view)
                .setPositiveButton("添加", (dialogInterface, i) -> {

                    String code = etCode.getText().toString().trim();
                    String title = etTitle.getText().toString().trim();
                    String content = etContent.getText().toString().trim();
                    String imageUrl = etImageUrl.getText().toString().trim();
                    String tag = etTag.getText().toString().trim();
                    String remark = etRemark.getText().toString().trim();

                    // =========================
                    // 番号标准化：提取字母和数字，转大写
                    // =========================
                    String normalizedCode = CardItem.normalizeCode(code);

                    LogUtil.i("==== 添加开始 ====");
                    LogUtil.i("code=" + code + " -> normalized=" + normalizedCode);

                    // =========================
                    // 唯一性验证：空番号不作重复检查
                    // =========================
                    if (!normalizedCode.isEmpty()) {
                        for (CardItem item : originList) {
                            if (item.isSameCode(code)) {
                                Toast.makeText(this, "番号已存在", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                    }

                    // =========================
                    // 1. 先创建卡片（立即显示）
                    // =========================
                    final boolean doScrape = swScrape.isChecked() && !normalizedCode.isEmpty();

                    CardItem item = new CardItem(
                            UUID.randomUUID().toString(),
                            title.isEmpty() ? "" : title,
                            content,
                            imageUrl,
                            System.currentTimeMillis(),
                            remark,
                            tag,
                            normalizedCode,
                            swScrape.isChecked()
                    );

                    dialogInterface.dismiss();

                    originList.add(0, item);
                    cardList.add(0, item);
                    adapter.notifyItemInserted(0);
                    saveCacheData();
                    LogUtil.i("添加卡片: code=" + normalizedCode + " scrape=" + doScrape);

                    // =========================
                    // 2. 异步刮削（仅在开关打开且番号非空时）
                    // =========================
                    if (doScrape) {
                        final int index = 0;
                        final CardItem finalItem = item;

                        JavResolver resolver = new JavResolver(
                                verifyWebView,
                                (scrapeTitle, imgUrl, detailUrl) -> {

                                    LogUtil.i("==== 刮削完成 ====");
                                    LogUtil.i("title=" + scrapeTitle);
                                    LogUtil.i("img=" + imgUrl);

                                    // =========================
                                    // 下载图片到本地存储
                                    // =========================
                                    if (imgUrl != null && !imgUrl.isEmpty()) {
                                        String resolvedUrl = imgUrl.startsWith("/")
                                                ? "https://www.javbus.com" + imgUrl
                                                : imgUrl;
                                        downloadImageToLocal(finalItem, resolvedUrl);
                                    }

                                    runOnUiThread(() -> {
                                        if (scrapeTitle != null) finalItem.setTitle(scrapeTitle);
                                        if (imgUrl != null) finalItem.setImageUrl(imgUrl);

                                        adapter.notifyItemChanged(index);
                                        saveCacheData();
                                    });
                                });

                        resolver.startScrape(code);
                    }
                })
                .setNegativeButton("取消", (d, i) -> d.dismiss());

        builder.create().show();
    }

    private void showPopupMenu(View view) {
        PopupMenu popupMenu = new PopupMenu(this, view);
        popupMenu.getMenuInflater().inflate(R.menu.menu_more, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_export) {
                exportJsonToDownload();
            } else if (item.getItemId() == R.id.menu_import) {
                openFileChooser();
            } else if (item.getItemId() == R.id.menu_import_txt) {
                showImportCodeListDialog();
            } else if (item.getItemId() == R.id.menu_multi_select) {
                openMultiSelectMode();
            } else if (item.getItemId() == R.id.menu_log) {
                showLogDialog();
            }
            return true;
        });
        popupMenu.show();
    }

    private void openMultiSelectMode() {
        isMultiMode = true;
        selectedList.clear();
        adapter.setMultiSelectMode(true);
        binding.llBottomAction.setVisibility(View.VISIBLE);
        binding.fabAdd.setVisibility(View.GONE);
    }

    private void batchDelete() {
        if (selectedList.isEmpty()) {
            Toast.makeText(this, "请先选择卡片", Toast.LENGTH_SHORT).show();
            return;
        }
        // 删除关联的本地图片文件
        for (CardItem item : selectedList) {
            deleteLocalImage(item);
            LogUtil.i("删除卡片: " + item.getCode());
        }
        originList.removeAll(selectedList);
        cardList.removeAll(selectedList);
        selectedList.clear();
        adapter.notifyDataSetChanged();
        saveCacheData();
        exitMultiMode();
        Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
    }

    private void batchExport() {
        if (selectedList.isEmpty()) {
            Toast.makeText(this, "请先选择卡片", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pendingExportJson = gson.toJson(selectedList);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "batch_backup.json");
            createExportFileLauncher.launch(intent);
            exitMultiMode();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void batchMoveOut() {
        if (selectedList.isEmpty()) {
            Toast.makeText(this, "请先选择卡片", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pendingMoveOut = true;
            pendingExportJson = gson.toJson(selectedList);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "batch_backup.json");
            createExportFileLauncher.launch(intent);
            // 不在这里 exitMultiMode 和删除 — 等 SAF 回调
        } catch (Exception e) {
            pendingMoveOut = false;
            pendingExportJson = null;
            Toast.makeText(this, "移出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void performMoveOutDelete() {
        // 删除本地图片
        for (CardItem item : selectedList) {
            deleteLocalImage(item);
            LogUtil.i("移出卡片: " + item.getCode());
        }
        originList.removeAll(selectedList);
        cardList.removeAll(selectedList);
        selectedList.clear();
        adapter.notifyDataSetChanged();
        saveCacheData();
        exitMultiMode();
        Toast.makeText(this, "移出成功", Toast.LENGTH_SHORT).show();
    }

    private void exitMultiMode() {
        isMultiMode = false;
        selectedList.clear();
        adapter.setMultiSelectMode(false);
        binding.llBottomAction.setVisibility(View.GONE);
        binding.fabAdd.setVisibility(View.VISIBLE);
    }

    @Override
    public void onItemSelected(int position, boolean isChecked) {
        CardItem item = cardList.get(position);
        if (isChecked) {
            if (!selectedList.contains(item)) selectedList.add(item);
        } else {
            selectedList.remove(item);
        }
    }

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        selectFileLauncher.launch(intent);
    }

    private void showImportCodeListDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_import_codes, null);
        EditText etCodes = view.findViewById(R.id.etCodes);

        builder.setTitle("导入番号列表")
                .setView(view)
                .setPositiveButton("导入", (dialog, which) -> {
                    String text = etCodes.getText().toString();
                    if (!text.trim().isEmpty()) {
                        importCodeListFromText(text);
                    }
                })
                .setNegativeButton("取消", null)
                .create()
                .show();
    }

    private void readJsonFromUri(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            inputStream.close();

            Type type = new TypeToken<List<CardItem>>() {}.getType();
            List<CardItem> importedList = gson.fromJson(sb.toString(), type);

            if (importedList == null || importedList.isEmpty()) {
                Toast.makeText(this, "导入文件无数据", Toast.LENGTH_SHORT).show();
                return;
            }

            int addCount = 0;
            int skipCount = 0;
            int invalidCodeCount = 0;

            for (CardItem importItem : importedList) {
                String importCode = importItem.getCode();
                if (importCode == null || importCode.trim().isEmpty()) {
                    invalidCodeCount++;
                    continue;
                }

                // =========================
                // 导入时统一标准化番号：提取字母数字，转大写
                // =========================
                String normalizedCode = CardItem.normalizeCode(importCode);
                importItem.setCode(normalizedCode);

                boolean isExist = false;
                for (CardItem localItem : originList) {
                    if (localItem.isSameCode(normalizedCode)) {
                        isExist = true;
                        break;
                    }
                }

                if (isExist) {
                    skipCount++;
                } else {
                    originList.add(importItem);
                    addCount++;
                }
            }

            cardList.clear();
            cardList.addAll(originList);
            adapter.notifyDataSetChanged();
            saveCacheData();

            String msg = String.format("导入完成：\n新增%d条，重复跳过%d条，辨识码无效%d条",
                    addCount, skipCount, invalidCodeCount);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导入失败，文件格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    private void importCodeListFromText(String text) {
        String[] lines = text.split("\\r?\\n");
        int addCount = 0;
        int skipCount = 0;
        int invalidCount = 0;
        List<CardItem> newItems = new ArrayList<>();

        for (String rawLine : lines) {
            String code = rawLine.trim();
            if (code.isEmpty()) continue;

            String normalizedCode = CardItem.normalizeCode(code);
            if (normalizedCode.isEmpty()) {
                invalidCount++;
                continue;
            }

            boolean exists = false;
            for (CardItem localItem : originList) {
                if (localItem.isSameCode(normalizedCode)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                skipCount++;
                continue;
            }

            CardItem newItem = new CardItem(
                    UUID.randomUUID().toString(),
                    "", "", "",
                    System.currentTimeMillis(),
                    "", "",
                    normalizedCode,
                    true
            );
            originList.add(0, newItem);
            newItems.add(newItem);
            addCount++;
        }

        cardList.clear();
        cardList.addAll(originList);
        adapter.notifyDataSetChanged();
        saveCacheData();

        // 逐条刮削，间隔3秒
        for (int i = 0; i < newItems.size(); i++) {
            final CardItem finalItem = newItems.get(i);
            final String scrapeCode = finalItem.getCode();

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                JavResolver resolver = new JavResolver(
                        verifyWebView,
                        (scrapeTitle, imgUrl, detailUrl) -> {
                            if (imgUrl != null && !imgUrl.isEmpty()) {
                                String resolvedUrl = imgUrl.startsWith("/")
                                        ? "https://www.javbus.com" + imgUrl : imgUrl;
                                downloadImageToLocal(finalItem, resolvedUrl);
                            }
                            if (scrapeTitle != null) finalItem.setTitle(scrapeTitle);
                            if (imgUrl != null) finalItem.setImageUrl(imgUrl);

                            runOnUiThread(() -> {
                                int displayIdx = cardList.indexOf(finalItem);
                                if (displayIdx >= 0) adapter.notifyItemChanged(displayIdx);
                                saveCacheData();
                            });
                        });
                resolver.startScrape(scrapeCode);
            }, i * 3000L);
        }

        String msg = String.format("导入完成：\n新增%d条，重复跳过%d条，无效%d条",
                addCount, skipCount, invalidCount);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void exportJsonToDownload() {
        try {
            pendingExportJson = gson.toJson(originList);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "avlist_backup.json");
            createExportFileLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeJsonToUri(Uri uri, String json) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                osw.write(json);
                osw.flush();
                osw.close();
                os.close();
                Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "导出失败：无法打开文件", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void filterData(String keyword) {
        cardList.clear();
        String raw = (keyword == null ? "" : keyword).trim();
        if (raw.isEmpty()) {
            cardList.addAll(originList);
        } else {
            // 按空格拆成多个 token，每个 token 必须在至少一个字段中命中
            String[] tokens = raw.toLowerCase().split("\\s+");

            for (CardItem item : originList) {
                String title = item.getTitle() == null ? "" : item.getTitle().toLowerCase();
                String tag = item.getTag() == null ? "" : item.getTag().toLowerCase();
                String code = item.getCode() == null ? "" : item.getCode().toLowerCase();
                String remark = item.getRemark() == null ? "" : item.getRemark().toLowerCase();
                String content = item.getContent() == null ? "" : item.getContent().toLowerCase();

                boolean allMatch = true;
                for (String token : tokens) {
                    if (token.isEmpty()) continue;
                    if (title.contains(token)
                            || tag.contains(token)
                            || code.contains(token)
                            || remark.contains(token)
                            || content.contains(token)) {
                        continue;
                    }
                    allMatch = false;
                    break;
                }

                if (allMatch) {
                    cardList.add(item);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void saveCacheData() {
        String json = gson.toJson(originList);
        getSharedPreferences("app_cache", MODE_PRIVATE)
                .edit()
                .putString("data", json)
                .apply();
    }

    private void loadCacheData() {
        String json = getSharedPreferences("app_cache", MODE_PRIVATE)
                .getString("data", "[]");
        Type type = new TypeToken<List<CardItem>>() {}.getType();
        List<CardItem> cacheList = gson.fromJson(json, type);
        originList.clear();
        originList.addAll(cacheList);
        cardList.clear();
        cardList.addAll(cacheList);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onItemLongClick(int position) {
        if (isMultiMode) {
            return;
        }
        isMultiMode = true;
        selectedList.clear();
        adapter.setMultiSelectMode(true);
        binding.llBottomAction.setVisibility(View.VISIBLE);
        binding.fabAdd.setVisibility(View.GONE);

        CardItem selectItem = cardList.get(position);
        selectedList.add(selectItem);
        adapter.notifyItemChanged(position);
    }

    @Override
    public void onScrapeToggle(int position, boolean enabled) {
        CardItem item = cardList.get(position);
        item.setScrapeEnabled(enabled);
        saveCacheData();

        // 如果开启刮削且有番号但无标题/图片，触发刮削
        if (enabled) {
            String code = item.getCode();
            if (code != null && !code.trim().isEmpty()) {
                final int index = originList.indexOf(item);
                if (index < 0) return;

                final CardItem finalItem = item;

                JavResolver resolver = new JavResolver(
                        verifyWebView,
                        (scrapeTitle, imgUrl, detailUrl) -> {

                            LogUtil.i("==== 卡片刮削 ====");
                            LogUtil.i("title=" + scrapeTitle);
                            LogUtil.i("img=" + imgUrl);

                            // 下载图片到本地
                            if (imgUrl != null && !imgUrl.isEmpty()) {
                                String resolvedUrl = imgUrl.startsWith("/")
                                        ? "https://www.javbus.com" + imgUrl
                                        : imgUrl;
                                downloadImageToLocal(finalItem, resolvedUrl);
                            }

                            runOnUiThread(() -> {
                                if (scrapeTitle != null) finalItem.setTitle(scrapeTitle);
                                if (imgUrl != null) finalItem.setImageUrl(imgUrl);

                                int displayIdx = cardList.indexOf(finalItem);
                                if (displayIdx >= 0) adapter.notifyItemChanged(displayIdx);
                                saveCacheData();
                            });
                        });

                resolver.startScrape(code);
            }
        }
    }

    // =========================
    // 下载网络图片到内部存储
    // =========================
    private void downloadImageToLocal(CardItem item, String imageUrl) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(imageUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                connection.setRequestProperty("Referer", "https://www.javbus.com/");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.connect();

                if (connection.getResponseCode() == 200) {
                    InputStream is = connection.getInputStream();

                    File dir = new File(getFilesDir(), "images");
                    if (!dir.exists()) dir.mkdirs();

                    File outFile = new File(dir, item.getId() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(outFile);

                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }

                    fos.flush();
                    fos.close();
                    is.close();

                    item.setLocalImagePath(outFile.getAbsolutePath());

                    LogUtil.i("图片已保存到本地: " + outFile.getAbsolutePath());

                    // 刷新卡片显示
                    runOnUiThread(() -> {
                        int idx = cardList.indexOf(item);
                        if (idx >= 0) adapter.notifyItemChanged(idx);
                    });
                }
            } catch (Exception e) {
                LogUtil.e("图片下载失败", e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    // =========================
    // 删除本地图片文件
    // =========================
    private void deleteLocalImage(CardItem item) {
        try {
            String path = item.resolveLocalImagePath(this);
            File file = new File(path);
            if (file.exists()) {
                boolean deleted = file.delete();
                LogUtil.i("本地图片删除" + (deleted ? "成功" : "失败") + ": " + path);
            }
        } catch (Exception e) {
            LogUtil.e("删除本地图片异常", e);
        }
    }

    private void showLogDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_log, null);
        TextView tvLog = view.findViewById(R.id.tvLog);
        androidx.appcompat.widget.AppCompatButton btnExport = view.findViewById(R.id.btnExportLog);
        androidx.appcompat.widget.AppCompatButton btnClear = view.findViewById(R.id.btnClearLog);

        tvLog.setText(LogUtil.getAllText());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("关闭", null)
                .create();

        btnClear.setOnClickListener(v -> {
            LogUtil.clear();
            tvLog.setText("");
            LogUtil.i("日志已清空");
            tvLog.setText(LogUtil.getAllText());
        });

        btnExport.setOnClickListener(v -> {
            File logFile = LogUtil.getLogFile();
            if (logFile != null && logFile.exists()) {
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, "avlist_log.txt");
                    createExportLogLauncher.launch(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "暂无日志文件", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.show();
    }

    // 日志导出 launcher
    private String pendingExportLogPath = null;
    private final ActivityResultLauncher<Intent> createExportLogLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    File logFile = LogUtil.getLogFile();
                    if (logFile != null && logFile.exists()) {
                        try {
                            OutputStream os = getContentResolver().openOutputStream(uri);
                            if (os != null) {
                                InputStream is = new java.io.FileInputStream(logFile);
                                byte[] buf = new byte[4096];
                                int len;
                                while ((len = is.read(buf)) != -1) {
                                    os.write(buf, 0, len);
                                }
                                is.close();
                                os.flush();
                                os.close();
                                Toast.makeText(this, "日志已导出", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
    );

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtil.i("应用关闭");
        if (verifyWebView != null) {
            verifyWebView.destroy();
        }
    }
}
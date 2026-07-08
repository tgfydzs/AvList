package com.skyseec.avlist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import com.bumptech.glide.Glide;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements CardAdapter.OnItemSelectListener {
    private ActivityMainBinding binding;
    private List<CardItem> cardList = new ArrayList<>();
    private List<CardItem> originList = new ArrayList<>();
    private List<CardItem> selectedList = new ArrayList<>();
    private CardAdapter adapter;
    private final Gson gson = new Gson();
    // 静态标记：快捷设置瓷砖请求弹出新增对话框
    public static boolean pendingShowAddDialog = false;

    private boolean isMultiMode = false;
    private DrawerLayout drawerLayout;
    private X5WebView verifyWebView;

    // 排序状态
    private enum SortField { RATING, JUMP_COUNT, TIME, CODE }
    private SortField currentSortField = SortField.TIME;
    private boolean sortAscending = false;  // false=降序(默认), true=升序
    private String currentSearchKeyword = "";

    // 刮削进度广播接收器
    private final BroadcastReceiver scrapeProgressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadCacheData();
        }
    };

    // 系统图片选择器（注意：currentAddDialogView 必须在 pickImageLauncher 之前声明）
    private View currentAddDialogView;
    private Uri pendingImagePickUri;
    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    pendingImagePickUri = uri;
                    if (currentAddDialogView != null) {
                        ImageView preview = currentAddDialogView.findViewById(R.id.ivImagePreview);
                        if (preview != null) {
                            preview.setVisibility(View.VISIBLE);
                            Glide.with(MainActivity.this).load(uri).dontTransform().into(preview);
                        }
                    }
                }
            }
    );

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
                    BackupManager.recordBackupDone(MainActivity.this);
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

        // 动态创建WebView，添加到内容区（不覆盖侧边栏）
        verifyWebView = new X5WebView(this);
        CoordinatorLayout.LayoutParams layoutParams = new CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT
        );
        verifyWebView.setVisibility(View.GONE);
        binding.contentLayout.addView(verifyWebView, layoutParams);

        initRecyclerView();
        loadCacheData();

        // 获取DrawerLayout
        drawerLayout = binding.drawerLayout;

        // 增大右边缘滑动检测区域：DrawerLayout默认只检测20dp边缘，
        // 通过反射拿到内部 ViewDragHelper 把边缘宽度拉到 80dp
        try {
            float density = getResources().getDisplayMetrics().density;
            int edgeSizePx = (int) (80 * density);
            java.lang.reflect.Field rdField = DrawerLayout.class.getDeclaredField("mRightDragger");
            rdField.setAccessible(true);
            Object rightDragger = rdField.get(drawerLayout);
            if (rightDragger != null) {
                Class<?> vdhClass = rightDragger.getClass();
                java.lang.reflect.Field esField = vdhClass.getDeclaredField("mEdgeSize");
                esField.setAccessible(true);
                esField.setInt(rightDragger, edgeSizePx);
            }
        } catch (Exception e) {
            LogUtil.e("无法设置抽屉滑动宽度", e);
        }

        // 返回键逻辑
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isMultiMode) {
                    exitMultiMode();
                } else if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    drawerLayout.closeDrawer(GravityCompat.END);
                } else if (verifyWebView.getVisibility() == View.VISIBLE) {
                    hideVerifyWebView();
                    verifyWebView.stopLoading();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // 增大右边缘滑动检测区域
        int edgeSize = Math.max(getResources().getDisplayMetrics().widthPixels / 6, 80);
        try {
            java.lang.reflect.Field field = DrawerLayout.class.getDeclaredField("mMinDrawerMargin");
            field.setAccessible(true);
            field.set(drawerLayout, edgeSize);
        } catch (Exception ignored) {}

        // 侧边栏菜单
        binding.btnMore.setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.END));

        binding.btnSort.setOnClickListener(v -> showSortDialog());

        // 右滑打开抽屉：触摸起点在屏幕30%位置右侧，左滑超过40dp触发
        binding.contentLayout.setOnTouchListener(
                new DrawerEdgeHelper(drawerLayout, 0.3f, 40)
        );

        binding.drawerExport.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            exportJsonToDownload();
        });
        binding.drawerImport.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            openFileChooser();
        });
        binding.drawerImportTxt.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            showImportCodeListDialog();
        });
        binding.drawerMultiSelect.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            openMultiSelectMode();
        });
        binding.drawerJumpSource.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            showJumpSourceDialog();
        });
        binding.drawerScrapeSource.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            showScrapeSourceDialog();
        });
        binding.drawerBackup.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            showBackupSettingsDialog();
        });
        binding.drawerLog.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            showLogDialog();
        });
        binding.drawerGuide.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            showGuideDialog();
        });

        EditText etSearch = binding.etSearch;
        ImageView ivClear = binding.ivClear;

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

        // 启动时检查是否需要备份（按日期差判定）
        BackupManager.checkAndBackup(this);

        // 首次启动弹窗
        checkFirstLaunch();
    }

    /**
     * 首次启动显示使用说明弹窗
     */
    private void checkFirstLaunch() {
        SharedPreferences sp = getSharedPreferences("guide_prefs", MODE_PRIVATE);
        boolean shown = sp.getBoolean("guide_shown", false);
        if (!shown) {
            sp.edit().putBoolean("guide_shown", true).apply();
            new Handler(Looper.getMainLooper()).postDelayed(this::showGuideDialog, 600);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        tryShowAddFromTile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryShowAddFromTile();

        // 注册刮削进度广播（Android 14+ 需指定 flag）
        IntentFilter filter = new IntentFilter(ScrapeService.BROADCAST_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scrapeProgressReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scrapeProgressReceiver, filter);
        }

        // 刷新数据（后台刮削可能已更新）
        loadCacheData();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 取消注册广播
        try {
            unregisterReceiver(scrapeProgressReceiver);
        } catch (IllegalArgumentException ignored) {}
    }

    private void tryShowAddFromTile() {
        if (!pendingShowAddDialog || binding == null) return;
        pendingShowAddDialog = false;
        try {
            new Handler(Looper.getMainLooper()).postDelayed(this::showAddDialog, 400);
        } catch (Exception e) {
            LogUtil.e("快捷磁貼弹窗失败", e);
        }
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
        Spinner spScrapeSource = view.findViewById(R.id.spScrapeSource);
        androidx.appcompat.widget.AppCompatButton btnPickImage = view.findViewById(R.id.btnPickImage);
        ImageView ivPreview = view.findViewById(R.id.ivImagePreview);
        LinearLayout llRating = view.findViewById(R.id.llRating);
        ImageView[] dialogStars = new ImageView[]{
                view.findViewById(R.id.ivDialogStar1),
                view.findViewById(R.id.ivDialogStar2),
                view.findViewById(R.id.ivDialogStar3),
                view.findViewById(R.id.ivDialogStar4),
                view.findViewById(R.id.ivDialogStar5)
        };
        final int[] addRating = {0};
        for (int si = 0; si < dialogStars.length; si++) {
            final int starValue = si + 1;
            dialogStars[si].setOnClickListener(v -> {
                addRating[0] = starValue;
                for (int j = 0; j < dialogStars.length; j++) {
                    dialogStars[j].setImageResource(
                            j < starValue ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off
                    );
                }
            });
        }

        // 保存引用以便 pickImageLauncher 回调刷新预览
        currentAddDialogView = view;
        pendingImagePickUri = null;

        // 图片选择按钮
        btnPickImage.setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        // 填充刮削源选择器
        List<ScrapeSource> allSources = ScrapeSource.loadAll(this);
        ScrapeSource defaultSrc = ScrapeSource.getDefault(this);
        int defaultIdx = 0;
        String[] sourceNames = new String[allSources.size()];
        for (int si = 0; si < allSources.size(); si++) {
            sourceNames[si] = allSources.get(si).getName();
            if (allSources.get(si).getId().equals(defaultSrc.getId())) defaultIdx = si;
        }
        ArrayAdapter<String> srcAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, sourceNames);
        srcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spScrapeSource.setAdapter(srcAdapter);
        spScrapeSource.setSelection(defaultIdx);

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
                    item.setRating(addRating[0]);

                    // 提前捕获刮削源（dismiss前）
                    final int selectedSrcIdx = spScrapeSource.getSelectedItemPosition();
                    final ScrapeSource selectedSrc = (selectedSrcIdx >= 0 && selectedSrcIdx < allSources.size())
                            ? allSources.get(selectedSrcIdx) : ScrapeSource.getDefault(MainActivity.this);

                    dialogInterface.dismiss();

                    // 复制选中的本地图片
                    if (pendingImagePickUri != null) {
                        copyImageToLocal(pendingImagePickUri, item.getId());
                        pendingImagePickUri = null;
                    }
                    currentAddDialogView = null;

                    originList.add(0, item);
                    cardList.add(0, item);
                    adapter.notifyItemInserted(0);
                    saveCacheData();
                    LogUtil.i("添加卡片: code=" + normalizedCode + " scrape=" + doScrape + " 源=" + selectedSrc.getName());

                    // =========================
                    // 2. 异步刮削（仅在开关打开且番号非空时）
                    // =========================
                    if (doScrape) {
                        // 入队到后台刮削服务
                        ScrapeTask task = ScrapeTask.from(item, selectedSrc);
                        ScrapeService.enqueue(MainActivity.this, task);
                        LogUtil.i("刮削任务已入队: " + normalizedCode);
                    }
                })
                .setNegativeButton("取消", (d, i) -> {
                    pendingImagePickUri = null;
                    currentAddDialogView = null;
                    d.dismiss();
                });

        builder.create().show();
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

            applySortAndFilter();
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

        applySortAndFilter();
        saveCacheData();

        // =========================
        // 批量入队后台刮削服务（服务内部串行处理）
        // =========================
        if (!newItems.isEmpty()) {
            final ScrapeSource defaultSource = ScrapeSource.getDefault(MainActivity.this);
            List<ScrapeTask> tasks = new ArrayList<>();
            for (CardItem item : newItems) {
                tasks.add(ScrapeTask.from(item, defaultSource));
            }
            ScrapeService.enqueueAll(MainActivity.this, tasks);
            LogUtil.i("批量导入: " + tasks.size() + " 个刮削任务已入队");
        }

        String msg = String.format("导入完成：\n新增%d条，重复跳过%d条，无效%d条",
                addCount, skipCount, invalidCount);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void exportJsonToDownload() {
        try {
            pendingExportJson = BackupManager.getDataJson(this);
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
        currentSearchKeyword = (keyword == null ? "" : keyword).trim();
        applySortAndFilter();
    }

    private void applySortAndFilter() {
        // 1. 先过滤
        List<CardItem> filtered;
        if (currentSearchKeyword.isEmpty()) {
            filtered = new ArrayList<>(originList);
        } else {
            filtered = new ArrayList<>();
            String[] tokens = currentSearchKeyword.toLowerCase().split("\\s+");

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
                    filtered.add(item);
                }
            }
        }

        // 2. 再排序
        java.util.Comparator<CardItem> comparator;
        switch (currentSortField) {
            case RATING:
                comparator = Comparator.comparingInt(CardItem::getRating);
                break;
            case JUMP_COUNT:
                comparator = Comparator.comparingInt(CardItem::getJumpCount);
                break;
            case TIME:
                comparator = Comparator.comparingLong(CardItem::getCreateTime);
                break;
            case CODE:
            default:
                comparator = Comparator.comparing(c ->
                        c.getCode() != null ? CardItem.normalizeCode(c.getCode()) : "");
                break;
        }
        if (!sortAscending) {
            comparator = comparator.reversed();
        }
        // 二级排序：同星级/同时间/同首字母时按时间降序
        if (currentSortField != SortField.TIME) {
            comparator = comparator.thenComparing(
                    java.util.Comparator.comparingLong(CardItem::getCreateTime).reversed());
        }
        filtered.sort(comparator);

        cardList.clear();
        cardList.addAll(filtered);
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
        applySortAndFilter();
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
    public void onItemScrape(int position) {
        CardItem item = cardList.get(position);
        String code = item.getCode();
        if (code == null || code.trim().isEmpty()) {
            Toast.makeText(this, "无番号，无法刮削", Toast.LENGTH_SHORT).show();
            return;
        }

        final ScrapeSource scrapeSource = ScrapeSource.getDefault(MainActivity.this);
        ScrapeTask task = ScrapeTask.from(item, scrapeSource);
        ScrapeService.enqueue(MainActivity.this, task);
        LogUtil.i("手动刮削已入队: " + code);
        Toast.makeText(this, "刮削已加入队列", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onItemClear(int position) {
        CardItem item = cardList.get(position);
        LogUtil.i("清除卡片数据: " + item.getCode());
        deleteLocalImage(item);
        item.setTitle("");
        item.setImageUrl("");
        item.setLocalImagePath(null);

        int displayIdx = cardList.indexOf(item);
        if (displayIdx >= 0) adapter.notifyItemChanged(displayIdx);
        saveCacheData();
    }

    @Override
    public void onItemJump(int position) {
        CardItem item = cardList.get(position);
        String code = item.getCode();
        if (code == null || code.trim().isEmpty()) return;

        // 记录跳转次数
        item.setJumpCount(item.getJumpCount() + 1);
        LogUtil.i("跳转: " + code + " 次数=" + item.getJumpCount());
        saveCacheData();

        // 执行跳转
        JumpSource jumpSource = JumpSource.getCurrent(this);
        String url = jumpSource.buildUrl(CardItem.normalizeCode(code));
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    // =========================
    // 从系统相册复制图片到内部存储
    // =========================
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

    // =========================
    // 排序对话框
    // =========================
    private void showSortDialog() {
        String[] fieldNames = {"星级", "跳转次数", "添加时间", "番号首字母"};
        SortField[] fields = {SortField.RATING, SortField.JUMP_COUNT, SortField.TIME, SortField.CODE};

        // 构建带方向指示的选项文字
        String[] items = new String[fieldNames.length];
        for (int i = 0; i < fields.length; i++) {
            String arrow = "";
            if (fields[i] == currentSortField) {
                arrow = sortAscending ? " ↑" : " ↓";
            }
            items[i] = fieldNames[i] + arrow;
        }

        final int[] checkedItem = {java.util.Arrays.asList(fields).indexOf(currentSortField)};

        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(items, checkedItem[0], (dialog, which) -> {
                    if (fields[which] == currentSortField) {
                        // 同字段 → 切换方向
                        sortAscending = !sortAscending;
                    } else {
                        currentSortField = fields[which];
                        sortAscending = false; // 切换字段默认降序
                    }
                    applySortAndFilter();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .create()
                .show();
    }

    // =========================
    // 定时备份设置对话框
    // =========================
    private void showBackupSettingsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_backup_settings, null);
        TextView tvLastBackup = view.findViewById(R.id.tvLastBackupTime);
        TextView tvBackupCount = view.findViewById(R.id.tvBackupCount);
        EditText etInterval = view.findViewById(R.id.etBackupInterval);
        Spinner spCount = view.findViewById(R.id.spBackupCount);
        TextView tvBackupFiles = view.findViewById(R.id.tvBackupFiles);
        TextView tvBackupPath = view.findViewById(R.id.tvBackupPath);
        androidx.appcompat.widget.AppCompatButton btnBackupNow = view.findViewById(R.id.btnBackupNow);

        // 自动备份目录（外部存储，可通过文件管理器访问）
        tvBackupPath.setText("自动备份目录：" + BackupManager.getBackupDir(this).getAbsolutePath());

        // 上次备份日期
        tvLastBackup.setText("上次备份：" + BackupManager.formatLastBackupDate(this));

        // 备份数量选择
        int currentCount = BackupManager.getBackupCount(this);
        String[] countOptions = {"1", "2", "3", "5", "10"};
        ArrayAdapter<String> countAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, countOptions);
        countAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCount.setAdapter(countAdapter);
        for (int i = 0; i < countOptions.length; i++) {
            if (Integer.parseInt(countOptions[i]) == currentCount) {
                spCount.setSelection(i);
                break;
            }
        }

        // 备份间隔
        int intervalDays = BackupManager.getBackupIntervalDays(this);
        etInterval.setText(String.valueOf(intervalDays));

        // 现有备份列表
        refreshBackupFileList(tvBackupFiles);

        // 立即备份按钮 → 调用 SAF 导出（与"导出 JSON"相同流程）
        btnBackupNow.setOnClickListener(bv -> {
            exportJsonToDownload();
        });

        new AlertDialog.Builder(this)
                .setTitle("定时备份设置")
                .setView(view)
                .setPositiveButton("保存", (d, i) -> {
                    try {
                        int count = Integer.parseInt(countOptions[spCount.getSelectedItemPosition()]);
                        BackupManager.setBackupCount(this, count);
                    } catch (Exception ignored) {}
                    try {
                        int days = Integer.parseInt(etInterval.getText().toString().trim());
                        BackupManager.setBackupIntervalDays(this, days);
                    } catch (Exception ignored) {}
                    Toast.makeText(this, "备份设置已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create()
                .show();
    }

    private void refreshBackupFileList(TextView tvBackupFiles) {
        List<File> files = BackupManager.getBackupFiles(this);
        tvBackupFiles.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        if (files.isEmpty()) {
            tvBackupFiles.setText("暂无备份文件");
        } else {
            StringBuilder sb = new StringBuilder();
            for (File f : files) {
                sb.append(BackupManager.parseTimestampFromName(f.getName())).append("\n");
            }
            tvBackupFiles.setText(sb.toString().trim());
        }
    }

    private void showLogDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_log, null);
        TextView tvLog = view.findViewById(R.id.tvLog);
        androidx.appcompat.widget.AppCompatButton btnCopy = view.findViewById(R.id.btnCopyLog);
        androidx.appcompat.widget.AppCompatButton btnExport = view.findViewById(R.id.btnExportLog);
        androidx.appcompat.widget.AppCompatButton btnClear = view.findViewById(R.id.btnClearLog);

        tvLog.setText(LogUtil.getAllText());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        btnCopy.setOnClickListener(v -> {
            String text = tvLog.getText().toString();
            if (text.isEmpty()) {
                Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("log", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });

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

    // =========================
    // 跳转源管理
    // =========================
    private void showJumpSourceDialog() {
        List<JumpSource> sources = JumpSource.loadAll(this);
        JumpSource current = JumpSource.getCurrent(this);

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_jump_source_list, null);
        LinearLayout llSourceList = view.findViewById(R.id.llJumpSourceList);
        androidx.appcompat.widget.AppCompatButton btnAddSource = view.findViewById(R.id.btnAddJumpSource);

        refreshJumpSourceListView(llSourceList, sources, current);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("关闭", null)
                .create();

        btnAddSource.setOnClickListener(v -> {
            showEditJumpSourceDialog(null, newSource -> {
                sources.add(newSource);
                JumpSource.saveAll(this, sources);
                refreshJumpSourceListView(llSourceList, sources, current);
            });
        });

        dialog.show();
    }

    private void refreshJumpSourceListView(LinearLayout container, List<JumpSource> sources, JumpSource current) {
        container.removeAllViews();

        for (int i = 0; i < sources.size(); i++) {
            final JumpSource source = sources.get(i);
            final int index = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);

            // 选中标记 + 名称 + URL
            LinearLayout infoArea = new LinearLayout(this);
            infoArea.setOrientation(LinearLayout.VERTICAL);
            infoArea.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            boolean isCurrent = current != null && current.getId().equals(source.getId());
            TextView tvName = new TextView(this);
            tvName.setText((isCurrent ? "✔ " : "") + source.getName());
            tvName.setTextSize(14);
            tvName.setTextColor(isCurrent ? 0xFF664FA3 : 0xFF333333);

            TextView tvUrl = new TextView(this);
            String url = source.getBaseUrl();
            if (url.length() > 45) url = url.substring(0, 45) + "...";
            tvUrl.setText(url);
            tvUrl.setTextSize(10);
            tvUrl.setTextColor(0xFF999999);

            infoArea.addView(tvName);
            infoArea.addView(tvUrl);

            infoArea.setOnClickListener(v -> {
                // 设为当前选中
                JumpSource.setCurrent(this, source);
                JumpSource newCurrent = JumpSource.getCurrent(this);
                refreshJumpSourceListView(container, sources, newCurrent);
            });

            // 编辑按钮
            androidx.appcompat.widget.AppCompatButton btnEdit = new androidx.appcompat.widget.AppCompatButton(this);
            btnEdit.setText("编辑");
            btnEdit.setTextSize(10);
            btnEdit.setMinWidth(0);
            btnEdit.setMinHeight(0);
            btnEdit.setPadding(8, 4, 8, 4);
            btnEdit.setOnClickListener(v -> {
                showEditJumpSourceDialog(source, edited -> {
                    sources.set(index, edited);
                    JumpSource.saveAll(this, sources);
                    refreshJumpSourceListView(container, sources, current);
                });
            });

            // 删除按钮
            androidx.appcompat.widget.AppCompatButton btnDelete = new androidx.appcompat.widget.AppCompatButton(this);
            btnDelete.setText("删除");
            btnDelete.setTextSize(10);
            btnDelete.setTextColor(0xFFE53935);
            btnDelete.setMinWidth(0);
            btnDelete.setMinHeight(0);
            btnDelete.setPadding(8, 4, 8, 4);
            btnDelete.setOnClickListener(v -> {
                if (sources.size() <= 1) {
                    Toast.makeText(this, "至少保留一个跳转源", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean wasCurrent = isCurrent;
                sources.remove(index);
                if (wasCurrent && !sources.isEmpty()) {
                    JumpSource.setCurrent(this, sources.get(0));
                    JumpSource newCurrent2 = JumpSource.getCurrent(this);
                    JumpSource.saveAll(this, sources);
                    refreshJumpSourceListView(container, sources, newCurrent2);
                } else {
                    JumpSource.saveAll(this, sources);
                    refreshJumpSourceListView(container, sources, current);
                }
            });

            row.addView(infoArea);
            row.addView(btnEdit);
            row.addView(btnDelete);
            container.addView(row);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(0xFFE0E0E0);
            container.addView(divider);
        }
    }

    private interface OnJumpSourceEditedListener {
        void onEdited(JumpSource source);
    }

    private void showEditJumpSourceDialog(JumpSource existing, OnJumpSourceEditedListener listener) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_jump_source_edit, null);
        EditText etName = view.findViewById(R.id.etJumpName);
        EditText etUrl = view.findViewById(R.id.etJumpUrl);

        final boolean isEdit = (existing != null);
        if (isEdit) {
            etName.setText(existing.getName());
            etUrl.setText(existing.getBaseUrl());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isEdit ? "编辑跳转源" : "添加跳转源")
                .setView(view)
                .setPositiveButton("保存", (d, i) -> {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();

                    if (name.isEmpty() || url.isEmpty()) {
                        Toast.makeText(this, "名称和跳转地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    JumpSource result;
                    if (isEdit) {
                        existing.setName(name);
                        existing.setBaseUrl(url);
                        result = existing;
                    } else {
                        result = new JumpSource(
                                UUID.randomUUID().toString(), name, url, false);
                    }
                    listener.onEdited(result);
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
    }

    // =========================
    // 刮削源管理
    // =========================
    private void showScrapeSourceDialog() {
        List<ScrapeSource> sources = ScrapeSource.loadAll(this);

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_scrape_source_list, null);
        LinearLayout llSourceList = view.findViewById(R.id.llSourceList);
        androidx.appcompat.widget.AppCompatButton btnAddSource = view.findViewById(R.id.btnAddSource);

        refreshSourceListView(llSourceList, sources);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("关闭", null)
                .create();

        btnAddSource.setOnClickListener(v -> {
            showEditSourceDialog(null, newSource -> {
                sources.add(newSource);
                ScrapeSource.saveAll(this, sources);
                refreshSourceListView(llSourceList, sources);
            });
        });

        dialog.show();
    }

    private void refreshSourceListView(LinearLayout container, List<ScrapeSource> sources) {
        container.removeAllViews();

        for (int i = 0; i < sources.size(); i++) {
            final ScrapeSource source = sources.get(i);
            final int index = i;

            // 每行：水平布局
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);

            // 默认标记 + 名称区域（可点击设默认）
            LinearLayout infoArea = new LinearLayout(this);
            infoArea.setOrientation(LinearLayout.VERTICAL);
            infoArea.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView tvName = new TextView(this);
            tvName.setText((source.isDefault() ? "★ " : "☆ ") + source.getName());
            tvName.setTextSize(14);
            tvName.setTextColor(source.isDefault() ? 0xFF664FA3 : 0xFF333333);

            TextView tvUrl = new TextView(this);
            String baseUrl = source.getBaseUrl();
            if (baseUrl.length() > 40) baseUrl = baseUrl.substring(0, 40) + "...";
            tvUrl.setText(baseUrl);
            tvUrl.setTextSize(10);
            tvUrl.setTextColor(0xFF999999);

            infoArea.addView(tvName);
            infoArea.addView(tvUrl);

            infoArea.setOnClickListener(v -> {
                // 设为默认
                for (ScrapeSource s : sources) s.setDefault(false);
                source.setDefault(true);
                ScrapeSource.saveAll(this, sources);
                refreshSourceListView(container, sources);
            });

            // 编辑按钮
            androidx.appcompat.widget.AppCompatButton btnEdit = new androidx.appcompat.widget.AppCompatButton(this);
            btnEdit.setText("编辑");
            btnEdit.setTextSize(10);
            btnEdit.setMinWidth(0);
            btnEdit.setMinHeight(0);
            btnEdit.setPadding(8, 4, 8, 4);
            btnEdit.setOnClickListener(v -> {
                showEditSourceDialog(source, editedSource -> {
                    sources.set(index, editedSource);
                    ScrapeSource.saveAll(this, sources);
                    refreshSourceListView(container, sources);
                });
            });

            // 删除按钮
            androidx.appcompat.widget.AppCompatButton btnDelete = new androidx.appcompat.widget.AppCompatButton(this);
            btnDelete.setText("删除");
            btnDelete.setTextSize(10);
            btnDelete.setTextColor(0xFFE53935);
            btnDelete.setMinWidth(0);
            btnDelete.setMinHeight(0);
            btnDelete.setPadding(8, 4, 8, 4);
            btnDelete.setOnClickListener(v -> {
                if (sources.size() <= 1) {
                    Toast.makeText(this, "至少保留一个刮削源", Toast.LENGTH_SHORT).show();
                    return;
                }
                sources.remove(index);
                // 如果删除的是默认源，第一个自动成为默认
                if (source.isDefault() && !sources.isEmpty()) {
                    sources.get(0).setDefault(true);
                }
                ScrapeSource.saveAll(this, sources);
                refreshSourceListView(container, sources);
            });

            row.addView(infoArea);
            row.addView(btnEdit);
            row.addView(btnDelete);
            container.addView(row);

            // 分割线
            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(0xFFE0E0E0);
            container.addView(divider);
        }
    }

    private interface OnSourceEditedListener {
        void onEdited(ScrapeSource source);
    }

    private void showEditSourceDialog(ScrapeSource existing, OnSourceEditedListener listener) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_scrape_source_edit, null);
        EditText etSourceName = view.findViewById(R.id.etSourceName);
        EditText etSourceUrl = view.findViewById(R.id.etSourceUrl);
        EditText etTitleXPath = view.findViewById(R.id.etTitleXPath);
        EditText etImageXPath = view.findViewById(R.id.etImageXPath);
        EditText etWaitSeconds = view.findViewById(R.id.etWaitSeconds);
        SwitchCompat swNeedsPrewarm = view.findViewById(R.id.swNeedsPrewarm);

        final boolean isEdit = (existing != null);

        if (isEdit) {
            etSourceName.setText(existing.getName());
            etSourceUrl.setText(existing.getBaseUrl());
            etTitleXPath.setText(existing.getTitleXPath());
            etImageXPath.setText(existing.getImageXPath());
            etWaitSeconds.setText(String.valueOf(existing.getWaitSeconds()));
            swNeedsPrewarm.setChecked(existing.isNeedsPrewarm());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isEdit ? "编辑刮削源" : "添加刮削源")
                .setView(view)
                .setPositiveButton("保存", (d, i) -> {
                    String name = etSourceName.getText().toString().trim();
                    String url = etSourceUrl.getText().toString().trim();
                    String titleXPath = etTitleXPath.getText().toString().trim();
                    String imageXPath = etImageXPath.getText().toString().trim();
                    String waitStr = etWaitSeconds.getText().toString().trim();
                    int waitSec = 3;
                    try { waitSec = Integer.parseInt(waitStr); } catch (NumberFormatException ignored) {}
                    if (waitSec < 1) waitSec = 3;
                    boolean needsPrewarm = swNeedsPrewarm.isChecked();

                    if (name.isEmpty() || url.isEmpty()) {
                        Toast.makeText(this, "名称和基础地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    ScrapeSource result;
                    if (isEdit) {
                        existing.setName(name);
                        existing.setBaseUrl(url);
                        existing.setTitleXPath(titleXPath);
                        existing.setImageXPath(imageXPath);
                        existing.setWaitSeconds(waitSec);
                        existing.setNeedsPrewarm(needsPrewarm);
                        result = existing;
                    } else {
                        result = new ScrapeSource(
                                UUID.randomUUID().toString(),
                                name, url, titleXPath, imageXPath, false);
                        result.setWaitSeconds(waitSec);
                        result.setNeedsPrewarm(needsPrewarm);
                    }
                    listener.onEdited(result);
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
    }

    // =========================
    // 使用说明弹窗
    // =========================
    private static final String GUIDE_PREFS = "guide_prefs";
    private static final String KEY_GUIDE_CONTENT = "guide_content";
    private static final String DEFAULT_GUIDE =
            "欢迎使用 AvList！\n\n"
                    + "• 点击右下角 + 添加卡片\n"
                    + "• 填写番号后可自动刮削获取标题和封面\n"
                    + "• 长按卡片进入多选模式\n"
                    + "• 右滑(没写好呢)或点击右上角菜单查看更多功能\n"
                    + "• 支持 JSON 导入/导出备份数据\n\n"
                    + "提示：刮削在后台服务中运行，息屏也不中断\n"
                    + "卡片所有字段都可为空，仅番号做唯一性限制\n"
                    + "卡片只显示番号、标题、图片";

    private void showGuideDialog() {
        String content = getSharedPreferences(GUIDE_PREFS, MODE_PRIVATE)
                .getString(KEY_GUIDE_CONTENT, DEFAULT_GUIDE);

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_guide, null);
        TextView tvContent = view.findViewById(R.id.tvGuideContent);
        EditText etContent = view.findViewById(R.id.etGuideContent);

        tvContent.setText(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("使用说明")
                .setView(view)
                .setPositiveButton("知道了", null)
                .setNeutralButton("编辑", (d, i) -> {
                    // 切换到编辑模式
                    etContent.setText(tvContent.getText().toString());
                    tvContent.setVisibility(View.GONE);
                    etContent.setVisibility(View.VISIBLE);
                    d.dismiss();
                    showGuideEditDialog(etContent.getText().toString());
                })
                .create();
        dialog.show();
    }

    private void showGuideEditDialog(String currentContent) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_guide, null);
        TextView tvContent = view.findViewById(R.id.tvGuideContent);
        EditText etContent = view.findViewById(R.id.etGuideContent);

        // 直接显示编辑模式
        tvContent.setVisibility(View.GONE);
        etContent.setVisibility(View.VISIBLE);
        etContent.setText(currentContent);

        new AlertDialog.Builder(this)
                .setTitle("编辑使用说明")
                .setView(view)
                .setPositiveButton("保存", (d, i) -> {
                    String newContent = etContent.getText().toString().trim();
                    if (newContent.isEmpty()) {
                        newContent = DEFAULT_GUIDE;
                    }
                    getSharedPreferences(GUIDE_PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_GUIDE_CONTENT, newContent)
                            .apply();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create()
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtil.i("应用关闭");
        if (verifyWebView != null) {
            verifyWebView.destroy();
        }
    }
}
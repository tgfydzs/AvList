package com.skyseec.avlist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.chip.ChipGroup;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** 上一次已反映到 UI 上的 app_cache JSON；相同说明数据没变，无需重建列表 */
    private String lastLoadedJson = null;
    /** 老数据图片尺寸回填只需跑一次 */
    private boolean imageSizeBackfillDone = false;

    // 搜索防抖：避免每敲一个键就整体重建列表
    private static final long SEARCH_DEBOUNCE_MS = 150;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    // 日志对话框实时刷新
    private static final long LOG_REFRESH_MS = 500;
    private final Handler logRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable logRefreshTask;

    // 排序状态
    private enum SortField { RATING, JUMP_COUNT, TIME, CODE }
    private SortField currentSortField = SortField.TIME;
    private boolean sortAscending = false;  // false=降序(默认), true=升序
    private String currentSearchKeyword = "";

    // 过滤条件（和排序一样只活在内存里，不持久化）
    private final CardFilter filter = new CardFilter();

    // 「筛选与排序」面板。它常驻在 activity_main 的视图树里（不是 Dialog），
    // 这样打开时不用 inflate、不用开窗口，动画能在点击那一帧就起步 —— 见 setupFilterSheet
    private View filterSheetOverlay;
    private View filterSheetRoot;
    private View filterSheetScrim;
    private TextView tvFilterMatchCount;
    /** 排序那排片子的基础文案，inflate 时抄一份存着，refreshSortChips 每次往上拼 ↑/↓ */
    private String[] sortLabels;
    private boolean filterSheetOpen = false;

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

        LogUtil.i("应用启动 " + buildTag());

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
        // 补扫老图片的宽高，让首屏之后的滚动就不再被图片撑开
        backfillImageSizes();

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
                if (filterSheetOpen) {
                    closeFilterSheet();
                } else if (isMultiMode) {
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

        binding.btnSort.setOnClickListener(v -> openFilterSheet());

        // 筛选面板的接线只做这一次（它是常驻视图，不像 Dialog 每次都要重来一遍）
        setupFilterSheet();

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
        // 软件设置：单独开一页，和运行日志一组
        binding.drawerSettings.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.END);
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });
        // 使用说明：和首次启动弹的是同一个只读弹窗，这里是手动入口
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
                ivClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                // 防抖：连续输入时只在停下来之后重建一次列表
                final String keyword = s.toString();
                if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
                pendingSearch = () -> filterData(keyword);
                searchHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
            }
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                cancelPendingSearch();
                filterData(v.getText().toString());
                return true;
            }
            return false;
        });

        ivClear.setOnClickListener(v -> {
            cancelPendingSearch();
            etSearch.setText("");
            filterData("");
        });
        binding.btnBatchSelectAll.setOnClickListener(v -> toggleSelectAll());
        binding.btnBatchScrape.setOnClickListener(v -> batchScrape());
        binding.btnBatchClear.setOnClickListener(v -> batchClear());
        binding.btnBatchDelete.setOnClickListener(v -> batchDelete());
        binding.btnBatchMoveOut.setOnClickListener(v -> batchMoveOut());
        binding.btnBatchExport.setOnClickListener(v -> batchExport());
        binding.fabAdd.setOnClickListener(v -> showAddDialog());

        // 启动时检查是否需要备份（按日期差判定）
        BackupManager.checkAndBackup(this);

        // 进 app 就把人机验证跑掉，免得刮削的第一张卡现场冷启动要等一分钟。
        // savedInstanceState != null 说明是旋转之类的重建，不用再来一遍。
        if (savedInstanceState == null) {
            // 顺序有讲究：先预热。不然补刮的任务先入队，服务一起来就直接开刮，
            // 预热反而被跳过（processNext 里会主动让路给预热，但那时已经开跑了）
            ScrapeService.warmUp(this);
            enqueueCardsMissingImage();
        }

        // 首次启动弹窗
        checkFirstLaunch();
    }

    /** 缺图补刮的重试冷却：同一张卡 24 小时内只重试一次 */
    private static final long MISSING_IMAGE_RETRY_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    /**
     * 进 app 扫一遍：没有本地图片的卡片补进刮削队列。
     *
     * 判据是"本地有没有图片文件"，不是"有没有 imageUrl" —— 有 URL 但当时没下载成功
     * （CDN 防盗链、网络抖动）的卡在列表里一样是空白，同样要补。
     *
     * 冷却的意义：搜不到结果的卡（番号无效之类）会一直没图，没有冷却的话
     * 每次进 app 都会被重新入队、每次最多烧 150 秒，几十张就能白跑几小时。
     */
    private void enqueueCardsMissingImage() {
        ScrapeSource source = ScrapeSource.getDefault(this);
        if (source == null) return;

        long now = System.currentTimeMillis();
        List<ScrapeTask> tasks = new ArrayList<>();
        int skipDisabled = 0, skipNoCode = 0, skipHasImage = 0, skipCooldown = 0;
        for (CardItem item : originList) {
            if (!item.isScrapeEnabled()) { skipDisabled++; continue; }   // 用户关了刮削
            if (CardItem.isBlankOrNullStr(item.getCode())) { skipNoCode++; continue; } // 没番号，刮不了
            if (new File(item.resolveLocalImagePath(this)).exists()) { skipHasImage++; continue; } // 已有图
            if (now - item.getLastScrapeAt() < MISSING_IMAGE_RETRY_COOLDOWN_MS) { skipCooldown++; continue; } // 刚试过
            tasks.add(ScrapeTask.from(item, source));
        }

        // 逐项报跳过原因。只报一个总数的话，"以为关了刮削却进了队列"这种疑问
        // 在日志里根本查不出来 —— 分不清是过滤没生效，还是那张卡的开关本来就是开的。
        LogUtil.i("缺图补刮: 共" + originList.size() + " 张 → 入队 " + tasks.size()
                + "，跳过(关刮削=" + skipDisabled + " 无番号=" + skipNoCode
                + " 已有图=" + skipHasImage + " 冷却中=" + skipCooldown + ")");

        if (tasks.isEmpty()) return;
        ScrapeService.enqueueAll(this, tasks);
        // 措辞上不说"已加入" —— 服务侧还会按 cardId 去重，已经在队列里的会被跳过，
        // 这里拿不到去重结果，说成"已加入"就对不上了
        Toast.makeText(this, "发现 " + tasks.size() + " 张缺图的卡片，已提交刮削队列",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 首次启动自动弹一次使用说明。之后想看走抽屉菜单 →「使用说明」。
     */
    private void checkFirstLaunch() {
        SharedPreferences sp = getSharedPreferences(GUIDE_PREFS, MODE_PRIVATE);
        if (!sp.getBoolean(KEY_GUIDE_SHOWN, false)) {
            sp.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply();
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
                        File pickedFile = copyImageToLocal(pendingImagePickUri, item.getId());
                        ImageSizeUtil.applyToCard(item, pickedFile);
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
        refreshSelectAllButton();
    }

    /** 当前显示出来的卡片是否已全部选中 */
    private boolean isAllVisibleSelected() {
        return !cardList.isEmpty()
                && selectedList.size() >= cardList.size()
                && selectedList.containsAll(cardList);
    }

    /**
     * 全选 / 取消全选。
     * 作用范围是 cardList（当前搜索过滤后的结果），不是 originList ——
     * 搜索状态下把看不见的卡片也选上，紧接着点删除会很危险。
     */
    private void toggleSelectAll() {
        boolean wasAllSelected = isAllVisibleSelected();
        selectedList.clear();
        if (!wasAllSelected) {
            selectedList.addAll(cardList);
        }
        adapter.notifyDataSetChanged();
        refreshSelectAllButton();
    }

    private void refreshSelectAllButton() {
        binding.btnBatchSelectAll.setText(isAllVisibleSelected() ? "取消全选" : "全选");
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
        saveCacheData();
        // exitMultiMode → setMultiSelectMode(false) 内部已做一次整体刷新，不再重复触发
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

    /**
     * 多选批量刮削：选中的卡全部打开刮削状态并入队。
     * 没番号的刮不了，跳过并计数 —— 不静默丢弃。
     */
    private void batchScrape() {
        if (selectedList.isEmpty()) {
            Toast.makeText(this, "请先选择卡片", Toast.LENGTH_SHORT).show();
            return;
        }

        final ScrapeSource source = ScrapeSource.getDefault(this);
        List<ScrapeTask> tasks = new ArrayList<>();
        int skipNoCode = 0;
        for (CardItem item : selectedList) {
            if (CardItem.isBlankOrNullStr(item.getCode())) { skipNoCode++; continue; }
            item.setScrapeEnabled(true);
            tasks.add(ScrapeTask.from(item, source));
        }

        if (!tasks.isEmpty()) ScrapeService.enqueueAll(this, tasks);
        saveCacheData();
        LogUtil.i("批量刮削: 选中" + selectedList.size() + " → 入队 " + tasks.size()
                + "，跳过(无番号=" + skipNoCode + ")");

        exitMultiMode();   // 里面会整体刷新
        Toast.makeText(this, "已提交 " + tasks.size() + " 个刮削任务"
                + (skipNoCode > 0 ? "，跳过 " + skipNoCode + " 张无番号" : ""),
                Toast.LENGTH_SHORT).show();
    }

    /** 多选批量清除：选中的卡全部关掉刮削状态，并清掉抓来的标题和图片 */
    private void batchClear() {
        if (selectedList.isEmpty()) {
            Toast.makeText(this, "请先选择卡片", Toast.LENGTH_SHORT).show();
            return;
        }

        for (CardItem item : selectedList) {
            clearCardScrapedData(item);
            item.setScrapeEnabled(false);
        }
        saveCacheData();
        LogUtil.i("批量清除: 已清除 " + selectedList.size() + " 张，刮削状态全部关闭");

        exitMultiMode();   // 里面会整体刷新
        Toast.makeText(this, "已清除 " + selectedList.size() + " 张，刮削已关闭",
                Toast.LENGTH_SHORT).show();
    }

    private void performMoveOutDelete() {
        // 删除本地图片
        for (CardItem item : selectedList) {
            deleteLocalImage(item);
            LogUtil.i("移出卡片: " + item.getCode());
        }
        originList.removeAll(selectedList);
        cardList.removeAll(selectedList);
        saveCacheData();
        // exitMultiMode → setMultiSelectMode(false) 内部已做一次整体刷新，不再重复触发
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
        refreshSelectAllButton();
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

            // scrapeEnabled = false：光有番号不代表就要刮，导入本身不该替用户做这个决定。
            // 想刮的话在多选里全选 → 点「刮削」，或者单张点卡片上的「刮削」。
            CardItem newItem = new CardItem(
                    UUID.randomUUID().toString(),
                    "", "", "",
                    System.currentTimeMillis(),
                    "", "",
                    normalizedCode,
                    false
            );
            originList.add(0, newItem);
            newItems.add(newItem);
            addCount++;
        }

        applySortAndFilter();
        saveCacheData();

        // 这里原先无条件整批入队（不存在的"开关"）。现在导入的卡一律不刮，
        // 也就不再入队 —— 要刮得由用户在多选里显式点「刮削」。
        LogUtil.i("批量导入: 新增 " + newItems.size() + " 张，刮削状态默认关闭，未入队");

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

    /** 取消还没落地的防抖搜索，避免紧接着又跑一次多余的重建 */
    private void cancelPendingSearch() {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
    }

    /**
     * 当前跑的到底是哪个包。
     *
     * 加这个是因为踩过一次：日志里出现了我上一轮刚删掉的旧行为，同时又缺着我刚加的输出，
     * 于是分不清"代码没生效"还是"装的是旧包"，白猜了一轮。lastUpdateTime 是安装时间，
     * 一看就知道手上这个 APK 是不是刚编译的。
     */
    private String buildTag() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + pi.versionName + "(" + pi.versionCode + ") 安装于 "
                    + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(pi.lastUpdateTime));
        } catch (Exception e) {
            return "(读取失败)";
        }
    }

    private void applySortAndFilter() {
        // 1. 先过滤。搜索关键词和「筛选与排序」里的三组条件是**且**的关系，
        //    所以放在同一个循环里判，省掉中间那一趟多余的列表
        List<CardItem> filtered = new ArrayList<>();
        boolean hasKeyword = !currentSearchKeyword.isEmpty();
        String[] tokens = hasKeyword ? currentSearchKeyword.toLowerCase().split("\\s+") : null;

        for (CardItem item : originList) {
            if (!filter.matches(this, item)) continue;
            if (hasKeyword && !matchesKeyword(item, tokens)) continue;
            filtered.add(item);
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
        // 过滤条件变了，"全选"按钮的文案可能就不对了（eg. 搜出来的正好都被选中了）
        if (isMultiMode) {
            refreshSelectAllButton();
        }
        updateSortButtonState();
    }

    /**
     * 多 token 取交集：每个 token 都要命中 title/tag/code/remark/content 之一（不分大小写，子串即可）。
     */
    private static boolean matchesKeyword(CardItem item, String[] tokens) {
        String title = item.getTitle() == null ? "" : item.getTitle().toLowerCase();
        String tag = item.getTag() == null ? "" : item.getTag().toLowerCase();
        String code = item.getCode() == null ? "" : item.getCode().toLowerCase();
        String remark = item.getRemark() == null ? "" : item.getRemark().toLowerCase();
        String content = item.getContent() == null ? "" : item.getContent().toLowerCase();

        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (title.contains(token)
                    || tag.contains(token)
                    || code.contains(token)
                    || remark.contains(token)
                    || content.contains(token)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * 有筛选在生效时，把工具栏那颗图标点亮 —— 否则列表少显示了一堆卡而界面上毫无提示，
     * 很容易被当成"数据丢了"。
     */
    private void updateSortButtonState() {
        if (binding == null) return;
        boolean active = filter.isActive();
        // 注意别写成 setColorFilter(active ? getColor(...) : null)：
        // 三元里一边是 int 一边是 null，整个表达式会被推成 Integer 再把 null 拆箱 → 必崩
        if (active) {
            binding.btnSort.setColorFilter(getColor(R.color.filter_icon_active));
        } else {
            binding.btnSort.clearColorFilter();
        }
        binding.btnSort.setContentDescription(
                active ? "筛选与排序（有筛选生效）" : "筛选与排序");
    }

    private void saveCacheData() {
        String json = gson.toJson(originList);
        // 自己写下去的就是当前内存状态，同步记上，免得下次读回来被当成外部变更
        lastLoadedJson = json;
        getSharedPreferences("app_cache", MODE_PRIVATE)
                .edit()
                .putString("data", json)
                .apply();
    }

    private void loadCacheData() {
        String json = getSharedPreferences("app_cache", MODE_PRIVATE)
                .getString("data", "[]");

        // 数据一个字都没变就什么都不做。
        // 刮削任务"开始处理"时也会广播，但那时卡片数据还没动，不该惊动列表。
        if (json.equals(lastLoadedJson)) return;
        lastLoadedJson = json;

        // 优先增量：只刷新真正变了的那几张卡，滚动位置和其余卡片的图片都不受影响
        if (refreshIncremental(json)) return;

        // 有增删或排序键变化 → 退回整体重建
        Type type = new TypeToken<List<CardItem>>() {}.getType();
        List<CardItem> cacheList = gson.fromJson(json, type);
        originList.clear();
        if (cacheList != null) originList.addAll(cacheList);
        applySortAndFilter();
    }

    /**
     * 增量刷新：把重新解析出来的卡片值就地合并进现有对象，避免整列表重绑。
     *
     * @return true  = 已处理完（要么没变化，要么已逐条刷新）
     *         false = 结构变了（有增删）或排序键变了，调用方需整体重建
     */
    private boolean refreshIncremental(String json) {
        if (originList.isEmpty()) return false;

        List<CardItem> fresh;
        try {
            Type type = new TypeToken<List<CardItem>>() {}.getType();
            fresh = gson.fromJson(json, type);
        } catch (Exception e) {
            LogUtil.e("增量刷新解析失败", e);
            return false;
        }
        if (fresh == null || fresh.size() != originList.size()) return false;

        Map<String, CardItem> freshById = new HashMap<>();
        for (CardItem c : fresh) freshById.put(c.getId(), c);

        List<CardItem> dirty = new ArrayList<>();
        for (CardItem old : originList) {
            CardItem now = freshById.get(old.getId());
            if (now == null) return false;                    // 有卡片被删掉
            if (old.sortKeysChangedFrom(now)) return false;   // 排序键变了，得整体重排
            if (old.applyChangesFrom(now)) dirty.add(old);
        }
        if (dirty.isEmpty()) return true;

        for (CardItem item : dirty) {
            int pos = cardList.indexOf(item);   // CardItem 未重写 equals，走的就是引用相等
            if (pos >= 0) adapter.notifyItemChanged(pos);
        }
        return true;
    }

    /**
     * 老数据的图片没记过宽高，后台补扫一次。
     * 之后就靠下载/选图时记录，不需要再跑。
     */
    private void backfillImageSizes() {
        if (imageSizeBackfillDone) return;
        imageSizeBackfillDone = true;

        // 先在主线程快照，子线程不碰 originList
        final Map<String, File> pending = new HashMap<>();
        for (CardItem item : originList) {
            if (item.getImgWidth() > 0 && item.getImgHeight() > 0) continue;
            File file = new File(item.resolveLocalImagePath(this));
            if (file.exists()) pending.put(item.getId(), file);
        }
        if (pending.isEmpty()) return;

        new Thread(() -> {
            final Map<String, int[]> sizes = new HashMap<>();
            for (Map.Entry<String, File> entry : pending.entrySet()) {
                int[] size = ImageSizeUtil.readSize(entry.getValue());
                if (size != null) sizes.put(entry.getKey(), size);
            }
            if (sizes.isEmpty()) return;

            runOnUiThread(() -> {
                boolean changed = false;
                for (CardItem item : originList) {
                    int[] size = sizes.get(item.getId());
                    if (size == null) continue;
                    if (item.getImgWidth() != size[0] || item.getImgHeight() != size[1]) {
                        item.setImageSize(size[0], size[1]);
                        changed = true;
                    }
                }
                if (!changed) return;
                saveCacheData();
                adapter.notifyItemRangeChanged(0, cardList.size());
                LogUtil.i("图片尺寸回填完成: " + sizes.size() + " 张");
            });
        }, "image-size-backfill").start();
    }

    @Override
    public void onItemLongClick(int position) {
        if (isMultiMode) {
            return;
        }
        openMultiSelectMode();

        CardItem selectItem = cardList.get(position);
        selectedList.add(selectItem);
        adapter.notifyItemChanged(position);
        refreshSelectAllButton();
    }

    /**
     * 点卡片上的「刮削」= 打开这张卡的刮削状态，并立刻刮一次。
     *
     * 改的就是 CardItem.scrapeEnabled —— 它一直都在（第一个 commit 就有，也一直序列化着），
     * 缺图补刮也在读它（enqueueCardsMissingImage）。缺的只是"能看见、能改"的入口，
     * 所以这里不新增字段：新增字段意味着老数据没有这个键，Gson 会用 Java 默认值填，
     * 那批用户明确关过刮削的卡会被重新打开，正是这个 bug 的原样重演。
     *
     * 关掉刮削走「清除」按钮，两个按钮是一对。
     */
    @Override
    public void onItemScrape(int position) {
        CardItem item = cardList.get(position);
        String code = item.getCode();
        if (code == null || code.trim().isEmpty()) {
            Toast.makeText(this, "无番号，无法刮削", Toast.LENGTH_SHORT).show();
            return;
        }

        // 没番号就刮不了，也就没有"打开刮削"的意义（上一行已经返回），
        // 所以到这里一定是能刮的，直接置为开
        item.setScrapeEnabled(true);

        final ScrapeSource scrapeSource = ScrapeSource.getDefault(MainActivity.this);
        ScrapeTask task = ScrapeTask.from(item, scrapeSource);
        ScrapeService.enqueue(MainActivity.this, task);
        LogUtil.i("手动刮削已入队(刮削状态已开): " + code);

        saveCacheData();
        adapter.notifyItemChanged(position);
        Toast.makeText(this, "刮削已加入队列", Toast.LENGTH_SHORT).show();
    }

    /**
     * 点卡片上的「清除」= 关掉这张卡的刮削状态 + 清掉抓来的标题和图片。
     *
     * "关刮削"和"清数据"合成一个动作，是因为这两件事的用户意图是同一个：
     * 这张卡我不想要它被刮了。分开做的话，清完图下次进 app 缺图补刮又把它刮回来。
     */
    @Override
    public void onItemClear(int position) {
        CardItem item = cardList.get(position);
        LogUtil.i("清除卡片数据(刮削状态已关): " + item.getCode());

        clearCardScrapedData(item);
        item.setScrapeEnabled(false);

        int displayIdx = cardList.indexOf(item);
        if (displayIdx >= 0) adapter.notifyItemChanged(displayIdx);
        saveCacheData();
    }

    /**
     * 清掉一张卡刮削得来的内容：本地图片文件 + 标题 + 图片 URL + 尺寸记录 + 编辑标记。
     *
     * userEdited 必须一起复位：它一旦为 true，ScrapeService.updateCardData 会直接
     * 跳过这张卡（"用户编辑过就别覆盖"）。不复位的话，清除之后点「刮削」是白点的
     * —— 任务跑完，标题和图都写不回来，卡片永远空着。
     */
    private void clearCardScrapedData(CardItem item) {
        deleteLocalImage(item);
        item.setTitle("");
        item.setImageUrl("");
        item.setLocalImagePath(null);
        item.clearImageSize();   // 图没了，比例记录作废，换图时重新测
        item.setUserEdited(false);
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
        // 次数是排序键。saveCacheData 之后 JSON 与内存一致，下次 loadCacheData 会直接跳过，
        // 所以按次数排序时得在这里主动重排，否则位次要等别的变更才会更新
        if (currentSortField == SortField.JUMP_COUNT) {
            applySortAndFilter();
        }

        // 执行跳转
        JumpSource jumpSource = JumpSource.getCurrent(this);
        String url = jumpSource.buildUrl(CardItem.normalizeCode(code));
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    // =========================
    // 从系统相册复制图片到内部存储
    // =========================
    /** @return 保存后的文件；失败返回 null */
    private File copyImageToLocal(Uri sourceUri, String cardId) {
        try {
            InputStream is = getContentResolver().openInputStream(sourceUri);
            if (is == null) {
                LogUtil.w("无法打开图片: " + sourceUri);
                return null;
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
            return outFile;
        } catch (Exception e) {
            LogUtil.e("复制图片失败", e);
            return null;
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
    // =========================
    // 筛选与排序（底部弹窗）
    // =========================

    /**
     * 排序 + 过滤的综合入口。
     *
     * 三组过滤条件任意组合（组内单选、组间取交集），改动**立刻生效**：
     * 面板只占下半屏，列表上半部分还看得见，点一下就能看见后面列表的变化，
     * 不用"关掉面板再看一眼"来回试。底部那行"显示 N / M 张"是同一回事的量化版。
     *
     * 从按下到画面动起来中间**不留空档**，靠的是三件事，缺一不可：
     *   1. 面板是 Activity 视图树里的常驻孩子（activity_main 里 include 进来的），
     *      不是 Dialog —— 省掉 inflate 和 WindowManager.addView 两笔开销；
     *   2. 它平时是 invisible（只挡绘制、不挡测量），所以高度**一直是量准的**，
     *      这里 getHeight() 立刻就能用，不用等一次布局；
     *   3. 于是 setTranslationY / animate 全在点击回调里同步跑完，
     *      和抽屉一样是"这一帧就开始动"。
     *
     * 用 Dialog 的时候这套做不到：光是铺满全屏就得 show() 之后再 setLayout 一次
     * （第二遍布局），入场动画还非挂在 preDraw 上等第一遍布局走完不可，
     * 那两笔加起来就是手感上那段延迟。
     */
    private void openFilterSheet() {
        if (filterSheetOpen) return;
        filterSheetOpen = true;

        // 收到一半又点开：先把收起动画掐掉，不然两段 animate 会互相抢 translationY
        filterSheetRoot.animate().cancel();
        filterSheetScrim.animate().cancel();

        refreshMatchCount();
        filterSheetOverlay.setVisibility(View.VISIBLE);

        // 起点用实测高度，不能图省事拿屏幕高 —— 多出来的行程全是"什么都还没看见"
        // 的空白，白等一段。理论上恒 > 0，兜个底免得首次布局还没跑完时整个面板直接弹出来。
        int travel = filterSheetRoot.getHeight();
        if (travel <= 0) travel = getResources().getDisplayMetrics().heightPixels;

        // 面板从屏幕下方滑上来，遮罩同时淡入：同一条曲线、同一个时长
        filterSheetRoot.setTranslationY(travel);
        filterSheetScrim.setAlpha(0f);
        filterSheetRoot.animate().translationY(0f)
                .setDuration(SHEET_MS)
                .setInterpolator(DRAWER_EASE)
                .start();
        filterSheetScrim.animate().alpha(SCRIM_ALPHA)
                .setDuration(SHEET_MS)
                .setInterpolator(DRAWER_EASE)
                .start();
    }

    /** 收起：面板滑走、遮罩淡出，跑完再把整个覆盖层藏起来（返回键也走这里） */
    private void closeFilterSheet() {
        if (!filterSheetOpen) return;
        filterSheetOpen = false;

        filterSheetRoot.animate().translationY(filterSheetRoot.getHeight())
                .setDuration(SHEET_MS)
                .setInterpolator(DRAWER_EASE)
                .withEndAction(() -> {
                    // 收干净之前又被点开的话，这里不能再把它藏回去
                    if (!filterSheetOpen) filterSheetOverlay.setVisibility(View.INVISIBLE);
                })
                .start();
        filterSheetScrim.animate().alpha(0f)
                .setDuration(SHEET_MS)
                .setInterpolator(DRAWER_EASE)
                .start();
    }

    /**
     * 面板的接线，只在 onCreate 里跑一次 —— 面板是常驻视图，不像 Dialog 每次都要重来。
     *
     * 「回显当前状态」也只需要做这一次：排序字段和过滤条件只会从这个面板里被改，
     * 改的时候视图本来就跟着变了，用不着每次打开再刷一遍。
     */
    private void setupFilterSheet() {
        filterSheetOverlay = findViewById(R.id.filterSheetOverlay);
        filterSheetRoot = findViewById(R.id.sheetRoot);
        filterSheetScrim = findViewById(R.id.filterScrim);
        tvFilterMatchCount = findViewById(R.id.tvMatchCount);

        applySheetBackground(filterSheetRoot);

        ChipGroup cgSort = findViewById(R.id.cgSort);
        ChipGroup cgRating = findViewById(R.id.cgRating);
        ChipGroup cgImage = findViewById(R.id.cgImage);
        ChipGroup cgScrape = findViewById(R.id.cgScrape);

        // 基础文案从 XML 抄一份存下来。必须在**第一次** refreshSortChips 之前抄，
        // 否则抄到的就是已经拼了 ↑/↓ 的版本，多刷几次箭头会越拼越长
        sortLabels = new String[cgSort.getChildCount()];
        for (int i = 0; i < cgSort.getChildCount(); i++) {
            sortLabels[i] = ((Chip) cgSort.getChildAt(i)).getText().toString();
        }

        // 回显当前状态
        checkChipByTag(cgSort, currentSortField.name());
        checkChipByTag(cgRating, filter.rating.name());
        checkChipByTag(cgImage, filter.image.name());
        checkChipByTag(cgScrape, filter.scrape.name());
        refreshSortChips(cgSort, sortLabels);

        // 排序：点已选中的那颗 = 就地翻转升降序。
        // 原来那个 AlertDialog 是单选即关闭，想换个方向还得重新点开一次（见 git 历史）。
        for (int i = 0; i < cgSort.getChildCount(); i++) {
            Chip chip = (Chip) cgSort.getChildAt(i);
            chip.setOnClickListener(v -> {
                SortField field = SortField.valueOf(v.getTag().toString());
                if (field == currentSortField) {
                    sortAscending = !sortAscending;
                } else {
                    currentSortField = field;
                    sortAscending = false;  // 换字段默认降序
                }
                refreshSortChips(cgSort, sortLabels);
                applySortAndFilter();
                refreshMatchCount();
            });
        }

        cgRating.setOnCheckedStateChangeListener((group, checkedIds) -> {
            filter.rating = chipTagOf(group, CardFilter.Rating.class, CardFilter.Rating.ANY);
            onFilterChanged();
        });
        cgImage.setOnCheckedStateChangeListener((group, checkedIds) -> {
            filter.image = chipTagOf(group, CardFilter.Image.class, CardFilter.Image.ANY);
            onFilterChanged();
        });
        cgScrape.setOnCheckedStateChangeListener((group, checkedIds) -> {
            filter.scrape = chipTagOf(group, CardFilter.Scrape.class, CardFilter.Scrape.ANY);
            onFilterChanged();
        });

        // 重置：排序和过滤一起回默认。两件事同属这个面板，分两个按钮反而要多想一下
        findViewById(R.id.tvFilterReset).setOnClickListener(v -> {
            currentSortField = SortField.TIME;
            sortAscending = false;
            filter.reset();
            checkChipByTag(cgSort, currentSortField.name());
            checkChipByTag(cgRating, filter.rating.name());
            checkChipByTag(cgImage, filter.image.name());
            checkChipByTag(cgScrape, filter.scrape.name());
            refreshSortChips(cgSort, sortLabels);
            applySortAndFilter();
            refreshMatchCount();
        });

        findViewById(R.id.btnFilterDone).setOnClickListener(v -> closeFilterSheet());
        filterSheetScrim.setOnClickListener(v -> closeFilterSheet());

        // 面板最高占屏幕 80%，再多就交给 ScrollView 滚 —— 小屏或系统大字体下
        // 内容会比一屏还高，不夹的话会顶到状态栏去。layout_height 写不出
        // "wrap_content 但不超过 X"，只能等量完再按结果改这次的高度。
        //
        // 卡在首次布局之后做一次就够：高度只跟内容有关，之后不会自己变。
        // 放在这儿而不是每次打开时做，是为了让"打开"那条路径上一个 requestLayout 都没有
        // —— 有的话就得多等一帧，又把延迟加回去了。
        filterSheetOverlay.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        filterSheetOverlay.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        clampSheetHeight();
                    }
                });
    }

    private void clampSheetHeight() {
        View scroll = findViewById(R.id.svFilterSort);
        int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
        if (scroll.getHeight() > max) {
            ViewGroup.LayoutParams lp = scroll.getLayoutParams();
            lp.height = max;
            scroll.setLayoutParams(lp);
        }
    }

    /**
     * 给面板铺底：上面两角圆角，下面两条边直角（贴着屏幕底边）。
     *
     * 用代码拼而不是写个 drawable：drawable 的 &lt;solid android:color="?attr/colorSurface"/&gt;
     * 在这里解析不出颜色，会得到一个**全透明**的底 —— 表现就是面板整块透出后面的列表，
     * 只有上面的字和过滤片是实的。主题色只能在代码里取。
     */
    private void applySheetBackground(View sheet) {
        int color = MaterialColors.getColor(sheet,
                com.google.android.material.R.attr.colorSurface);
        float r = 16 * getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
    }

    /**
     * 面板展开/收起一次要多久。
     *
     * 右菜单抽屉是 512ms —— 它打开时走 ViewDragHelper.forceSettleCapturedViewAt，
     * 速度为零，于是时长 = computeAxisDuration 的 (pageDelta + 1) * BASE_SETTLE_DURATION，
     * 整段行程 pageDelta = 1，所以是 (1 + 1) * 256 = 512ms。
     *
     * 这里没有照搬 512，取了 400：抽屉的行程是它自己的宽度（260dp），而面板要滑过
     * 整块面板的高度，比抽屉长。同样的时长摊在更长的行程上，观感反而更"沉" ——
     * 所以时长短一点，让面板的速度明显压过抽屉。
     *
     * 曲线仍用抽屉那条，两边只有时长不同。想再快就往下调这个数（350 是下一档）。
     */
    private static final long SHEET_MS = 400;

    /**
     * 抽屉用的插值器 —— 不是系统的 AccelerateDecelerateInterpolator，
     * ViewDragHelper 里是这条五次曲线（t^5），手感不一样，照抄过来。
     */
    private static final Interpolator DRAWER_EASE = t -> {
        t -= 1.0f;
        return t * t * t * t * t + 1.0f;
    };

    private static final float SCRIM_ALPHA = 0.45f;

    /** 过滤条件变了：重排列表 + 刷新底部那行计数 */
    private void onFilterChanged() {
        applySortAndFilter();
        refreshMatchCount();
    }

    private void refreshMatchCount() {
        tvFilterMatchCount.setText("显示 " + cardList.size() + " / " + originList.size() + " 张");
    }

    /** 只给当前排序列那颗加 ↑/↓，其余用基础文案 */
    private void refreshSortChips(ChipGroup group, String[] labels) {
        for (int i = 0; i < group.getChildCount(); i++) {
            Chip chip = (Chip) group.getChildAt(i);
            boolean active = chip.getTag().toString().equals(currentSortField.name());
            String label = i < labels.length ? labels[i] : "";
            chip.setText(active ? label + (sortAscending ? " ↑" : " ↓") : label);
        }
    }

    private static void checkChipByTag(ChipGroup group, String tag) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View chip = group.getChildAt(i);
            if (tag.equals(chip.getTag())) {
                ((Chip) chip).setChecked(true);
                return;
            }
        }
    }

    /** 取当前选中片子上的 tag（就是枚举名），对不上就退回默认值 */
    private static <E extends Enum<E>> E chipTagOf(ChipGroup group, Class<E> type, E fallback) {
        int id = group.getCheckedChipId();
        if (id == View.NO_ID) return fallback;
        View chip = group.findViewById(id);
        if (chip == null || chip.getTag() == null) return fallback;
        try {
            return Enum.valueOf(type, chip.getTag().toString());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
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
        ScrollView svLog = view.findViewById(R.id.svLog);
        androidx.appcompat.widget.AppCompatButton btnCopy = view.findViewById(R.id.btnCopyLog);
        androidx.appcompat.widget.AppCompatButton btnExport = view.findViewById(R.id.btnExportLog);
        androidx.appcompat.widget.AppCompatButton btnClear = view.findViewById(R.id.btnClearLog);

        tvLog.setText(LogUtil.getAllText());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        // =========================
        // 实时刷新：刮削在后台往 LogUtil 写，这里定时把新增的行显示出来
        // =========================
        final int[] shownVersion = {LogUtil.getVersion()};
        final boolean[] scrolledToBottom = {false};
        startLogRefresh(() -> {
            int now = LogUtil.getVersion();
            if (now == shownVersion[0]) return;   // 没新内容，不动 UI
            // 用户正在选文字准备复制：setText 会清掉选区，这一轮先跳过，
            // 等他取消选中后下一轮自然跟上
            if (tvLog.getSelectionEnd() > tvLog.getSelectionStart()) return;
            shownVersion[0] = now;

            // 首次一定要滚到底。初始位置就在顶部，如果照搬"贴底才跟随"，
            // 会被当成用户在翻历史，日志就永远停在最旧那一屏
            boolean follow = !scrolledToBottom[0] || isLogAtBottom(svLog);
            tvLog.setText(LogUtil.getAllText());
            if (follow) {
                svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
                scrolledToBottom[0] = true;
            }
        });

        dialog.setOnDismissListener(d -> stopLogRefresh());

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
            LogUtil.i("日志已清空");
            // 手动刷完同步版本号，免得轮询马上又重绘一次
            shownVersion[0] = LogUtil.getVersion();
            tvLog.setText(LogUtil.getAllText());
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
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

        // 打开就直接停在最新一条，不用等第一条新日志进来
        svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 起一个定时轮询，每 {@link #LOG_REFRESH_MS} 跑一次 onTick。
     * 重复调用会先停掉上一个，避免开两次对话框就有两条轮询在跑。
     */
    private void startLogRefresh(Runnable onTick) {
        stopLogRefresh();
        logRefreshTask = new Runnable() {
            @Override
            public void run() {
                onTick.run();
                logRefreshHandler.postDelayed(this, LOG_REFRESH_MS);
            }
        };
        logRefreshHandler.postDelayed(logRefreshTask, LOG_REFRESH_MS);
    }

    private void stopLogRefresh() {
        if (logRefreshTask != null) {
            logRefreshHandler.removeCallbacks(logRefreshTask);
            logRefreshTask = null;
        }
    }

    /** 已经贴底才自动跟着滚；用户往上翻历史时不打扰 */
    private static boolean isLogAtBottom(ScrollView sv) {
        View child = sv.getChildAt(0);
        if (child == null) return true;
        return sv.getScrollY() + sv.getHeight() >= child.getMeasuredHeight() - 8;
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
        EditText etSourceUserAgent = view.findViewById(R.id.etSourceUserAgent);
        SwitchCompat swNeedsPrewarm = view.findViewById(R.id.swNeedsPrewarm);

        final boolean isEdit = (existing != null);

        if (isEdit) {
            etSourceName.setText(existing.getName());
            etSourceUrl.setText(existing.getBaseUrl());
            etTitleXPath.setText(existing.getTitleXPath());
            etImageXPath.setText(existing.getImageXPath());
            etWaitSeconds.setText(String.valueOf(existing.getWaitSeconds()));
            etSourceUserAgent.setText(existing.getUserAgent());
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
                    String ua = etSourceUserAgent.getText().toString().trim();
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
                        existing.setUserAgent(ua);
                        existing.setNeedsPrewarm(needsPrewarm);
                        result = existing;
                    } else {
                        result = new ScrapeSource(
                                UUID.randomUUID().toString(),
                                name, url, titleXPath, imageXPath, false);
                        result.setWaitSeconds(waitSec);
                        result.setUserAgent(ua);
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
    /** 只存"首次启动弹过没有"这一个标记，说明内容本身不存盘。 */
    private static final String GUIDE_PREFS = "guide_prefs";
    private static final String KEY_GUIDE_SHOWN = "guide_shown";

    /**
     * 使用说明的内容 —— **写死的常量，弹窗只读**。
     *
     * 以前这里带一个「编辑」按钮，改完存进 guide_prefs / guide_content。现在不要了：
     * 说明是跟着功能走的，能被改反而会和实际行为对不上（改了代码、弹窗里还是旧话）。
     * 要改说明就直接改这个常量。老版本存下的 guide_content 不再被读取，留在那不管。
     */
    private static final String GUIDE_TEXT =
            "欢迎使用 AvList！\n\n"
                    + "• 点击右下角 + 添加卡片\n"
                    + "• 填写番号后可自动刮削获取标题和封面\n"
                    + "• 长按卡片进入多选模式\n"
                    + "• 卡片右下「刮削」开这张卡的刮削并加进队列，「清除」关掉它并清图\n"
                    + "• 右滑(没写好呢)或点击右上角菜单查看更多功能\n"
                    + "• 菜单 →「软件设置」调刮削参数；长按设置里的某一行，可以看这一项是干什么的\n"
                    + "• 支持 JSON 导入/导出备份数据\n\n"
                    + "提示：刮削在后台服务中运行，息屏也不中断\n"
                    + "卡片所有字段都可为空，仅番号做唯一性限制\n"
                    + "卡片只显示番号、标题、图片";

    /**
     * 使用说明弹窗 —— 只读。
     *
     * 两个入口共用：首次启动自动弹一次，以及抽屉菜单 →「使用说明」。
     */
    private void showGuideDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_guide, null);
        ((TextView) view.findViewById(R.id.tvGuideContent)).setText(GUIDE_TEXT);

        new AlertDialog.Builder(this)
                .setTitle("使用说明")
                .setView(view)
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLogRefresh();
        LogUtil.i("应用关闭");
        if (verifyWebView != null) {
            verifyWebView.destroy();
        }
    }
}
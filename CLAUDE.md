# CLAUDE.md

本文件为在此代码库中工作的 Claude Code 提供指导。

## 构建与运行

```bash
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # 安装到已连接的设备
./gradlew clean
```

Gradle wrapper 9.4.1，AGP 9.2.1，JDK 17+（本机装有 JDK 21）。单模块 `:app`，`namespace com.skyseec.avlist`，`compileSdk`/`targetSdk` 34，`minSdk` 26，`versionName` 1.4。Maven 仓库走阿里云镜像（见 `settings.gradle.kts`）；`local.properties` 指定了 `sdk.dir=E:\AndroidSDK`。

**本项目没有测试。** `app/src/test/java` 和 `app/src/androidTest/java` 目录存在但是空的；`app/build.gradle.kts` 里声明了 `junit`/`espresso`，但没有任何地方用到它们。`gradle/libs.versions.toml` 存在但**部分失效** —— 只有 `android-application` 插件别名在用，`app/build.gradle.kts` 里的依赖版本是硬编码字符串，且已经和 catalog 脱节（比如构建文件里 Material 1.11.0、catalog 里 1.10.0）。加依赖时照 `app/build.gradle.kts` 写，不要照 catalog。

## 架构

单模块 Android 应用，**全部用 Java 编写**（Gradle 脚本是 Kotlin DSL）。开了 `viewBinding`，但代码绝大多数地方用 `findViewById`，只有 `MainActivity` 用了 `binding`。没有 Application 子类，也没有 DI —— 一切都是静态工具方法加 `SharedPreferences`。

这是一个个人向的番号卡片库。卡片保存番号/标题/标签/备注/内容/评分；真正复杂的部分是**后台网页刮削服务**，它负责把标题和封面图填进来。

### 持久化分布

没有数据库。一切都是 Gson JSON 存在 `SharedPreferences` 里。整个代码库的写入都用 `.apply()` —— 没有任何地方用 `commit()`。

| prefs 文件 | key | 内容 | 归属 |
|---|---|---|---|
| `app_cache` | `data` | `List<CardItem>` JSON —— **整个数据存储本体** | `MainActivity`、`DetailActivity`、`ScrapeService` |
| `scrape_queue_prefs` | `queue` | `List<ScrapeTask>` JSON | `ScrapeService` |
| `scrape_source_prefs` | `sources` | `List<ScrapeSource>` JSON | `ScrapeSource` |
| `jump_source_prefs` | `current_jump_source`、`all_sources` | `JumpSource` JSON | `JumpSource` |
| `scrape_prefs` | `queue_card_timeout_sec`、`queue_card_wait_step`、`disable_on_fail` | 全局刮削设置 | `ScrapeSettings` / `SettingsActivity` |
| `backup_prefs` | `backup_count`、`backup_interval_days`、`last_backup_date`（`yyyyMMdd`） | 轮换策略 | `BackupManager` |
| `guide_prefs` | `guide_shown` | 首次启动标记 | `MainActivity` |

`app_cache` 是一个整表读-改-写的 blob，**没有加锁也没有版本号 —— 后写的赢**。有三个类会写它（`MainActivity`、`DetailActivity`、`ScrapeService`）；`DetailActivity.saveItemToCache()` 特意重新读取 blob、只把详情页能改的字段拷回去，以免覆盖并发写入的刮削结果。改这块时请沿用这个模式。

图片存放在 `filesDir/images/<cardId>.jpg`，后缀名是硬编码的，与源图格式无关。`CardItem.localImagePath` 是 `transient` 的，从不序列化 —— `CardItem.resolveLocalImagePath(Context)` 由 id 推导路径，所以**「这张卡有没有图」等价于「那个文件在不在」**。缺图补刮用的就是这个判据。

### `CardItem` —— 数据模型

- **标准化**：`normalizeCode()` 去掉所有非字母数字字符并转大写。另有静态重载 `CardItem.normalizeCode(String)`。永远不要直接比较原始番号字符串 —— 用 `isSameCode(String)`。
- **`userEdited`**：为 true 时 `ScrapeService.updateCardData()` 会完全跳过这张卡（不覆盖 title 和 imageUrl；图片仍会下载，卡也仍会被记为已刮削）。详情页的编辑对话框和点星星都会把它置 true。
- **`scrapeEnabled`**：单卡刮削开关，从第一个版本起就在序列化。卡片上的「刮削」按钮把它置 true 并入队；「清除」把它置 false 并清掉标题和图片。**它唯一的消费方是缺图补刮** —— 手动点「刮削」不读它，而是先写它。
- **哨兵值过滤**：`isBlankOrNullStr()` 把 `"null"`、`"undefined"`、`"none"`（不分大小写）视为空。所有 getter 都会过一遍这个判断，所以 UI 永远不会渲染出这几个字面量。
- **`applyChangesFrom(other)` / `sortKeysChangedFrom(other)`**：增量刷新用的就地合并。`applyChangesFrom` 故意排除 `rating`、`jumpCount`、`code`（这三个是排序键），并且故意不把 `lastScrapeAt` 算作变化 —— 否则每刮完一张卡都会导致整个网格重绑。

### 列表流水线（`MainActivity`）

`originList`（数据真源，也是被持久化的那个）→ `applySortAndFilter()` → `cardList`（适配器绑定的那个）。多选集合里存的是指向 `cardList` 元素的引用。

- **过滤**：多 token 取交集（AND）。输入转小写后按 `\s+` 切分，每个 token 必须子串命中 `title`/`tag`/`code`/`remark`/`content` 之一。防抖 150 ms（`SEARCH_DEBOUNCE_MS`）。
- **排序**：`enum SortField { RATING, JUMP_COUNT, TIME, CODE }`，默认 `TIME` 降序。点同一个字段切换方向，点不同字段重置为降序。主键不是 `TIME` 时，永远追加一个 `createTime` 降序作为次级排序。**排序状态不持久化**，转屏即重置。
- **增量刷新**：`saveCacheData()` 会记下它刚写入的 JSON，`loadCacheData()` 在字符串没变时直接短路返回。`refreshIncremental(json)` 按 `id` 就地合并，若列表长度或 id 对不上、或 `sortKeysChangedFrom` 报告排序键变了，则回退到全量重建。它用 `cardList.indexOf(item)` 定位行，这只在 **`CardItem` 不重写 `equals`** 的前提下成立 —— 一旦加上 `equals`/`hashCode`，刷新会静默失效。
- 短路的后果：如果调用方在内存里改了某张卡的**排序键**，不会有任何东西自动重排。所以 `onItemJump()` 在当前排序是 `JUMP_COUNT` 时会显式调一次 `applySortAndFilter()`。

### 页面

- **MainActivity** —— 2 列 `StaggeredGridLayoutManager` 网格、搜索栏、FAB、排序按钮、右侧 `DrawerLayout`。长按进入多选；批量操作栏有 全选 / 刮削 / 清除 / 删除 / 移出 / 导出，全部作用于 `cardList`（当前筛选后的视图）而非 `originList`。
  - **「筛选与排序」面板**（`view_filter_sheet`）—— 工具栏那颗排序按钮现在的入口，排序 + 三组过滤（星级 / 图片 / 刮削开关）合在一起，改动**立刻生效**（面板只占下半屏，后面的列表当场就变）。过滤状态在 `CardFilter` 里，和排序一样只活内存、不持久化。
  - 这个面板**不是 Dialog，是 `activity_main` 里 include 进来的常驻视图**，为的是消掉"点下去到画面动起来"之间那段空档（Dialog 每次都要 inflate + 开窗口 + 布局两遍 + 挂 preDraw 等第一帧，抽屉没有这些所以显得快）。`MainActivity.setupFilterSheet` 一次接线，`openFilterSheet` / `closeFilterSheet` 只做动画。
- **DetailActivity** —— 通过 `"card_data"` Serializable extra 接收一个 `CardItem`。星级评分（会置 `userEdited`）、编辑对话框、刮削/清除，语义与卡片行上的按钮一致。
- **SettingsActivity** —— 分区式设置页，目前只有「刮削」一个分区，后端是 `ScrapeSettings`。详细说明文字写在 Java 调用处，通过**长按整行**弹出（长按 EditText/Switch 本身不会触发 —— 它们自己把触摸吃掉了）。
- **AddCardTileService** —— 下拉快捷设置瓷砖。置静态标记 `MainActivity.pendingShowAddDialog` 并启动 Activity；`tryShowAddFromTile()` 在 `onResume`/`onNewIntent` 里消费它。

### 刮削流水线

几乎所有的复杂度都在这里。分层是严格的：

```
Activity  ──enqueue/warmUp/cancelAll──▶  ScrapeService（前台服务，持有队列）
                                              │
                                              ├─ 独占一个 WebScrapeEngine（它持有 WebView）
                                              ├─ 每次尝试构造一个 JavResolver
                                              └─ PageScripts / ChallengeDetector（静态工具）

JavResolver     = 单次尝试的编排：预热 → 搜索 → 轮询 → 提取
WebScrapeEngine = WebView 及其 WebViewClient 的唯一归属方
PageScripts     = 所有注入的 JS 字符串 + 全项目唯一的 XPath 提取实现
```

**Activity 从不构造 `JavResolver`，也不为刮削碰任何 WebView。** 它们调用 `ScrapeService` 的静态方法 `enqueue(Context, ScrapeTask)` / `enqueueAll` / `cancelAll` / `warmUp`，并在 `onResume` 注册 `BroadcastReceiver` 监听 `ScrapeService.BROADCAST_PROGRESS`（`onPause` 注销）。收到广播后只做一件事：`loadCacheData()` —— 载荷 `EXTRA_QUEUE_JSON` 会被写入但从不被读取。

**队列模型 —— 是轮转，不是在原地重试。** `ScrapeService` 用同一个 `List<ScrapeTask>` 同时充当队列和轮转环。`processNext()` 取**第一个** `PENDING` 任务。可重试的空结果执行 `remove(task); add(task)` —— 这张卡被丢到队尾，于是 N 张卡先按入队顺序各走一遍第 1 次尝试，再走第 2 次，以此类推。`ScrapeTask.attempts` 统计的是**执行次数**（两种重试原因共用这一个计数：空结果和渲染进程崩溃），上限 `MAX_TASK_ATTEMPTS = 3`。单次尝试的预算来源：

- 单次尝试墙钟上限 = `ScrapeSettings.getQueueCardTimeoutSec()`（默认 **20 s**，夹在 3–300）。它在 `JavResolver` 构造函数里读一次，所以改完设置下一个任务就生效，不用重启服务。超时按*空结果*上报 → 进入轮转，不是一种独立状态。预热页的加载也在这同一份预算内。
- **没有单卡墙钟总预算**；实际上限是涌现出来的：最多 3 × 20 s，分散在若干轮轮转里。
- `queue_card_wait_step`（默认 1.0 = 没有阶梯）按轮次放大轮询窗口：`waitSec = round(source.waitSeconds * step^attempt)`。

**状态**：`PENDING / RUNNING / COMPLETED / FAILED`（`isFinished()` = COMPLETED || FAILED）。服务启动时 `loadQueue()` 会把持久化的 `RUNNING` 改回 `PENDING` 并保留 `attempts` —— 这就是崩溃恢复路径。

**卡片回写规则**（`ScrapeService` 内，全部跑在主 handler 上）：
- `updateCardData` 跳过 `userEdited` 的卡。
- `isCardScrapeEnabled` 是在**出结果时**求值，不是入队时 —— 用户可能在卡片排队期间把它清掉。
- 卡片已被清掉时，图片必须**完全不下载**。`CardAdapter` 无条件优先用本地文件，所以只跳过 `updateCardData` 的话图片照样会显示出来。
- `markCardScraped` 在每次尝试结束后（无论成功还是失败）都写 `lastScrapeAt`，这样补刮的冷却期对失败也生效。
- `disableScrapeOnFailure`（受 `disable_on_fail` 设置控制，默认**开**）在累计 3 次失败后把 `scrapeEnabled` 置 false。**绝不能**在用户主动取消时调用它。

**缺图补刮** —— 在 `MainActivity.enqueueCardsMissingImage()`，不在服务里。每次 Activity 冷启动跑一次，且刻意排在 `ScrapeService.warmUp(this)` **之后**（这个顺序是有承重作用的）。筛选条件：`scrapeEnabled` **且**番号非空 **且**本地无图文件 **且** `lastScrapeAt` 距今超过 `MISSING_IMAGE_RETRY_COOLDOWN_MS`（24 小时）。

**Cloudflare / 人机验证的处理** —— 这块经过了好几轮真机调试，注释里记着哪些「修复」反而把事情弄坏了：
- WebView 默认 UA 是**设备真实 UA**，只抹掉 `; wv)` 和 `Version/4.0 `（`X5WebView.buildDefaultUa()`），不是伪装的桌面 UA。伪装 UA 会和 `navigator.userAgentData.platform`、WebGL renderer 打架 —— 后两者是引擎如实上报的，JS 改不了。
- `PageScripts.STEALTH_JS` 只删自动化痕迹。不要重新加回任何指纹伪装补丁。
- **预热页刻意不注入 stealth** —— `WebScrapeEngine.loadPrewarm` 故意跳过 `injectStealthScript()`（窗口尺寸补丁*会*打，因为挑战正是跑在那个页面上）。往预热页注入 stealth 会让挑战无限期卡住。
- 预热**不等验证跑完**：停留 2 s（检测到挑战则 5 s）后直奔搜索页。真正等待的地方是搜索页的内容轮询，它等的是「要的东西出现了」。
- `ChallengeDetector.hasClearance()` 查的是 `cf_clearance` cookie，其中 `withRootPath()` 那个修补很关键 —— `CookieManager` 不会把 `Path=/` 的 cookie 匹配到没有路径的 URL 上。但预热轮询必须先看**页面**再看 cookie：`cf_clearance` 是绑定 UA 的，换了 UA 的旧 cookie 还在 jar 里却已经失效。
- 内容轮询由 Java 侧通过 `evaluateJavascript` 驱动（IPC 求值，不受后台定时器节流影响），不是页面里的 `setInterval`。轮询窗口会被夹到本次尝试的剩余预算内，以免超时先触发、`handleExtract` 根本轮不到跑。
- `X5WebView` 设了 `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)`，让渲染进程在后台不被回收；`WebScrapeEngine` 通过 `onRenderProcessGone` 重建 WebView 并把任务重新排到队尾。

**刮削源**（`ScrapeSource`，`scrape_source_prefs`）：用户可编辑的列表，每项含 `baseUrl`（`{keyword}` 占位符）、`titleXPath`、`imageXPath`、`waitSeconds`、`needsPrewarm`，以及可选的每源 `userAgent`（留空 = 设备默认；当某站点对移动端返回的 HTML 导致 XPath 失配时用它）。`loadAll()` 按*名称*合并新增的内置源，并为老用户自动升级预热配置。内置源：**MissAV**（默认，开预热，等 8 s）和 **JavBus**。`resolveImageUrl()` 补全相对路径，并过滤掉 `data:`/`blob:`/`javascript:`。`JumpSource` 是「跳转」按钮的平行概念（URL 模板，默认 MissAV）。

### 图片

优先本地文件，其次网络，两者都没有时把 `ImageView` 设为 `GONE`。网络加载走 Glide，用 `GlideUrl` 带上桌面 UA 和 `Referer: <源域名>`（CDN 通常会校验来源站）。`RatioImageView` 把行高固定在 `CardItem.getImageRatio()`（兜底 0.75），这样瀑布流不会在图片加载完成的瞬间重排 —— 这就是为什么 `imgWidth`/`imgHeight` 要被记录下来：下载完成时、本地选图时、以及 `MainActivity.backfillImageSizes()` 为历史卡片补录时，都用 `ImageSizeUtil.readSize()`（只解码头，不解码像素）写进去。

### 备份与导入导出

- **`BackupManager`** —— `checkAndBackup()` 在 `MainActivity.onCreate` 末尾跑一次；按天粒度对比 `last_backup_date`/`backup_interval_days`，写入 `getExternalFilesDir()/AvListBackups/card_backup_<ts>.json`，与最新备份内容相同则跳过，并按 `backup_count` 裁剪。对话框里的「立即备份」走的是 SAF 导出，不是 `autoBackup`。
- **导出 JSON** 写的是 `BackupManager.getDataJson()` —— 直接取存储里的原始字符串，不是重新序列化。
- **导入 JSON** 会把每个番号标准化、跳过空值、按标准化番号对 `originList` 去重，且**保留文件里的 id**（删掉某张卡后再导入同一份文件，会把那张卡连旧的图片路径一起复活）。
- **导入番号列表**一律 `scrapeEnabled = false` 且不入队 —— 导入番号不应替用户决定要不要刮削。
- 清除/删除卡片时会显式删掉对应的本地图片（`deleteLocalImage`）。

## 动手「修」之前值得知道的硬性约束

- **筛选面板有三条"删了就静默失效"的属性**，改 `view_filter_sheet.xml` / `activity_main.xml` 时别顺手清掉：① 根节点必须是 `visibility="invisible"` 而**不是** `gone` —— invisible 只挡绘制不挡测量，面板因此随时有一个量准的高度，`openFilterSheet` 才能拿它当滑入起点；换成 gone 高度恒为 0，面板会直接"弹"出来而不是滑上来。② 它必须是 `contentLayout` 的**最后一个孩子**且带 16dp `elevation` —— CoordinatorLayout 按 Z 序绘制，少一个条件面板就会被 FAB（6dp）或顶栏（4dp）盖住一角。③ `sheetRoot` 上的 `clickable="true"` 不能去掉：面板上的空白处如果不吃触摸，事件会透给同级的遮罩，手指点在面板上反而把面板关掉（以前是 Dialog，这个问题由窗口层挡着，看不出来）。
- **面板的过滤片状态只在 `setupFilterSheet` 里回显一次**，不在每次打开时刷。因为面板常驻，而排序字段和过滤条件只可能从这个面板里被改，视图本来就跟着变了。要加"从别处改排序/过滤"的入口，得同时补上回显，否则面板会显示上一次的状态。
- **`ScrapeService` 的重试必须在调用 `processNext()` *之前* 把任务放回队列。** 顺序反过来会先安排空闲停止（此时没有 PENDING），再把任务翻回 PENDING —— 却没有派发者了：队列永久死锁，外加一个永不退出的前台服务。
- **所有 `taskList` 的结构性改动都必须在主线程上做。**
- **`JavResolver.cancel()` 不会触发回调。** 每个调用方自己负责收尾（`processing = false`、`currentResolver = null`），否则队列会闩死。`handleCancelAll` 还刻意连 *RUNNING* 的那个任务一起取消 —— 只取消 PENDING 的话，在途的那次尝试会经由轮转再冒出来。
- **`clearCardScrapedData` 必须重置 `userEdited = false`。** 留着 true 会让之后每一次「刮削」都变成永远静默无效的操作。
- **不要为单卡刮削开关新增字段。** `scrapeEnabled` 已经存在；新字段经 Gson 反序列化时对老数据会取 Java 默认值，从而静默地把用户已经关掉的卡重新打开。
- **`toggleSelectAll` 刻意只作用于 `cardList` 而非 `originList`** —— 要避免的失败模式是「选中了看不见的卡，然后点了删除」。
- **使用说明正文是 `MainActivity` 里硬编码的 `GUIDE_TEXT` 常量**；该对话框按设计只读（可编辑的说明会与实际行为脱节）。要改就改常量。
- **抽屉边缘宽度是用反射伸进框架私有字段设的**（`DrawerLayout.mRightDragger` → `ViewDragHelper.mEdgeSize`、`DrawerLayout.mMinDrawerMargin`），另有 `DrawerEdgeHelper` 触摸监听作为兜底。框架一变它们会静默失效（只留一行日志）。
- `LogUtil.init()` 是在 `MainActivity.onCreate` 里调的，所以仅由服务拉起的进程只会往内存里记日志，不会写 `filesDir/logs/app.log`。
- 一处已知的毛边，代码里没有注释标注：`ScrapeService` 的结果回调里，`!success && !cardWanted` 这个分支（没搜到*且*该卡在排队期间被关掉了）不设置任何状态，任务会停在 `RUNNING` —— 空闲停止的检查把 `RUNNING` 算作有活干，于是服务可能迟迟不退、通知一直挂着，直到下次启动把它当作 PENDING 复活。动那个回调之前值得先确认一下。

## 日志

`LogUtil`（tag 为 `AV`/`AV-W`/`AV-E`）保留 500 行内存环形缓冲，**同时**追加写入 `filesDir/logs/app.log`，并维护一个 `version` 计数器，让日志对话框可以跳过无变化的重绘。这是本项目主要的调试面 —— 刮削代码非常刻意地记录了它的推理过程（XPath、各阶段耗时、页面状态、cookie 名、挑战脚本地址、响应头），而这些日志行正是当初定位 Cloudflare 行为的手段。改动刮削逻辑之前，先看日志对话框的输出。

## 计划文档

`.claude/plans/*.md` 是历史施工计划。其中几份带着明确的「已过时」横幅，并指向本文件；当它们与代码冲突时，以本文件为准。

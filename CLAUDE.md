# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build the project
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Clean build
./gradlew clean

# Run instrumentation tests (Android device/emulator required)
./gradlew connectedAndroidTest

# Run unit tests
./gradlew test
```

The project targets `compileSdk = 34`, `minSdk = 26`. All Maven dependencies are proxied through Aliyun mirrors (defined in `settings.gradle.kts`).

## Architecture

This is a single-module Android app (package `com.skyseec.avlist`) written entirely in **Java**. Gradle build scripts use Kotlin DSL. `viewBinding` is enabled.

### Data model (`CardItem`)

- Implements `Serializable`. Key identifying field is `code` ("番号" — a product code).
- **Normalization**: `normalizeCode()` strips all non-alphanumeric characters and uppercases. There is both an instance method (uses the object's own `code`) and a static overload `CardItem.normalizeCode(String input)`.
- **Duplicate detection**: `isSameCode(String)` compares normalized codes — never compare raw code strings directly.
- **`userEdited` flag**: When set to `true`, scraping will NOT overwrite `title`/`imageUrl` (the image is still downloaded to local storage). Set automatically when the user manually edits a card in `DetailActivity`.
- **`rating` field**: Integer 0–5, where 0 means unrated. Displayed as star icons in both the card grid and detail view. Set by tapping stars in `DetailActivity` or the edit dialog — sets `userEdited=true`.
- **Sentinel filtering**: `isBlankOrNullStr()` treats `"null"`, `"undefined"`, `"none"` as empty. Used by getters to keep UI clean.

### Data flow

1. **Persistence**: The entire card list is serialized to JSON via Gson and stored in `SharedPreferences` (key `app_cache` / `"data"`). On startup, `loadCacheData()` reads, deserializes, then calls `applySortAndFilter()` to rebuild the display list. `saveCacheData()` serializes `originList` and calls `apply()` (not `commit()`).
2. **Images**: Downloaded from the web to `context.filesDir/images/<card-id>.jpg`. `resolveLocalImagePath()` derives the path from id — images are NOT serialized into JSON (the `localImagePath` field is `transient`). Glide loads local files first, falls back to network URL with custom UA and Referer headers.
3. **Image management**: `copyImageToLocal(Uri, cardId)` copies from system gallery to internal storage (used by add dialog). `deleteLocalImage(CardItem)` removes the local image file (used by clear operation).

### Screen flow

- **MainActivity** — Grid card list (`RecyclerView` + `StaggeredGridLayoutManager` with 2 columns). Features: search bar (multi-token AND filtering across title/tag/code/remark/content), FAB to add cards, sort button (toolbar, cycles RATING/TIME/CODE ascending/descending), right-side drawer menu (export/import JSON, import text code list, multi-select mode, jump source mgmt, scrape source mgmt, log viewer), batch operations (delete/move-out/export). Long-press a card enters multi-select mode. Supports Quick Settings tile via `AddCardTileService`.
- **DetailActivity** — Full detail view of a single card. Receives `CardItem` via Intent extra (`"card_data"`). Shows image, code, title, tag, scrape toggle, timestamp, content, remark, star rating (tap to set, sets `userEdited=true`). Can trigger scraping individually. Editing sets `userEdited=true`.

### Sorting & filtering

- **Sort**: `SortField` enum — `RATING`, `TIME`, `CODE`. Default: `TIME` descending. Toggled via toolbar sort button → dialog with radio list. Selecting the same field again flips ascending/descending; switching fields defaults to descending.
- **Secondary sort**: When primary sort is not TIME, a secondary sort by time descending is always applied (e.g., same-rating items are time-ordered).
- **Filter**: Multi-token AND search. Input split on whitespace, each token must match at least one of title/tag/code/remark/content (case-insensitive substring).
- Both sort and filter are applied together in `applySortAndFilter()` which rebuilds `cardList` from `originList`.

### CardAdapter

Binds `CardItem` list to `RecyclerView` grid items (`item_card` layout). Key behaviors per card:
- **Image loading**: Prefers local file (`resolveLocalImagePath()`), falls back to network URL via Glide with custom UA and Referer headers. Relative image URLs are resolved via `ScrapeSource.getDefault().getBaseDomain()`. Hides `ImageView` when no image is available.
- **Star rating**: Displays 5 star icons (filled/empty) for items with `rating > 0`; hidden for unrated items.
- **Buttons**:
  - **跳转 (Jump)**: Builds URL from current `JumpSource` with the card's normalized code, opens in browser. Grayed out ("无跳转") when code is empty.
  - **刮削 (Scrape)**: Enqueues the card for scraping via `ScrapeService.enqueue()`.
  - **清除 (Clear)**: Visible only when the card has scraped title or image. Clears title, image URL, and deletes the local image file.
- **Multi-select**: Checkbox visible only in multi-select mode. Clicking the card body toggles the checkbox when in multi-select mode; otherwise opens `DetailActivity`.
- **Long press**: Enters multi-select mode (if not already in it).

### Web scraping (`ScrapeService` + `JavResolver` + `ScrapeSource` + `X5WebView`)

All scraping runs in a **foreground service** (`ScrapeService`) that survives screen-off and app backgrounding. Activities never create `JavResolver` directly — they enqueue `ScrapeTask` objects via `ScrapeService.enqueue()` / `enqueueAll()`.

- **`ScrapeService`**: Foreground service with notification ("刮削服务" channel). Owns its own `X5WebView`. Key behaviors:
  - **Task queue**: `ScrapeTask` list persisted to `SharedPreferences` (`scrape_queue_prefs`) for crash recovery. On restart, RUNNING tasks reset to PENDING and resumed.
  - **Sequential processing**: One scrape at a time, picks next PENDING task after current completes/fails.
  - **Idle stop**: Auto-`stopSelf()` after 5 seconds of empty queue.
  - **WakeLock**: `PARTIAL_WAKE_LOCK` acquired while scraping (10-min max), keeps CPU alive during screen-off.
  - **Card update**: Directly reads `app_cache` SharedPreferences, modifies the target `CardItem` by ID, writes back. Respects `userEdited` flag (skips overwrite).
  - **Image download**: Same `HttpURLConnection` logic moved into the service, saved to `filesDir/images/<cardId>.jpg`.
  - **Broadcast**: Sends `BROADCAST_PROGRESS` on every queue state change. Activities listen to refresh UI.
  - **Intent actions**: `ACTION_ENQUEUE` (with `EXTRA_TASKS_JSON`), `ACTION_CANCEL_ALL`.
- **`ScrapeTask`**: Data model for a queued task. Fields: `id`, `cardId`, `code`, `scrapeSourceId`, `status` (PENDING/RUNNING/COMPLETED/FAILED), `title`, `imgUrl`, `errorMessage`, `createdAt`. Has `ScrapeTask.from(CardItem, ScrapeSource)` factory method.
- **`ScrapeSource`**: Configurable scraping target. Fields: `baseUrl` (with `{keyword}` placeholder), `titleXPath`, `imageXPath`, `waitSeconds` (default 3), `needsPrewarm`. Persisted to `scrape_source_prefs`. Built-in defaults: **MissAV** (default, pre-warm enabled, 8s wait) and **JavBus**. Provides `getBaseDomain()` (extracts `protocol://host` from `baseUrl`) and `resolveImageUrl()` (handles relative URL completion and filters data/blob URIs) — used by `CardAdapter` for image loading with correct Referer.
- **`X5WebView`**: Extends `WebView` with PC Chrome UA, hardware acceleration, `injectStealthScript()` for bot evasion, `injectReadyCheck()` for DOM-ready polling, `stopReadyCheck()`.
- **`JavResolver`**: Orchestrates a single scrape. Flow: pre-warm (if needed) → load search URL → DOM-ready polling → XPath parse. Has **90-second overall timeout** — if no result within 90s, forces callback with null results. Retries up to 2 times on empty results.
- **Activity integration**: `MainActivity` and `DetailActivity` register `BroadcastReceiver` for `BROADCAST_PROGRESS` in `onResume`, unregister in `onPause`. On receive → `loadCacheData()` → refresh UI. Activities also call `loadCacheData()` in `onResume` to pick up changes made while backgrounded.

### Configurable sources

- **`ScrapeSource`**: Manageable from drawer menu → "刮削源管理". Supports add/edit/delete, set default. Each source defines XPath rules for title and image extraction, wait time, and pre-warm behavior. At least one source must exist.
- **`JumpSource`**: Defines where the "跳转" button in card items navigates to. URL template with `{keyword}` placeholder. Managed from drawer menu → "跳转源管理". At least one source must exist. Default: MissAV.

### Quick Settings tile (`AddCardTileService`)

Extends `TileService`. Sets `MainActivity.pendingShowAddDialog = true` then launches `MainActivity`. On resume/new-intent, `tryShowAddFromTile()` checks this flag and auto-opens the add dialog after a 400ms delay.

### Import/Export

- **Add card dialog**: FAB opens dialog with fields for code, title, tag, content, remark, and star rating. Includes a system image picker (`ACTION_GET_CONTENT`) — selected image is copied to internal storage (`filesDir/images/<cardId>.jpg`).
- **Import JSON**: SAF file picker (`ACTION_OPEN_DOCUMENT`), reads JSON, merges into `originList` with duplicate code check. Invalid codes (empty after normalization) are skipped and reported.
- **Import text code list**: Paste raw text (one code per line), each line becomes a new `CardItem` with `scrapeEnabled=true`. Auto-scrapes in sequence with 2-second delays between items.
- **Export JSON**: SAF file creator (`ACTION_CREATE_DOCUMENT`), writes full or selected list as JSON.
- **Move-out**: Export + delete from local storage in one operation, via `pendingMoveOut` flag.

### Logging

`LogUtil` provides `i()`, `w()`, `e()` static methods. Writes timestamped lines to both `Log.d` and an internal file (`logs/app.log` in `filesDir`). Keeps the last 500 lines in memory. Viewable via drawer menu → "运行日志" dialog, which also supports copy, export, and clear.

### BackupManager

`BackupManager` handles scheduled data backups. Key behaviors:
- **Strategy**: Records last backup **date** (not time). On each app cold start (`checkAndBackup()` called in `MainActivity.onCreate`), compares today's date with last backup date. If the day difference ≥ configured interval, backs up once. No periodic polling — the entire process lifetime needs no further checks.
- **Two backup paths**:
  - **Auto backup** (silent, no user interaction): Writes to `context.getExternalFilesDir(null)/AvListBackups/` — accessible via file manager at `/storage/emulated/0/Android/data/com.skyseec.avlist/files/AvListBackups/`. No special permissions required.
  - **Manual export** (SAF file picker): "导出备份" button in settings dialog calls `MainActivity.exportJsonToDownload()` — same as drawer "导出 JSON". After successful SAF write, calls `BackupManager.recordBackupDone()` to update the last-backup date.
- **`getDataJson(Context)`**: Public method to get current cache JSON. Used by both auto-backup and `exportJsonToDownload()`.
- **`recordBackupDone(Context)`**: Called after any successful backup (auto or manual SAF export) to stamp today's date.
- **Retention**: Keeps N most recent auto-backup files (configurable 1–10, default 3). Automatically trims oldest when limit exceeded.
- **Dedup**: Before writing auto-backup, compares against latest backup file content. If identical, skips writing and just updates date.
- **Settings dialog**: Drawer menu → "定时备份" → configure backup count (Spinner), interval in days (1–30), see last backup date, auto-backup directory path, list existing auto-backup files, manual "导出备份（选择保存位置）" button → SAF file picker.
- **Config**: Stored in `backup_prefs` SharedPreferences (`backup_count`, `backup_interval_days`, `last_backup_date`).

## Key libraries

| Library | Purpose |
|---------|---------|
| Glide 4.16 | Image loading (with custom User-Agent/Referer headers) |
| Gson 2.10.1 | JSON serialization of card data and source configs |
| Material Components | UI theming (Material3 DayNight NoActionBar) |
| AndroidX AppCompat / RecyclerView / CoordinatorLayout | Layout infrastructure |

## Navigation / Back stack

`MainActivity` handles its own back press via `OnBackPressedDispatcher`:
1. If in multi-select mode → exit multi-select
2. If drawer is open (`GravityCompat.END`) → close drawer
3. If `verifyWebView` is visible → hide it and stop loading
4. Otherwise → default system back

## Drawer behavior

- **Open**: `btnMore` (overflow icon in toolbar) or right-edge swipe.
- **`DrawerEdgeHelper`**: Custom `OnTouchListener` on `contentLayout`. Detects left-swipe from right 70% of screen (configurable `startRatio=0.3f`), requires 40dp horizontal displacement with horizontal dominance (dx > dy × 1.2). Cancels tracking if vertical movement dominates.
- **Edge size**: Drawer's right-edge detection is enlarged to ~80dp via reflection on `mRightDragger.mEdgeSize`.

## Code conventions

- All source code is under `app/src/main/java/com/skyseec/avlist/`
- Comments are in Chinese, code identifiers in English
- Code normalization is a core concept — always use `CardItem.normalizeCode()` for code comparison
- `originList` is the source of truth; `cardList` is the filtered/displayed subset
- ViewBinding is used via `ActivityMainBinding`; other Activities use `findViewById`
- Source configs (`ScrapeSource`, `JumpSource`) and scrape queue are stored in separate `SharedPreferences` files (`scrape_source_prefs`, `jump_source_prefs`, `scrape_queue_prefs`), NOT in `app_cache`
- Scraping is delegated to `ScrapeService` (foreground service) — Activities never create `JavResolver` or hold `scrapeBusy` directly. Use `ScrapeService.enqueue()` / `enqueueAll()`.
- UI updates after scraping come via `BROADCAST_PROGRESS` → `loadCacheData()` → `adapter.notifyDataSetChanged()`, or via `onResume` reload.

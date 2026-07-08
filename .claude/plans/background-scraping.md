# Background Scraping Implementation Plan

## Problem
Scraping fails when phone screen turns off or app loses focus because:
1. Everything runs on Activity's main thread / Activity-owned WebView — dies with Activity
2. `scrapeBusy` has no timeout — stuck forever if WebView never calls back
3. No cancellation chain — batch import chain keeps running even after Activity destroyed
4. Doze mode kills network access

## Solution: Foreground Service

Move all scraping into a foreground `Service` that:
- Owns its own `X5WebView`
- Runs independently of Activity lifecycle
- Shows an ongoing notification (keeps process alive, bypasses Doze)
- Has per-task timeout (90s)
- Supports cancellation
- Persists task queue for crash recovery

---

## New Files

### 1. `ScrapeTask.java`
Simple data model for a queued scrape task:
- Fields: `id`, `cardId`, `code`, `scrapeSourceId`, `status` (PENDING/RUNNING/COMPLETED/FAILED), `title`, `imgUrl`, `errorMessage`, `createdAt`
- Serialized with Gson, stored as JSON list in SharedPreferences `"scrape_queue"`

### 2. `ScrapeService.java`
Foreground service, the core of the change:
- **Lifecycle**: Starts on first enqueue, stops when queue empty + 5s idle
- **Queue**: In-memory `List<ScrapeTask>` synced to `SharedPreferences` after each change
- **Processing**: Sequential — picks first PENDING task, sets to RUNNING, starts `JavResolver`
- **Timeout**: `JavResolver` gets a 90-second timeout `Handler`; on timeout → mark FAILED, move to next
- **Card update**: On completion, reads `app_cache` SharedPreferences, finds card by ID, updates title/imgUrl, saves back
- **Notification**: Shows "正在刮削 x/N" with progress; collapses to "刮削已完成" when idle
- **Intent actions**:
  - `ACTION_ENQUEUE` — add task(s) to queue (extras: `"tasks_json"`)
  - `ACTION_CANCEL_ALL` — clear pending queue
- **Broadcasts**: `ACTION_SCRAPE_PROGRESS` when task state changes (for Activity UI refresh)
- **Recovery**: `onCreate()` loads saved queue from SharedPreferences, resumes processing

### 3. `res/drawable/ic_scrape_notification.xml`
Simple vector drawable (cloud-download icon, white on transparent) for notification.

---

## Modified Files

### 4. `JavResolver.java`
- Add `timeoutHandler` field, `TIMEOUT_MS = 90_000`
- In `startScrape()`: start timeout handler that calls `callback.onResult(null, null, null)` with an error flag
- In `parseAndHandle()` success callback: cancel timeout before delivering result
- On error/failure redirects: cancel timeout first

### 5. `MainActivity.java`
- **Remove**: `scrapeBusy` field (service handles serialization)
- **Keep**: `verifyWebView` (for debug/verification display only, not scraping)
- **`showAddDialog()`**: Replace `new JavResolver(verifyWebView, ...)` + manual threading → `ScrapeService.enqueue(context, task)`
- **`importCodeListFromText()`**: Replace `Handler` serial chain → batch enqueue all tasks to service
- **`onItemScrape()`**: Replace `new JavResolver(verifyWebView, ...)` → enqueue to service
- **Add**: `BroadcastReceiver` for `ACTION_SCRAPE_PROGRESS` — registered in `onResume`, unregistered in `onPause`
- **On progress broadcast**: call `loadCacheData()` + `adapter.notifyDataSetChanged()`

### 6. `DetailActivity.java`
- **Remove**: `scrapeWebView` field (no longer needed)
- **`triggerScrape()`**: Replace `new JavResolver(scrapeWebView, ...)` → enqueue to service
- **Add**: `BroadcastReceiver` for progress (optional — just Toast on completion via broadcast extra)

### 7. `AndroidManifest.xml`
- Add permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`
- Register `<service>` for `ScrapeService` with `android:foregroundServiceType="dataSync"`

### 8. `res/values/strings.xml`
- Add `scrape_notification_channel_name` = "刮削服务"
- Add `scrape_notification_channel_desc` = "后台刮削进度"

---

## Data Flow After Change

```
User adds card → Activity saves to originList + enqueues task to Service
                        ↓
Service picks task → loads search page in its own WebView → parses → downloads image
                        ↓
Service updates card in SharedPreferences → broadcasts ACTION_SCRAPE_PROGRESS
                        ↓
Activity (if visible) receives broadcast → loadCacheData() → refresh RecyclerView
                        ↓
Service picks next task (or idles 5s → stopSelf)
```

## Edge Cases Handled

| Case | Handling |
|------|----------|
| WebView hangs | 90s timeout → mark FAILED → move to next task |
| Screen off | Foreground service + wakelock bypasses Doze |
| App swiped away | Service continues (foreground notification) |
| Service killed by system | Queue persisted to SharedPreferences → resumed on restart |
| Multiple rapid scrape requests | Sequential queue, no concurrent scrapes |
| Queue empty | Service stops itself after 5s idle |
| No network | WebView `onReceivedError` → mark FAILED → next task |

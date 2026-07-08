# Plan: Add Rating, Complete Search, and Sorting Features

## Overview
Three features to add to the AvList Android app: star rating (card + detail page), comprehensive search, and multi-field sorting.

---

## 1. Rating (评星) — `int rating` field 0-5

### 1.1 CardItem.java
- Add `private int rating = 0;` (0 = unrated, 1-5 stars)
- Add `getRating()` / `setRating(int)` with bounds clamping (0-5)

### 1.2 item_card.xml
- Add a row of 5 `ImageView` stars below tvCreateTime, above the button row
- Use a horizontal `LinearLayout` with tiny star icons

### 1.3 CardAdapter.java
- Add `ImageView[] starViews` (5 views) to `CardViewHolder`
- In `onBindViewHolder`: loop 1-5, set filled/empty star drawable based on `item.getRating()`
- Hide star row when rating = 0 (unrated)

### 1.4 activity_detail.xml
- Add rating row between tag and buttons section: 5 clickable star ImageViews
- Each star clickable to set rating 1-5

### 1.5 DetailActivity.java
- Add `ImageView[] detailStars` fields
- `bindData()` → update star display
- Click listener on each star: set rating = starIndex+1, update display, save
- Edit dialog: add star rating row (5 clickable stars, same pattern)

### 1.6 dialog_add.xml
- Add a star rating row (5 ImageView, clickable) below etRemark, above llScrapeRow
- Default: 0 stars (unrated)

### 1.7 MainActivity.java (add dialog)
- Read rating from the star selector in the add dialog
- Pass rating to `CardItem` constructor

---

## 2. Search Enhancement

### 2.1 Current state
The `filterData()` method already searches: title, tag, code, remark, content (case-insensitive, multi-token AND). This is functionally complete.

### 2.2 Changes
- **activity_main.xml**: Update `etSearch` hint from `"搜索番号、标题、标签、备注"` to `"搜索番号、标题、标签、备注、内容"` (add "内容")
- No code logic changes needed — the filtering already works correctly

---

## 3. Sorting (排序)

### 3.1 Sort options
Three fields, each with ascending/descending:
| Field | Ascending | Descending |
|-------|-----------|------------|
| 星级 (Rating) | 0→5 | 5→0 |
| 添加时间 (Create Time) | oldest first | newest first (default) |
| 番号首字母 (Code) | A→Z | Z→A |

### 3.2 UI approach
- Add a sort button (icon) in the header bar, between search CardView and btnMore
- Click opens a popup menu or bottom sheet with sort options
- Current sort is shown as an indicator (small text or highlighted item)
- Simple AlertDialog with radio-button list is the most straightforward approach

### 3.3 Sort state
- Store in MainActivity: `sortField` (enum: RATING, TIME, CODE) + `sortAscending` (boolean)
- Default: TIME descending (current behavior)
- After every sort change: re-run `applySortAndFilter()`

### 3.4 Sort logic
- In `applySortAndFilter()`:
  1. Start from `originList`
  2. Apply filter (current search keyword) → filtered list
  3. Apply sort on filtered list → `cardList`
  4. Notify adapter

### 3.5 Implementation in MainActivity
- Add enum `SortField { RATING, TIME, CODE }`
- Fields: `SortField currentSortField = SortField.TIME`, `boolean sortAscending = false`
- Method `applySortAndFilter()` — replaces inline filterData logic
- `filterData(String keyword)` → stores keyword, calls `applySortAndFilter()`
- New method `showSortDialog()` → shows AlertDialog with sort options
- Comparator implementations for each sort field+direction

---

## Files changed

| File | Change type |
|------|-------------|
| `CardItem.java` | Add `rating` field + getter/setter |
| `item_card.xml` | Add star rating display row |
| `CardAdapter.java` | Bind star views, new fields in ViewHolder |
| `activity_main.xml` | Add sort button, update search hint |
| `MainActivity.java` | Sort logic, sort dialog, sort button handler, read rating in add dialog |
| `activity_detail.xml` | Add rating row with clickable stars |
| `DetailActivity.java` | Star display + edit + save, rating in edit dialog |
| `dialog_add.xml` | Add rating selector row |

---

## Star drawables
- Use Android built-in star drawables: `android.R.drawable.btn_star_big_on` (filled) and `android.R.drawable.btn_star_big_off` (empty)
- Or create small custom ones — but built-in are simpler and consistent
- Scale: ~16-20dp per star for card, ~28-32dp for detail/edit

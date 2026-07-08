# Local Image Picker Plan

## Problem
Cards can only get images from web URLs or scraping. User cannot select a photo from their gallery.

## Solution
Add a "选择图片" button in the add/edit dialog. Selected image is copied to `filesDir/images/<card-id>.jpg` — same path where scraped images go. Glide already loads local files first, so no other changes needed.

**No image hosting service required.**

## Changes

### 1. `dialog_add.xml`
Add below `etImageUrl`:
```xml
<LinearLayout horizontal>
    <AppCompatButton android:id="@+id/btnPickImage" text="选择图片" />
    <ImageView android:id="@+id/ivImagePreview" 80x80dp, initially GONE />
</LinearLayout>
```

### 2. `MainActivity.java`
- Add `ActivityResultLauncher<String> pickImageLauncher` using `GetContent("image/*")`
- In `showAddDialog()`: wire `btnPickImage` → launch picker; on result → show preview in `ivImagePreview`, store picked URI
- On dialog submit: if picked URI exists → copy to `filesDir/images/<card-id>.jpg` → set `localImagePath`

### 3. `DetailActivity.java`
- Add same `pickImageLauncher`
- In `showEditDialog()`: wire `btnPickImage` (not hidden), show preview, store URI
- On dialog save: copy image (card already has ID), set `localImagePath`, mark `userEdited=true`

### 4. `item_card.xml` (CardAdapter)
- No change. Glide logic already loads local files first.

## Image copy helper
```java
private void copyImageToLocal(Uri sourceUri, String cardId) {
    try {
        InputStream is = getContentResolver().openInputStream(sourceUri);
        File dir = new File(getFilesDir(), "images");
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, cardId + ".jpg");
        FileOutputStream fos = new FileOutputStream(outFile);
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
        fos.close();
        is.close();
    } catch (Exception e) { LogUtil.e("图片复制失败", e); }
}
```

## Permissions
None needed. System picker grants temporary URI access.

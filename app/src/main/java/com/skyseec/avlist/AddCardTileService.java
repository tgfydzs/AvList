package com.skyseec.avlist;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 下拉快捷设置瓷砖 — 快速新增卡片。
 * 点击后启动 MainActivity 并自动弹出新增对话框。
 */
public class AddCardTileService extends TileService {

    @Override
    public void onClick() {
        super.onClick();

        try {
            Tile tile = getQsTile();
            if (tile != null) {
                tile.setState(Tile.STATE_ACTIVE);
                tile.updateTile();
            }

            // 设静态标记，MainActivity 恢复/启动时检查
            MainActivity.pendingShowAddDialog = true;

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivityAndCollapse(intent);
        } catch (Exception e) {
            LogUtil.e("Tile点击异常", e);
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }
}

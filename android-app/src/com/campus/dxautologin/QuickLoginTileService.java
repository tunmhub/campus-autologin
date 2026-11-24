package com.campus.dxautologin;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 鸿蒙下拉控制中心快捷磁贴：点一下即可在后台静默执行一次检测与登录。
 */
public class QuickLoginTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("DX校园网");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("点击静默登录");
            }
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        WifiMonitorService.ensureRunning(this);
        final Tile tile = getQsTile();
        if (tile != null) {
            tile.setLabel("正在检测...");
            tile.updateTile();
        }
        CampusLoginEngine.triggerAsync(this, "控制中心磁贴", true, new CampusLoginEngine.Callback() {
            @Override
            public void onResult(boolean success, String summary) {
                if (tile != null) {
                    tile.setLabel(success ? "DX已在线" : "DX登录失败");
                    tile.updateTile();
                }
            }
        });
    }
}

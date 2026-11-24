package com.campus.dxautologin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        WifiMonitorService.ensureRunning(context);
        CampusLoginEngine.triggerAsync(context, "系统开机自检", false, null);
    }
}

package com.campus.dxautologin;

import android.app.Activity;
import android.os.Bundle;

/**
 * 透明无窗口静默入口（Theme.NoDisplay）：
 * 供鸿蒙 4.2 “智慧场景”或快捷方式调用，启动瞬间唤醒后台服务并触发登录，
 * 随后立即 finish()，屏幕上零窗口闪烁、绝不打断平板当前正在运行的任何应用。
 */
public class SilentLoginActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WifiMonitorService.ensureRunning(this);
        CampusLoginEngine.triggerAsync(this, "静默场景触发", false, null);
        finish();
        overridePendingTransition(0, 0);
    }
}

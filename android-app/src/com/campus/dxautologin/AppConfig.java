package com.campus.dxautologin;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppConfig {
    private static final String PREF_NAME = "dx_campus_autologin_prefs";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_LOGIN_URL = "login_url";
    private static final String KEY_PROBE_URL = "probe_url";
    private static final String KEY_AUTO_ENABLED = "auto_enabled";
    private static final String KEY_PREFER_DX = "prefer_dx_over_gd";
    private static final String KEY_LOGS = "run_logs";

    // 开源通用版默认置空，首次打开应用在界面填写保存即可
    public static final String DEFAULT_USERNAME = "";
    public static final String DEFAULT_PASSWORD = "";
    public static final String DEFAULT_SSID = "DX-WLAN";
    public static final String DEFAULT_LOGIN_URL = "http://172.18.0.11/cgi-bin/wlogin.cgi";
    public static final String DEFAULT_PROBE_URL = "http://172.18.0.11/";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static String getUsername(Context ctx) {
        return prefs(ctx).getString(KEY_USERNAME, DEFAULT_USERNAME).trim();
    }

    public static String getPassword(Context ctx) {
        return prefs(ctx).getString(KEY_PASSWORD, DEFAULT_PASSWORD);
    }

    public static String getSsid(Context ctx) {
        return prefs(ctx).getString(KEY_SSID, DEFAULT_SSID).trim();
    }

    public static String getLoginUrl(Context ctx) {
        return prefs(ctx).getString(KEY_LOGIN_URL, DEFAULT_LOGIN_URL).trim();
    }

    public static String getProbeUrl(Context ctx) {
        return prefs(ctx).getString(KEY_PROBE_URL, DEFAULT_PROBE_URL).trim();
    }

    public static boolean isAutoEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_ENABLED, true);
    }

    public static boolean isPreferDxEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PREFER_DX, true);
    }

    public static void saveConfig(Context ctx, String username, String password, String ssid, String loginUrl, boolean autoEnabled, boolean preferDx) {
        prefs(ctx).edit()
                .putString(KEY_USERNAME, username == null ? "" : username.trim())
                .putString(KEY_PASSWORD, password == null ? "" : password)
                .putString(KEY_SSID, ssid == null ? DEFAULT_SSID : ssid.trim())
                .putString(KEY_LOGIN_URL, (loginUrl == null || loginUrl.trim().isEmpty()) ? DEFAULT_LOGIN_URL : loginUrl.trim())
                .putBoolean(KEY_AUTO_ENABLED, autoEnabled)
                .putBoolean(KEY_PREFER_DX, preferDx)
                .apply();
    }

    public static synchronized void appendLog(Context ctx, String level, String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        String line = sdf.format(new Date()) + " [" + level + "] " + message;
        SharedPreferences sp = prefs(ctx);
        String oldLogs = sp.getString(KEY_LOGS, "");
        String updated = oldLogs.isEmpty() ? line : (line + "\n" + oldLogs);
        String[] lines = updated.split("\n");
        if (lines.length > 80) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 80; i++) {
                if (i > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            updated = sb.toString();
        }
        sp.edit().putString(KEY_LOGS, updated).apply();
    }

    public static String getLogs(Context ctx) {
        String logs = prefs(ctx).getString(KEY_LOGS, "");
        return logs.isEmpty() ? "暂无运行记录（连上 DX-WLAN 或在宿舍从 GD-WLAN 自动切换时将静默记录）" : logs;
    }

    public static void clearLogs(Context ctx) {
        prefs(ctx).edit().remove(KEY_LOGS).apply();
    }
}

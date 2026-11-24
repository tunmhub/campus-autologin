package com.campus.dxautologin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class CampusLoginEngine {
    private static final String FAIL_CHANNEL_ID = "dx_login_failure_channel";
    private static final int FAIL_NOTIFICATION_ID = 2002;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IS_RUNNING = new AtomicBoolean(false);
    private static volatile long lastOnlineLogTimeMs = 0L;
    private static volatile long lastSwitchAttemptTimeMs = 0L;

    public interface Callback {
        void onResult(boolean success, String summary);
    }

    public static void triggerAsync(final Context context, final String triggerSource, final boolean isManual, final Callback callback) {
        final Context appCtx = context.getApplicationContext();
        if (!isManual && !AppConfig.isAutoEnabled(appCtx)) {
            return;
        }
        if (!IS_RUNNING.compareAndSet(false, true)) {
            if (isManual) {
                notifyCallback(callback, false, "正在执行检测中，请稍候...");
            }
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runLoginFlow(appCtx, triggerSource, isManual, callback);
                } catch (Throwable t) {
                    AppConfig.appendLog(appCtx, "ERROR", "执行异常(" + triggerSource + "): " + t.getMessage());
                    if (isManual) {
                        notifyCallback(callback, false, "异常: " + t.getMessage());
                    }
                } finally {
                    IS_RUNNING.set(false);
                }
            }
        });
    }

    private static void runLoginFlow(Context ctx, String source, boolean isManual, Callback callback) {
        String username = AppConfig.getUsername(ctx);
        String password = AppConfig.getPassword(ctx);
        String targetSsid = AppConfig.getSsid(ctx);
        String loginUrl = AppConfig.getLoginUrl(ctx);
        String probeUrl = AppConfig.getProbeUrl(ctx);

        if (username.isEmpty() || password.isEmpty()) {
            AppConfig.appendLog(ctx, "ERROR", "账号或密码为空，请先打开 DX校园网设置 填写");
            showFailureNotification(ctx, "账号或密码为空，请打开 DX校园网设置 填写。");
            notifyCallback(callback, false, "账号或密码为空");
            return;
        }

        // 注册高优先级网络建议（辅助系统底层提高 DX-WLAN 优先级）
        registerHighPrioritySuggestion(ctx, targetSsid);

        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network wifiNetwork = findActiveWifiNetwork(cm);

        // 若当前未连接任何 Wi-Fi，但周围有 DX-WLAN 信号，也尝试主动连接 DX-WLAN
        if (wifiNetwork == null) {
            if (AppConfig.isPreferDxEnabled(ctx) && !targetSsid.isEmpty() && isTargetSsidInRange(ctx, targetSsid)) {
                AppConfig.appendLog(ctx, "INFO", "[" + source + "] 当前未连 Wi-Fi，检测到周围有 [" + targetSsid + "]，尝试自动连接...");
                if (switchToTargetWifi(ctx, targetSsid)) {
                    wifiNetwork = waitForWifiConnected(cm, ctx, targetSsid, 7000);
                }
            }
            if (wifiNetwork == null) {
                if (isManual) {
                    AppConfig.appendLog(ctx, "INFO", "[" + source + "] 当前未连接任何 Wi-Fi（周围亦未扫到 " + targetSsid + "）");
                    notifyCallback(callback, false, "当前未连接 Wi-Fi");
                }
                return;
            }
        }

        // 1. 检查当前连接的 SSID：若当前连的是 GD-WLAN 等其他 Wi-Fi，检查周围是否有 DX-WLAN 信号！
        String currentSsid = getReadableSsid(ctx);
        if (currentSsid != null && !targetSsid.isEmpty() && !currentSsid.equalsIgnoreCase(targetSsid)) {
            if (AppConfig.isPreferDxEnabled(ctx) && isTargetSsidInRange(ctx, targetSsid)) {
                long now = System.currentTimeMillis();
                if (isManual || (now - lastSwitchAttemptTimeMs > 15_000L)) {
                    lastSwitchAttemptTimeMs = now;
                    AppConfig.appendLog(ctx, "INFO", "[" + source + "] 当前连在 [" + currentSsid + "]，检测到宿舍 [" + targetSsid + "] 信号，正在自动切换至 [" + targetSsid + "]...");
                    boolean switched = switchToTargetWifi(ctx, targetSsid);
                    if (switched) {
                        Network newWifi = waitForWifiConnected(cm, ctx, targetSsid, 8000);
                        if (newWifi != null) {
                            wifiNetwork = newWifi;
                            currentSsid = getReadableSsid(ctx);
                            AppConfig.appendLog(ctx, "OK", "[" + source + "] 已成功从前期网络切换至 [" + targetSsid + "]，继续检查登录...");
                        } else {
                            AppConfig.appendLog(ctx, "WARN", "[" + source + "] 已发起切换至 [" + targetSsid + "]，等待系统完成连接（建议在平板 WLAN 设置中关闭 WLAN+）");
                            if (isManual) {
                                notifyCallback(callback, false, "已发起切换到 " + targetSsid + "，若系统未切过去请关闭鸿蒙 WLAN+");
                            }
                            return;
                        }
                    } else {
                        AppConfig.appendLog(ctx, "WARN", "[" + source + "] 自动切换到 [" + targetSsid + "] 受系统限制，请在平板设置中关闭「WLAN+」");
                    }
                }
            } else {
                // 周围没有 DX-WLAN 信号（在教学楼/图书馆），安心保持在 GD-WLAN，绝不打扰！
                if (isManual) {
                    AppConfig.appendLog(ctx, "INFO", "[" + source + "] 当前 Wi-Fi [" + currentSsid + "] 且周围无 [" + targetSsid + "] 信号（不在宿舍），保持当前连接");
                    notifyCallback(callback, true, "当前连在 " + currentSsid + "（周围无 " + targetSsid + " 信号，保持连接）");
                }
                return;
            }
        }

        // 若再次检查仍确定在非目标 Wi-Fi 下，直接返回
        currentSsid = getReadableSsid(ctx);
        if (currentSsid != null && !targetSsid.isEmpty() && !currentSsid.equalsIgnoreCase(targetSsid)) {
            return;
        }

        // 2. 第一轮在线探测（绑定 Wi-Fi 网卡通道，绝不误走流量或 VPN）
        if (isWifiOnline(wifiNetwork)) {
            long now = System.currentTimeMillis();
            if (isManual || (now - lastOnlineLogTimeMs > 120_000L)) {
                lastOnlineLogTimeMs = now;
                AppConfig.appendLog(ctx, "INFO", "[" + source + "] DX-WLAN 网络已在线，无需重复登录");
            }
            if (cm != null) {
                try {
                    cm.reportNetworkConnectivity(wifiNetwork, true);
                } catch (Throwable ignored) {}
            }
            notifyCallback(callback, true, "当前 DX-WLAN 已在线，无需登录");
            return;
        }

        // 3. 确认是否处于校园网环境：探测内网网关 http://172.18.0.11/ 是否可达
        if (!waitForPortalReachable(wifiNetwork, probeUrl, isManual ? 6000 : 12000)) {
            if (isManual) {
                AppConfig.appendLog(ctx, "WARN", "[" + source + "] 校园网网关 (172.18.0.11) 不可达，当前不在宿舍 DX-WLAN 网络下");
                notifyCallback(callback, false, "未检测到校园网网关 (172.18.0.11)，当前不在 DX-WLAN 下");
            }
            return;
        }

        // 4. 二次确认离线（防止连网瞬间抖动误判）
        sleepQuietly(800);
        if (isWifiOnline(wifiNetwork)) {
            AppConfig.appendLog(ctx, "INFO", "[" + source + "] 复检网络已在线，无需登录");
            notifyCallback(callback, true, "网络已在线");
            return;
        }

        AppConfig.appendLog(ctx, "INFO", "[" + source + "] 检测到 DX-WLAN 离线，开始自动登录...");

        // 5. 提交 Dr.COM 表单登录
        String lastResponseSnippet = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            lastResponseSnippet = sendPortalLoginPost(wifiNetwork, loginUrl, username, password);
            sleepQuietly(1500);

            if (isWifiOnline(wifiNetwork)) {
                AppConfig.appendLog(ctx, "OK", "[" + source + "] 登录成功，网络已恢复（第 " + attempt + " 次尝试）");
                if (cm != null) {
                    try {
                        cm.reportNetworkConnectivity(wifiNetwork, true);
                    } catch (Throwable ignored) {}
                }
                notifyCallback(callback, true, "登录成功，网络已恢复！");
                return;
            }

            if (attempt == 1) {
                AppConfig.appendLog(ctx, "WARN", "[" + source + "] 第 1 次登录尝试未生效，3 秒后重试...");
                sleepQuietly(3000);
            }
        }

        // 6. 连续 2 次失败：写日志并弹出系统通知栏提醒
        if (lastResponseSnippet.isEmpty()) {
            lastResponseSnippet = "(Portal 无响应)";
        } else if (lastResponseSnippet.length() > 160) {
            lastResponseSnippet = lastResponseSnippet.substring(0, 160);
        }
        String errMsg = "连续 2 次尝试登录仍无法上网，请检查账号密码或校园网状态。响应: " + lastResponseSnippet;
        AppConfig.appendLog(ctx, "ERROR", "[" + source + "] " + errMsg);
        showFailureNotification(ctx, "连续 2 次登录未成功，请检查校园网账号密码或认证状态。");
        notifyCallback(callback, false, "登录失败，请检查账号密码或查看日志");
    }

    /**
     * 检测周围 Wi-Fi 扫描列表中是否存在目标 SSID（如 DX-WLAN）且信号强度合理 (>= -82 dBm)
     */
    public static boolean isTargetSsidInRange(Context ctx, String targetSsid) {
        if (targetSsid == null || targetSsid.isEmpty()) return false;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return false;

            try {
                wm.startScan();
            } catch (Throwable ignored) {}

            List<ScanResult> results = wm.getScanResults();
            if (results != null) {
                for (ScanResult sr : results) {
                    if (sr != null && sr.SSID != null && sr.SSID.equalsIgnoreCase(targetSsid)) {
                        if (sr.level >= -84) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 在检测到宿舍 DX-WLAN 信号时，主动从 GD-WLAN 切换连接至 DX-WLAN
     */
    private static boolean switchToTargetWifi(Context ctx, String targetSsid) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;

            String quotedSsid = "\"" + targetSsid + "\"";
            int targetNetId = -1;

            List<WifiConfiguration> configs = null;
            try {
                configs = wm.getConfiguredNetworks();
            } catch (Throwable ignored) {}

            if (configs != null) {
                for (WifiConfiguration cfg : configs) {
                    if (cfg != null && cfg.SSID != null &&
                            (cfg.SSID.equalsIgnoreCase(quotedSsid) || cfg.SSID.equalsIgnoreCase(targetSsid))) {
                        targetNetId = cfg.networkId;
                        cfg.priority = 99999;
                        try {
                            wm.updateNetwork(cfg);
                        } catch (Throwable ignored) {}
                        break;
                    }
                }
            }

            if (targetNetId == -1) {
                WifiConfiguration openCfg = new WifiConfiguration();
                openCfg.SSID = quotedSsid;
                openCfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                openCfg.priority = 99999;
                try {
                    targetNetId = wm.addNetwork(openCfg);
                } catch (Throwable ignored) {}
            }

            if (targetNetId != -1) {
                wm.disconnect();
                boolean enabled = wm.enableNetwork(targetNetId, true);
                wm.reconnect();
                return enabled;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 向系统注册高优先级 WifiNetworkSuggestion，让系统在自动选网时把 DX-WLAN 排在最高优先级
     */
    private static void registerHighPrioritySuggestion(Context ctx, String targetSsid) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetSsid != null && !targetSsid.isEmpty()) {
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                        .setSsid(targetSsid)
                        .setPriority(1000)
                        .setIsAppInteractionRequired(false)
                        .build();
                List<WifiNetworkSuggestion> list = new ArrayList<>();
                list.add(suggestion);
                wm.addNetworkSuggestions(list);
            } catch (Throwable ignored) {}
        }
    }

    private static Network waitForWifiConnected(ConnectivityManager cm, Context ctx, String targetSsid, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(600);
            Network net = findActiveWifiNetwork(cm);
            if (net != null) {
                String ssid = getReadableSsid(ctx);
                if (ssid != null && ssid.equalsIgnoreCase(targetSsid)) {
                    return net;
                }
            }
        }
        return null;
    }

    public static Network findActiveWifiNetwork(ConnectivityManager cm) {
        if (cm == null) return null;
        try {
            Network[] networks = cm.getAllNetworks();
            if (networks != null) {
                for (Network net : networks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return net;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static String getReadableSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null) {
                        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                        if (!ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                            return ssid;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isWifiOnline(Network wifiNetwork) {
        if (checkHttp204(wifiNetwork, "http://connect.rom.miui.com/generate_204")) {
            return true;
        }
        if (checkHttp204(wifiNetwork, "http://www.gstatic.com/generate_204")) {
            return true;
        }
        return checkCaptiveApple(wifiNetwork, "http://captive.apple.com/hotspot-detect.html");
    }

    private static boolean checkHttp204(Network network, String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) network.openConnection(url);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            return code == 204;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean checkCaptiveApple(Network network, String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) network.openConnection(url);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code != 200) return false;
            String body = readStream(conn.getInputStream());
            return body.contains("Success");
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean waitForPortalReachable(Network network, String probeUrl, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(probeUrl);
                conn = (HttpURLConnection) network.openConnection(url);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                int code = conn.getResponseCode();
                if (code > 0) {
                    return true;
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            sleepQuietly(1000);
        }
        return false;
    }

    private static String sendPortalLoginPost(Network network, String loginUrl, String username, String password) {
        HttpURLConnection conn = null;
        try {
            String u = URLEncoder.encode(username, "UTF-8");
            String p = URLEncoder.encode(password, "UTF-8");
            String body = "wname=" + u + "&wpwd=" + p + "&login=%B5%C7%C2%BC";
            byte[] postBytes = body.getBytes(StandardCharsets.UTF_8);

            URL url = new URL(loginUrl);
            conn = (HttpURLConnection) network.openConnection(url);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; HarmonyOS 4.2; Tablet) AppleWebKit/537.36");
            conn.setFixedLengthStreamingMode(postBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            return readStream(is).replaceAll("\\s+", " ").trim();
        } catch (Throwable e) {
            return "请求异常: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[512];
            int n;
            while ((n = reader.read(buf)) != -1 && sb.length() < 1024) {
                sb.append(buf, 0, n);
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static void showFailureNotification(Context ctx, String message) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        FAIL_CHANNEL_ID,
                        "校园网登录失败提醒",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("仅在校园网连续自动登录失败时提醒");
                nm.createNotificationChannel(channel);
            }

            Intent intent = new Intent(ctx, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent, flags);

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(ctx, FAIL_CHANNEL_ID)
                    : new Notification.Builder(ctx);

            builder.setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("DX-WLAN 校园网自动登录失败")
                    .setContentText(message)
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            nm.notify(FAIL_NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {}
    }

    private static void notifyCallback(final Callback callback, final boolean success, final String summary) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                callback.onResult(success, summary);
            }
        });
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

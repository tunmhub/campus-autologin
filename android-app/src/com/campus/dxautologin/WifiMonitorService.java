package com.campus.dxautologin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;

public class WifiMonitorService extends Service {
    private static final String MONITOR_CHANNEL_ID = "dx_wifi_monitor_silent";
    private static final int MONITOR_NOTIFICATION_ID = 1001;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private BroadcastReceiver screenUnlockReceiver;
    private volatile long lastTriggerTimeMs = 0L;

    public static void ensureRunning(Context context) {
        Context appCtx = context.getApplicationContext();
        if (!AppConfig.isAutoEnabled(appCtx)) {
            return;
        }
        try {
            Intent intent = new Intent(appCtx, WifiMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent);
            } else {
                appCtx.startService(intent);
            }
        } catch (Throwable ignored) {}
    }

    public static void stopServiceIfRunning(Context context) {
        try {
            Context appCtx = context.getApplicationContext();
            appCtx.stopService(new Intent(appCtx, WifiMonitorService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startSilentForeground();
        registerWifiNetworkCallback();
        registerScreenUnlockReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startSilentForeground();
        if (!AppConfig.isAutoEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        debouncedTrigger("服务启动巡检", 1000L);
        return START_STICKY;
    }

    private void startSilentForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        MONITOR_CHANNEL_ID,
                        "DX校园网后台静默守护",
                        NotificationManager.IMPORTANCE_MIN
                );
                channel.setDescription("保持后台监听 DX-WLAN 自动登录（可长按隐藏此静默通知）");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            Intent intent = new Intent(this, MainActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, MONITOR_CHANNEL_ID)
                    : new Notification.Builder(this);

            builder.setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("DX-WLAN 自动登录守护中")
                    .setContentText("连上校园网或掉线时将自动静默认证")
                    .setContentIntent(pi)
                    .setOngoing(true);

            startForeground(MONITOR_NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {}
    }

    private void registerWifiNetworkCallback() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();

            wifiCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    debouncedTrigger("Wi-Fi连网事件", 1500L);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (caps == null) return;
                    // 当出现 Captive Portal 标记，或者 Wi-Fi 已连接但失去互联网认证（如凌晨 0 点校园网强制下线）时触发补登
                    boolean hasPortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
                    boolean isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (hasPortal || !isValidated) {
                        debouncedTrigger(hasPortal ? "检测到Portal认证拦截" : "Wi-Fi待认证状态巡检", 4000L);
                    }
                }
            };
            connectivityManager.registerNetworkCallback(request, wifiCallback);
        } catch (Throwable ignored) {}
    }

    private void registerScreenUnlockReceiver() {
        try {
            screenUnlockReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
                        debouncedTrigger("亮屏解锁巡检", 8000L);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenUnlockReceiver, filter);
        } catch (Throwable ignored) {}
    }

    private void debouncedTrigger(String reason, long minIntervalMs) {
        long now = System.currentTimeMillis();
        if (now - lastTriggerTimeMs < minIntervalMs) {
            return;
        }
        lastTriggerTimeMs = now;
        CampusLoginEngine.triggerAsync(this, reason, false, null);
    }

    @Override
    public void onDestroy() {
        try {
            if (connectivityManager != null && wifiCallback != null) {
                connectivityManager.unregisterNetworkCallback(wifiCallback);
            }
        } catch (Throwable ignored) {}
        try {
            if (screenUnlockReceiver != null) {
                unregisterReceiver(screenUnlockReceiver);
            }
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

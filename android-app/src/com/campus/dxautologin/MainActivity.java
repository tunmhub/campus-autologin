package com.campus.dxautologin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText etUsername;
    private EditText etPassword;
    private EditText etSsid;
    private EditText etLoginUrl;
    private Switch swAutoEnabled;
    private Switch swPreferDx;
    private TextView tvBatteryStatus;
    private TextView tvLocationStatus;
    private TextView tvLogs;
    private Button btnTestLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        loadConfigToViews();
        requestInitialPermissions();
        WifiMonitorService.ensureRunning(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSystemStatus();
        refreshLogsView();
    }

    private void requestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != 0) {
                    requestPermissions(new String[]{
                            "android.permission.ACCESS_FINE_LOCATION",
                            "android.permission.ACCESS_COARSE_LOCATION",
                            "android.permission.POST_NOTIFICATIONS"
                    }, 102);
                }
            } catch (Throwable ignored) {}
        }
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F4F6F9"));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        // 1. 顶部标题卡片
        LinearLayout headerCard = createCard("#1565C0");
        TextView title = new TextView(this);
        title.setText("DX-WLAN 校园网自动登录 v1.1");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerCard.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("鸿蒙 4.2 专版 · 回宿舍自动从 GD-WLAN 切换至 DX-WLAN · 零弹窗打断");
        subtitle.setTextColor(Color.parseColor("#E3F2FD"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, dp(6), 0, 0);
        headerCard.addView(subtitle);
        root.addView(headerCard, marginBottom(dp(14)));

        // 2. 鸿蒙后台保活与抢切设置卡片
        LinearLayout keepAliveCard = createCard("#FFFFFF");
        TextView kaTitle = createSectionTitle("一、鸿蒙 4.2 保活与「回宿舍优先连 DX」权限");
        keepAliveCard.addView(kaTitle);

        tvBatteryStatus = new TextView(this);
        tvBatteryStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvBatteryStatus.setPadding(0, dp(6), 0, dp(2));
        keepAliveCard.addView(tvBatteryStatus);

        tvLocationStatus = new TextView(this);
        tvLocationStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvLocationStatus.setPadding(0, dp(2), 0, dp(10));
        keepAliveCard.addView(tvLocationStatus);

        LinearLayout kaBtnRow = new LinearLayout(this);
        kaBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnIgnoreBattery = createStyledButton("1. 解除省电限制", "#0277BD", Color.WHITE);
        btnIgnoreBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimizations();
            }
        });
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpLeft.rightMargin = dp(8);
        kaBtnRow.addView(btnIgnoreBattery, lpLeft);

        Button btnGrantLoc = createStyledButton("2. 授权扫描DX信号", "#059669", Color.WHITE);
        btnGrantLoc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestLocationPermissionExplicit();
            }
        });
        LinearLayout.LayoutParams lpMid = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpMid.rightMargin = dp(8);
        kaBtnRow.addView(btnGrantLoc, lpMid);

        Button btnAppSettings = createStyledButton("3. 启动管理/权限", "#E0E6ED", Color.parseColor("#1F2937"));
        btnAppSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHuaweiStartupSettings();
            }
        });
        LinearLayout.LayoutParams lpRight = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        kaBtnRow.addView(btnAppSettings, lpRight);

        keepAliveCard.addView(kaBtnRow);

        TextView kaHint = new TextView(this);
        kaHint.setText("【重要】请去平板「设置 -> WLAN -> 更多 WLAN 设置 -> WLAN+」把 WLAN+ 关掉！\n这样在教学楼/图书馆（搜不到 DX-WLAN）时平板仍会自动连 GD-WLAN；一旦回到宿舍（搜到 DX-WLAN），本软件就会自动把 Wi-Fi 从 GD 切到 DX 并完成登录，系统也不会再跳回 GD。");
        kaHint.setTextColor(Color.parseColor("#B45309"));
        kaHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        kaHint.setPadding(0, dp(10), 0, 0);
        keepAliveCard.addView(kaHint);
        root.addView(keepAliveCard, marginBottom(dp(14)));

        // 3. 账号与参数配置卡片
        LinearLayout configCard = createCard("#FFFFFF");
        configCard.addView(createSectionTitle("二、校园网账号与自动切换策略"));

        configCard.addView(createFieldLabel("校园网账号 (wname)"));
        etUsername = createInputBox("输入校园网账号", false);
        configCard.addView(etUsername);

        configCard.addView(createFieldLabel("校园网密码 (wpwd)"));
        etPassword = createInputBox("输入校园网密码", false);
        configCard.addView(etPassword);

        configCard.addView(createFieldLabel("宿舍优先 Wi-Fi 名称 (SSID)"));
        etSsid = createInputBox("默认 DX-WLAN", false);
        configCard.addView(etSsid);

        configCard.addView(createFieldLabel("Portal 登录接口地址"));
        etLoginUrl = createInputBox("http://172.18.0.11/cgi-bin/wlogin.cgi", false);
        configCard.addView(etLoginUrl);

        swPreferDx = new Switch(this);
        swPreferDx.setText("回宿舍自动从 GD-WLAN 切换到 DX-WLAN（教室无 DX 信号时保持连 GD）");
        swPreferDx.setTextColor(Color.parseColor("#111827"));
        swPreferDx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        swPreferDx.setPadding(0, dp(12), 0, dp(6));
        configCard.addView(swPreferDx);

        swAutoEnabled = new Switch(this);
        swAutoEnabled.setText("开启全场景自动静默登录（连网 / 半夜0点掉线 / 亮屏解锁）");
        swAutoEnabled.setTextColor(Color.parseColor("#111827"));
        swAutoEnabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        swAutoEnabled.setPadding(0, dp(6), 0, dp(12));
        configCard.addView(swAutoEnabled);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnSave = createStyledButton("保存配置", "#1565C0", Color.WHITE);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCurrentConfig(true);
            }
        });
        actionRow.addView(btnSave, lpLeft);

        btnTestLogin = createStyledButton("立即检测 / 测试切换与登录", "#059669", Color.WHITE);
        btnTestLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCurrentConfig(false);
                btnTestLogin.setEnabled(false);
                btnTestLogin.setText("正在检测...");
                CampusLoginEngine.triggerAsync(MainActivity.this, "手动测试按钮", true, new CampusLoginEngine.Callback() {
                    @Override
                    public void onResult(boolean success, String summary) {
                        btnTestLogin.setEnabled(true);
                        btnTestLogin.setText("立即检测 / 测试切换与登录");
                        refreshLogsView();
                        Toast.makeText(MainActivity.this, summary, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        actionRow.addView(btnTestLogin, lpRight);
        configCard.addView(actionRow);

        root.addView(configCard, marginBottom(dp(14)));

        // 4. 运行日志卡片
        LinearLayout logCard = createCard("#FFFFFF");
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView logTitle = createSectionTitle("三、最近运行日志（成功时全静默，仅失败弹通知）");
        LinearLayout.LayoutParams logTitleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        logHeader.addView(logTitle, logTitleLp);

        Button btnRefreshLog = createSmallButton("刷新");
        btnRefreshLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLogsView();
            }
        });
        logHeader.addView(btnRefreshLog);

        Button btnClearLog = createSmallButton("清空");
        btnClearLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppConfig.clearLogs(MainActivity.this);
                refreshLogsView();
            }
        });
        logHeader.addView(btnClearLog);
        logCard.addView(logHeader);

        tvLogs = new TextView(this);
        tvLogs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvLogs.setTypeface(Typeface.MONOSPACE);
        tvLogs.setTextColor(Color.parseColor("#1F2937"));
        tvLogs.setBackgroundColor(Color.parseColor("#F8FAFC"));
        int logPad = dp(10);
        tvLogs.setPadding(logPad, logPad, logPad, logPad);
        LinearLayout.LayoutParams logBoxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logBoxLp.topMargin = dp(8);
        logCard.addView(tvLogs, logBoxLp);

        root.addView(logCard);
        scrollView.addView(root);
        return scrollView;
    }

    private void loadConfigToViews() {
        etUsername.setText(AppConfig.getUsername(this));
        etPassword.setText(AppConfig.getPassword(this));
        etSsid.setText(AppConfig.getSsid(this));
        etLoginUrl.setText(AppConfig.getLoginUrl(this));
        swAutoEnabled.setChecked(AppConfig.isAutoEnabled(this));
        swPreferDx.setChecked(AppConfig.isPreferDxEnabled(this));
    }

    private void saveCurrentConfig(boolean showToast) {
        String u = etUsername.getText().toString().trim();
        String p = etPassword.getText().toString();
        String s = etSsid.getText().toString().trim();
        String url = etLoginUrl.getText().toString().trim();
        boolean auto = swAutoEnabled.isChecked();
        boolean preferDx = swPreferDx.isChecked();

        AppConfig.saveConfig(this, u, p, s, url, auto, preferDx);
        if (auto) {
            WifiMonitorService.ensureRunning(this);
        } else {
            WifiMonitorService.stopServiceIfRunning(this);
        }
        if (showToast) {
            Toast.makeText(this, "配置已保存，自动抢切 DX 与静默登录已生效", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshSystemStatus() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean ignoring = (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    && pm.isIgnoringBatteryOptimizations(getPackageName());
            if (ignoring) {
                tvBatteryStatus.setText("省电白名单：✓ 已加入电池优化白名单（后台守护不易被清理）");
                tvBatteryStatus.setTextColor(Color.parseColor("#059669"));
            } else {
                tvBatteryStatus.setText("省电白名单：⚠ 尚未解除省电限制，请点击下方按钮 1");
                tvBatteryStatus.setTextColor(Color.parseColor("#D97706"));
            }

            boolean hasLoc = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                hasLoc = checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == 0;
            }
            if (hasLoc) {
                tvLocationStatus.setText("Wi-Fi 信号识别权限：✓ 已授权（可在连上 GD-WLAN 时自动扫描周围是否有 DX-WLAN 并切换）");
                tvLocationStatus.setTextColor(Color.parseColor("#059669"));
            } else {
                tvLocationStatus.setText("Wi-Fi 信号识别权限：⚠ 尚未授予位置权限（无法从 GD 自动切到 DX），请点击下方按钮 2");
                tvLocationStatus.setTextColor(Color.parseColor("#D97706"));
            }
        } catch (Throwable ignored) {}
    }

    private void requestLocationPermissionExplicit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != 0) {
                requestPermissions(new String[]{
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_COARSE_LOCATION"
                }, 103);
                return;
            }
        }
        Toast.makeText(this, "已获得位置权限（若需后台锁屏也能切换，可在权限设置中选“始终允许”）", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshSystemStatus();
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                }
            }
            Toast.makeText(this, "已处于电池优化白名单中", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            openHuaweiStartupSettings();
        }
    }

    private void openHuaweiStartupSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this, "请在「权限」中将位置信息设为“始终允许”，并在「耗电管理」中允许自启动与后台运行", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private void refreshLogsView() {
        if (tvLogs != null) {
            tvLogs.setText(AppConfig.getLogs(this));
        }
    }

    private LinearLayout createCard(String bgColorHex) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgColorHex));
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(2));
        }
        return card;
    }

    private TextView createSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#111827"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView createFieldLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#4B5563"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp(10), 0, dp(4));
        return tv;
    }

    private EditText createInputBox(String hint, boolean isPassword) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setTextColor(Color.parseColor("#111827"));
        if (isPassword) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.parseColor("#F9FAFB"));
        border.setCornerRadius(dp(8));
        border.setStroke(dp(1), Color.parseColor("#D1D5DB"));
        et.setBackground(border);
        int p = dp(10);
        et.setPadding(p, p, p, p);
        return et;
    }

    private Button createStyledButton(String text, String bgHex, int textColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgHex));
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        int p = dp(10);
        btn.setPadding(p, p, p, p);
        return btn;
    }

    private Button createSmallButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setAllCaps(false);
        return btn;
    }

    private LinearLayout.LayoutParams marginBottom(int bottomPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomPx;
        return lp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}

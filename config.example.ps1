# ===== 校园网自动登录配置文件模板 =====
# 初次使用请将本文件复制并重命名为 config.ps1，然后把下面两行改成你自己的校园网账号和密码
$CampusUsername = "你的校园网账号"
$CampusPassword = "你的校园网密码"

# 只在这个 WiFi 下工作;改成 $null 表示任意 WiFi 都检测
$WlanSsid = "DX-WLAN"

# Portal 服务器信息(已探测好,一般不需要改)
$PortalLoginUrl = "http://172.18.0.11/cgi-bin/wlogin.cgi"
# Portal 探测地址:用于等待网络栈恢复(本地网段,恢复即可达,比盲等外网检测快)
$PortalProbeUrl = "http://172.18.0.11/"

# 在线检测地址:全部失败才判定为离线(降低网络抖动导致的误判)
# 注意:OkBody 非空时要求响应内容包含该词,防止 Portal 劫持页返回 200 被误判为在线
$OnlineCheckUrls = @(
    @{ Url = "http://www.gstatic.com/generate_204";          OkCode = 204 },
    @{ Url = "http://connect.rom.miui.com/generate_204";     OkCode = 204 },
    @{ Url = "http://captive.apple.com/hotspot-detect.html"; OkCode = 200; OkBody = "Success" }
)

# ===== 校园网自动登录主脚本 =====
# 流程:检查 WiFi -> 两轮在线检测(在线则直接退出,绝不发登录请求)
#       -> 确认离线才向 Portal 提交登录 -> 复查网络确认结果(失败时弹系统通知)
param([switch]$WaitNetwork)  # 登录触发时传入:智能检测是否在宿舍及锁屏连网状态
$ErrorActionPreference = "Continue"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $ScriptDir "logs"
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
$LogFile = Join-Path $LogDir ("autologin_{0}.log" -f (Get-Date -Format "yyyyMMdd"))

function Write-Log {
    param([string]$Level, [string]$Message)
    $line = "{0} [{1}] {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Level, $Message
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Show-FailureNotification {
    param([string]$Title, [string]$Message)
    try {
        [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
        [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
        $escapedTitle = [System.Security.SecurityElement]::Escape($Title)
        $escapedMsg = [System.Security.SecurityElement]::Escape($Message)
        $xmlStr = "<toast><visual><binding template='ToastGeneric'><text>$escapedTitle</text><text>$escapedMsg</text></binding></visual></toast>"
        $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
        $xml.LoadXml($xmlStr)
        $toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
        $appId = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe"
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($appId).Show($toast)
    } catch {
        # 系统通知不可用时静默忽略，避免阻塞退出
    }
}

# 清理 7 天前的旧日志
Get-ChildItem -Path $LogDir -Filter "autologin_*.log" -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-7) } |
    Remove-Item -ErrorAction SilentlyContinue

# 读取配置
. (Join-Path $ScriptDir "config.ps1")

if ([string]::IsNullOrEmpty($CampusUsername) -or [string]::IsNullOrEmpty($CampusPassword)) {
    Write-Log "ERROR" "config.ps1 中账号或密码为空,请先填写校园网账号密码"
    Show-FailureNotification -Title "校园网自动登录失败" -Message "config.ps1 中账号或密码为空，请先填写校园网账号密码。"
    exit 1
}

# --- 1. 检查是否在指定 WiFi 下 ---
function Get-CurrentSsid {
    foreach ($line in (& netsh.exe wlan show interfaces 2>$null)) {
        if ($line -match "^\s*SSID\s" -and $line -notmatch "BSSID") {
            return ($line -split ":", 2)[1].Trim()
        }
    }
    return $null
}

function Test-SsidVisible {
    param([string]$TargetSsid)
    foreach ($line in (& netsh.exe wlan show networks 2>$null)) {
        if ($line -match "^\s*SSID\s+\d+\s*:\s*(.+)$") {
            if ($Matches[1].Trim() -eq $TargetSsid) { return $true }
        }
    }
    return $false
}

if ($WlanSsid) {
    $currentSsid = Get-CurrentSsid
    if ($currentSsid -ne $WlanSsid) {
        if (-not $WaitNetwork) {
            Write-Log "INFO" "当前 WiFi [$currentSsid] 不是 [$WlanSsid],不处理"
            exit 0
        }
        # 开机登录兜底场景:
        # 1) 若已连接其他 WiFi(如手机热点或其他网络),直接秒退
        if (-not [string]::IsNullOrEmpty($currentSsid)) {
            Write-Log "INFO" "开机检测到已连接其他 WiFi [$currentSsid],非 [$WlanSsid],直接退出"
            exit 0
        }
        # 2) 若当前未连接任何 WiFi,扫描周围是否有 DX-WLAN 信号;不在宿舍则直接秒退
        if (-not (Test-SsidVisible -TargetSsid $WlanSsid)) {
            Write-Log "INFO" "开机检测周围无 [$WlanSsid] 信号(不在宿舍),直接退出"
            exit 0
        }
        # 3) 周围有 DX-WLAN 信号且网卡正在自动连接中,最多等待 30 秒(后续亦有连网事件兜底)
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $currentSsid = Get-CurrentSsid
            if ($currentSsid -eq $WlanSsid) { break }
            if (-not [string]::IsNullOrEmpty($currentSsid) -and $currentSsid -ne $WlanSsid) { break }
        }
        if ($currentSsid -ne $WlanSsid) {
            Write-Log "INFO" "开机等待 30 秒未连接 [$WlanSsid](当前: [$currentSsid]),交由连网事件接管"
            exit 0
        }
        Write-Log "INFO" "开机检测到 WiFi [$WlanSsid] 已连接,继续登录流程"
    }
}

# --- 2. 在线检测(在线则退出,绝不碰登录接口)---
function Test-Online {
    foreach ($check in $OnlineCheckUrls) {
        if ($check.OkBody) {
            # 带内容校验:劫持页虽返回 200 但不含 Success,不能算在线
            $body = (& curl.exe -s --max-time 3 $check.Url 2>$null) -join " "
            if ($body -like "*$($check.OkBody)*") { return $true }
        } else {
            $code = & curl.exe -s -o NUL -w "%{http_code}" --max-time 3 $check.Url 2>$null
            if ("$code" -eq "$($check.OkCode)") { return $true }
        }
    }
    return $false
}

# 等待网络栈恢复:Portal 服务器(本地网段)可达即恢复,比盲等外网检测快得多
function Wait-PortalReady {
    param([int]$TimeoutSec = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $code = & curl.exe -s -o NUL -w "%{http_code}" --max-time 2 $PortalProbeUrl 2>$null
        if ("$code" -ne "000") { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

# 快速在线探测(在线场景秒退)
if (Test-Online) {
    Write-Log "INFO" "网络已在线,无需登录"
    exit 0
}

# 等待网络栈恢复(Portal 可达即恢复,避免盲等外网检测超时)
if (-not (Wait-PortalReady -TimeoutSec 30)) {
    Write-Log "WARN" "等待 30 秒 Portal 仍不可达,网络栈可能未恢复,仍尝试登录"
}

# 二次确认离线(防快速探测误判)
Start-Sleep -Seconds 1
if (Test-Online) {
    Write-Log "INFO" "复检网络已在线,无需登录"
    exit 0
}
Write-Log "INFO" "确认离线,开始自动登录..."

# --- 3. 提交登录(模拟浏览器表单,login 字段为 GBK 编码的“登录”)---
$u = [System.Uri]::EscapeDataString($CampusUsername)
$p = [System.Uri]::EscapeDataString($CampusPassword)
$body = "wname=$u&wpwd=$p&login=%B5%C7%C2%BC"
$responseText = ""

for ($attempt = 1; $attempt -le 2; $attempt++) {
    $response = & curl.exe -s --max-time 10 -X POST -H "Content-Type: application/x-www-form-urlencoded" -d $body $PortalLoginUrl 2>$null
    $responseText = ($response -join " ")

    # --- 4. 登录后复查网络确认结果 ---
    Start-Sleep -Seconds 2
    if (Test-Online) {
        Write-Log "OK" "登录成功,网络已恢复(第 $attempt 次尝试)"
        exit 0
    }
    if ($attempt -eq 1) {
        Write-Log "WARN" "第 1 次登录尝试未见效,5 秒后重试(可能是开机后 Portal 未就绪)"
        Start-Sleep -Seconds 5
    }
}

$respSnippet = $responseText -replace "\s+", " "
if ($respSnippet.Length -gt 300) { $respSnippet = $respSnippet.Substring(0, 300) }
if ([string]::IsNullOrEmpty($respSnippet)) { $respSnippet = "(Portal 无响应)" }
Write-Log "ERROR" "登录 2 次后仍无法上网,请检查账号密码是否正确。Portal 响应: $respSnippet"
Show-FailureNotification -Title "DX-WLAN 校园网自动登录失败" -Message "连续 2 次尝试登录未成功，请检查校园网账号密码或认证状态（详见 logs 日志）。"
exit 1

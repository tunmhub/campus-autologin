# ===== 注册校园网自动登录计划任务 =====
# 任务 1:CampusAutoLogin-NetEvent —— 仅在连接目标 WiFi (DX-WLAN) 事件时触发,通过 wscript.exe 彻底静默无黑窗运行
# 任务 2:CampusAutoLogin-Logon    —— 用户登录触发(锁屏连网兜底;非目标 WiFi 或周围无信号时秒退,不盲等 3 分钟)
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LoginScript = Join-Path $ScriptDir "auto_login.ps1"
$HiddenLauncher = Join-Path $ScriptDir "run_hidden.vbs"
$ConfigScript = Join-Path $ScriptDir "config.ps1"
$NetEventTask = "CampusAutoLogin-NetEvent"
$LogonTask = "CampusAutoLogin-Logon"

if (-not (Test-Path $LoginScript)) {
    Write-Host "找不到 auto_login.ps1: $LoginScript" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $HiddenLauncher)) {
    Write-Host "找不到 run_hidden.vbs: $HiddenLauncher" -ForegroundColor Red
    exit 1
}

$WlanSsid = "DX-WLAN"
if (Test-Path $ConfigScript) {
    . $ConfigScript
}

# 清理旧版任务
Unregister-ScheduledTask -TaskName "CampusAutoLogin" -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $NetEventTask -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $LogonTask -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName "CampusAutoLogin-Boot" -Confirm:$false -ErrorAction SilentlyContinue

# 构造 XPath 订阅:在计划任务底层直接锁定目标 SSID (Description='DX-WLAN')
# 彻底杜绝连接其他 WiFi、手机热点或开启加速器虚拟网卡(如 SpeedCat)时误触发
if (-not [string]::IsNullOrEmpty($WlanSsid)) {
    $xpathSelect = "*[System[EventID=10000]] and *[EventData[Data[@Name='Description']='$WlanSsid']]"
} else {
    $xpathSelect = "*[System[EventID=10000]]"
}
$subscriptionXml = "<QueryList><Query Id=`"0`" Path=`"Microsoft-Windows-NetworkProfile/Operational`"><Select>$xpathSelect</Select></Query></QueryList>"

# --- 任务 1:网络连接事件(仅连上 DX-WLAN 时触发,延迟 2 秒快速认证)---
$action1 = New-ScheduledTaskAction -Execute "wscript.exe" `
    -Argument "`"$HiddenLauncher`""
$trigger1 = New-CimInstance -CimClass `
    (Get-CimClass -ClassName MSFT_TaskEventTrigger -Namespace Root/Microsoft/Windows/TaskScheduler) `
    -ClientOnly -Property @{
        Enabled = $true
        Subscription = $subscriptionXml
        Delay = 'PT2S'
    }
$settings1 = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 5) `
    -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $NetEventTask -Action $action1 -Trigger $trigger1 -Settings $settings1 | Out-Null

# --- 任务 2:登录触发(锁屏期间已连上 DX-WLAN 的兜底;不在宿舍或连其他 WiFi 时秒退)---
$action2 = New-ScheduledTaskAction -Execute "wscript.exe" `
    -Argument "`"$HiddenLauncher`" -WaitNetwork"
$trigger2 = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings2 = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 2) `
    -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $LogonTask -Action $action2 -Trigger $trigger2 -Settings $settings2 | Out-Null

Write-Host "计划任务 [$NetEventTask](仅 $WlanSsid 触发 + 零黑窗)与 [$LogonTask](开机智能兜底 + 零黑窗)注册成功" -ForegroundColor Green
Get-ScheduledTask -TaskName $NetEventTask, $LogonTask | Format-Table TaskName, State -AutoSize

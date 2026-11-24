# 校园网自动登录工具

连上校园网 WiFi(`DX-WLAN`)后自动完成 Portal 认证,全程零黑窗闪烁、不在其他网络或开启加速器时误触发。

## 快速开始(只需一步)

打开 [config.ps1](config.ps1),把账号密码填上:

```powershell
$CampusUsername = "你的校园网账号"
$CampusPassword = "你的校园网密码"
```

保存即可,无需重启电脑。计划任务已注册好,之后只要连上 `DX-WLAN`:

- **连上网 2~5 秒内**立即自动登录(计划任务底层 XPath 精准绑定 `DX-WLAN` 连接事件,连其他 WiFi、手机热点或开关加速器虚拟网卡时绝不触发)
- **全程零黑窗闪烁**:通过 `wscript.exe` 调用 [run_hidden.vbs](run_hidden.vbs) 以 `SW_HIDE` 模式运行
- **开机锁屏连网智能兜底**:登录桌面时触发一次检查,若已连其他 WiFi 或周围无 `DX-WLAN` 信号(不在宿舍)立即秒退,不再后台盲等 3 分钟
- **异常感知**:平时保持纯静默写日志;仅当连续 2 次登录失败(如账号密码错误)时弹出 Windows 右下角通知提醒

## 工作原理

```
触发(仅 DX-WLAN 连网事件 CampusAutoLogin-NetEvent / 登录桌面兜底 CampusAutoLogin-Logon)
    ↓
wscript.exe 启动 run_hidden.vbs (GUI 子系统 + SW_HIDE 彻底消除黑窗)
    ↓
检查当前 WiFi 是否为 DX-WLAN(Logon 触发时若已连其他 WiFi 或周围无 DX-WLAN 信号直接秒退)
    ↓
两轮在线检测(generate_204 ×2 + captive.apple.com 内容校验,防劫持页误判)
    ↓ 已在线 → 直接退出,绝不发登录请求
    ↓ 两轮都失败才判定离线
向 http://172.18.0.11/cgi-bin/wlogin.cgi POST 表单登录(Dr.COM 系统)
    ↓
再次检测网络,确认登录成功 → 记录日志(若失败则记录日志并弹 Windows 右下角通知)
```

## 目录结构

| 文件 | 作用 |
|---|---|
| `config.ps1` | 配置文件:**账号密码**、SSID(`DX-WLAN`)、Portal 地址 |
| `run_hidden.vbs` | VBS 隐藏窗口启动器:通过 `wscript.exe` 彻底消除控制台黑窗闪烁 |
| `auto_login.ps1` | 主脚本:智能判断 WiFi → 检测离线 → 自动登录 → 写日志/失败通知 |
| `install_task.ps1` | 注册/重装计划任务 `CampusAutoLogin-NetEvent` 和 `CampusAutoLogin-Logon` |
| `logs/autologin_日期.log` | 运行日志,自动清理 7 天前的旧日志 |

## 手动测试(可选)

立即静默触发一次(与计划任务行为完全一致,无黑窗):

```powershell
Start-ScheduledTask -TaskName "CampusAutoLogin-Logon"
```

查看日志:

```powershell
Get-Content "C:\Users\17256\ZCodeProject\campus-autologin\logs\autologin_$(Get-Date -Format yyyyMMdd).log" -Tail 20
```

## 常见问题

- **改了校园网密码**:重新编辑 `config.ps1` 即可。
- **换成了别的 WiFi**:改 `config.ps1` 里的 `$WlanSsid` 后,重新运行一次 `install_task.ps1` 更新计划任务的事件过滤条件。
- **日志里报"登录后仍无法上网"**:通常是密码错了,或账号欠费/被认证系统锁定,登录 Portal 页面手动试一次确认原因。
- **想暂时停用**:`schtasks /change /tn CampusAutoLogin-NetEvent /disable` 和 `schtasks /change /tn CampusAutoLogin-Logon /disable`
- **彻底卸载**:禁用上面两个任务后,`schtasks /delete /tn CampusAutoLogin-NetEvent /f` 和 `schtasks /delete /tn CampusAutoLogin-Logon /f`,最后删除整个 `campus-autologin` 文件夹。
- **重新启用/重装**:运行一次 `install_task.ps1` 即可。

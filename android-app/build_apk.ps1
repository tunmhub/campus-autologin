$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ProjectDir
$SdkDir = "$env:LOCALAPPDATA\Android\Sdk"
$BuildTools = "$SdkDir\build-tools\34.0.0"
$AndroidJar = "$SdkDir\platforms\android-33\android.jar"

$Aapt2 = "$BuildTools\aapt2.exe"
$ZipAlign = "$BuildTools\zipalign.exe"
$D8Jar = "$BuildTools\lib\d8.jar"
$ApkSignerJar = "$BuildTools\lib\apksigner.jar"

if (-not (Test-Path $Aapt2) -or -not (Test-Path $AndroidJar)) {
    throw "Android SDK build-tools 或 android.jar 未就绪，请检查 $SdkDir"
}

$BuildDir = Join-Path $ProjectDir "build"
if (Test-Path $BuildDir) {
    Remove-Item $BuildDir -Recurse -Force
}
New-Item -ItemType Directory -Path "$BuildDir\compiled_res", "$BuildDir\gen", "$BuildDir\classes", "$BuildDir\dex" -Force | Out-Null

Write-Host "[1/6] 使用 aapt2 编译资源..." -ForegroundColor Cyan
Get-ChildItem -Path "$ProjectDir\res" -File -Recurse | ForEach-Object {
    & $Aapt2 compile $_.FullName -o "$BuildDir\compiled_res"
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败: $($_.FullName)" }
}

Write-Host "[2/6] 使用 aapt2 链接资源并生成 R.java..." -ForegroundColor Cyan
$FlatFiles = (Get-ChildItem "$BuildDir\compiled_res\*.flat").FullName
$UnalignedApk = "$BuildDir\unsigned.unaligned.apk"
& $Aapt2 link -o $UnalignedApk `
    -I $AndroidJar `
    --manifest "$ProjectDir\AndroidManifest.xml" `
    --java "$BuildDir\gen" `
    $FlatFiles
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "[3/6] 使用 javac 编译 Java 源码..." -ForegroundColor Cyan
$JavaFiles = @()
$JavaFiles += (Get-ChildItem -Path "$ProjectDir\src" -Filter "*.java" -Recurse).FullName
$JavaFiles += (Get-ChildItem -Path "$BuildDir\gen" -Filter "*.java" -Recurse).FullName

& javac -encoding UTF-8 --release 8 -classpath $AndroidJar -d "$BuildDir\classes" $JavaFiles
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }

# 兼容 JDK 21+/25 生成的匿名内部类无名 MethodParameters 属性，避免旧版 D8 空指针
$mpBytes = [System.Text.Encoding]::UTF8.GetBytes("MethodParameters")
Get-ChildItem -Path "$BuildDir\classes" -Filter "*.class" -Recurse | ForEach-Object {
    $b = [System.IO.File]::ReadAllBytes($_.FullName)
    $changed = $false
    for ($i = 0; $i -le $b.Length - $mpBytes.Length; $i++) {
        $m = $true
        for ($j = 0; $j -lt $mpBytes.Length; $j++) {
            if ($b[$i + $j] -ne $mpBytes[$j]) { $m = $false; break }
        }
        if ($m) { $b[$i] = [byte][char]'X'; $changed = $true }
    }
    if ($changed) { [System.IO.File]::WriteAllBytes($_.FullName, $b) }
}

Write-Host "[4/6] 使用 D8 将字节码转换为 classes.dex..." -ForegroundColor Cyan
$ClassFiles = (Get-ChildItem -Path "$BuildDir\classes" -Filter "*.class" -Recurse).FullName
& java -cp $D8Jar com.android.tools.r8.D8 `
    --lib $AndroidJar `
    --min-api 26 `
    --output "$BuildDir\dex" `
    $ClassFiles
if ($LASTEXITCODE -ne 0) { throw "D8 转换失败" }

Write-Host "[5/6] 打包 classes.dex 并执行 zipalign 4 字节对齐..." -ForegroundColor Cyan
& jar -uf $UnalignedApk -C "$BuildDir\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "打包 classes.dex 失败" }

$AlignedApk = "$BuildDir\unsigned.aligned.apk"
& $ZipAlign -f -p 4 $UnalignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "[6/6] 使用 V1+V2+V3 签名生成正式 APK..." -ForegroundColor Cyan
$KeystorePath = Join-Path $ProjectDir "dx_release.jks"
if (-not (Test-Path $KeystorePath)) {
    & keytool -genkeypair -v `
        -keystore $KeystorePath `
        -alias dxkey `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -storepass 123456 `
        -keypass 123456 `
        -dname "CN=DXAutoLogin, OU=Dorm, O=Campus, L=CN, S=CN, C=CN" | Out-Null
}

$OutApk1 = Join-Path $RootDir "DX校园网自动登录_鸿蒙专版.apk"
$OutApk2 = Join-Path $ProjectDir "DXAutoLogin.apk"

& java -jar $ApkSignerJar sign `
    --ks $KeystorePath `
    --ks-key-alias dxkey `
    --ks-pass pass:123456 `
    --key-pass pass:123456 `
    --out $OutApk1 `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner 签名失败" }

Copy-Item $OutApk1 $OutApk2 -Force
& java -jar $ApkSignerJar verify --verbose $OutApk1

$apkItem = Get-Item $OutApk1
Write-Host "========================================" -ForegroundColor Green
Write-Host "APK 构建与签名成功！" -ForegroundColor Green
Write-Host "文件路径: $($apkItem.FullName)" -ForegroundColor Green
Write-Host "文件大小: $([Math]::Round($apkItem.Length / 1KB, 1)) KB" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
